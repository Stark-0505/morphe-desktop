/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.patches

import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.FileStamp
import app.morphe.engine.util.Logger
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keeps a patch bundle that cannot be read from taking the whole app down with it, on every
 * launch, forever.
 *
 * A `.mpp` is a jar full of third-party code, and loading one runs that code inside this
 * process ([app.morphe.patcher.patch.loadPatchesFromJar] executes static initializers,
 * instantiates every `Patch` it finds, and may load native libraries a patch bundles). A
 * `Throwable` from that is already handled where it's caught (see the isolation in
 * [MultiSourceLoader]); what neither that nor a try/catch can reach is the process
 * disappearing outright — a hard JVM crash, `System.exit`, or the OS/user killing a hung
 * load. Nothing runs afterward to record what happened, so without this class the app would
 * retry the same fatal load on every subsequent launch and never get anywhere.
 *
 * Two mechanisms, ported from `app.morphe.manager.domain.repository.PatchBundleLoadGuard`:
 * an in-flight marker written before a load starts and removed after it finishes, so a
 * marker still present on the next launch means the previous process died mid-read; and a
 * strike ledger that holds a bundle back once the same file has done that twice in a row.
 * The Android-only dex-cache purge (stale ART quickening cache after a manager update) has
 * no JVM equivalent and is dropped — the class loading this uses doesn't leave that kind of
 * cache behind.
 *
 * One [PatchBundleLoadGuard] instance is shared across all sources (see [AppModule]); state
 * is keyed per [sourceId] + file [FileStamp] internally.
 */
class PatchBundleLoadGuard(
    private val stateDir: File,
) {
    private val ledgerFile = File(stateDir, LEDGER_FILE)

    private val lock = Any()
    private val strikes = mutableMapOf<String, Strike>()

    /** Reads currently running per source id. Concurrent reads of one source share one marker,
     *  which is only removed when the last of them finishes. */
    private val inFlight = mutableMapOf<String, Int>()
    private var prepared = false

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun prepare() = synchronized(lock) {
        if (prepared) return@synchronized
        prepared = true

        strikes.putAll(readLedger())
        recordInterruptedLoads()
    }

    /**
     * Runs [load] for [sourceId]'s bundle [patchesJar] under the guard.
     *
     * @throws PatchBundleHeldBackException if this exact file already took the process down on
     *   [HELD_BACK_AFTER] consecutive attempts. [load]'s own exceptions are not strikes (they are
     *   ordinary, recoverable failures) and pass through unchanged.
     */
    fun <T> read(sourceId: String, patchesJar: File, load: () -> T): T {
        prepare()

        val stamp = FileStamp.of(patchesJar)?.toString() ?: "unknown"

        synchronized(lock) {
            val strike = strikes[sourceId]
            if (strike != null) {
                if (strike.stamp != stamp) {
                    // A replaced/updated bundle ships different bytes and starts with a
                    // clean record — whatever the old file did says nothing about this one.
                    strikes.remove(sourceId)
                    writeLedger()
                } else if (strike.count >= HELD_BACK_AFTER) {
                    throw PatchBundleHeldBackException(sourceId)
                }
            }
        }

        beginRead(sourceId, stamp)
        try {
            return load().also {
                synchronized(lock) {
                    if (strikes.remove(sourceId) != null) writeLedger()
                }
            }
        } finally {
            endRead(sourceId)
        }
    }

    /** Forgets everything recorded about [sourceId], e.g. when the source is removed. */
    fun forget(sourceId: String) = synchronized(lock) {
        strikes.remove(sourceId)
        if (inFlight[sourceId] == null) runCatching { markerFor(sourceId).delete() }
        writeLedger()
    }

    private fun markerFor(sourceId: String) = File(stateDir, LoadMarker.fileName(sourceId))

    private fun beginRead(sourceId: String, stamp: String) = synchronized(lock) {
        val running = (inFlight[sourceId] ?: 0) + 1
        inFlight[sourceId] = running
        if (running == 1) {
            val (pid, start) = LoadMarker.currentProcess()
            val content = LoadMarker.encode(LoadMarker.Marker(sourceId, stamp, pid, start))
            runCatching { AtomicFiles.write(markerFor(sourceId), content) }
                .onFailure { Logger.warn("Could not mark source '$sourceId' as loading", it) }
        }
    }

    private fun endRead(sourceId: String) = synchronized(lock) {
        val remaining = (inFlight[sourceId] ?: 1) - 1
        if (remaining <= 0) {
            inFlight.remove(sourceId)
            runCatching { markerFor(sourceId).delete() }
        } else {
            inFlight[sourceId] = remaining
        }
    }

    /**
     * Turns markers left behind by a process that ended mid-read into strikes. Run once per
     * process, before any read.
     *
     * - Staging files from an interrupted marker write are deleted, never counted.
     * - A marker whose writer is still running (another window, the CLI) is left alone: that
     *   read is in progress, not interrupted.
     * - Markers from before the hashed-name format are still honoured, so an upgrade does not
     *   forget a bundle that was already crashing the app.
     */
    private fun recordInterruptedLoads() {
        val files = stateDir.listFiles { f -> f.name.startsWith(LoadMarker.PREFIX) } ?: return
        if (files.isEmpty()) return

        var changed = false
        for (file in files) {
            val name = file.name
            if (LoadMarker.isStagingFile(name)) {
                runCatching { file.delete() }
                continue
            }
            val text = runCatching { file.readText() }.getOrNull()
            val (sourceId, stamp) = if (LoadMarker.isMarkerFile(name)) {
                val marker = text?.let(LoadMarker::decode)
                if (marker == null) {
                    runCatching { file.delete() }   // malformed: nothing trustworthy to learn from it
                    continue
                }
                if (LoadMarker.writerIsAlive(marker)) continue
                marker.sourceId to marker.stamp
            } else {
                // Legacy marker: `reading-<sourceId>`, content = the stamp.
                name.removePrefix(LoadMarker.PREFIX) to text?.trim().orEmpty()
            }
            runCatching { file.delete() }
            if (sourceId.isBlank()) continue

            val previous = strikes[sourceId]?.takeIf { it.stamp == stamp }
            val count = (previous?.count ?: 0) + 1
            strikes[sourceId] = Strike(count, stamp)
            changed = true

            // A process can also die mid-read because the OS reclaimed it or the user quit
            // the app, so one interrupted read alone is not yet a verdict on the bundle.
            Logger.warn(
                "Source '$sourceId' was still loading when the previous process ended " +
                    "($count of $HELD_BACK_AFTER before it's held back)"
            )
        }
        if (changed) writeLedger()
    }

    private fun readLedger(): Map<String, Strike> = try {
        AtomicFiles.readState(
            file = ledgerFile,
            empty = emptyMap<String, Strike>(),
            onQuarantined = { preserved, cause ->
                Logger.warn("Could not read the bundle load ledger (${cause.message}); preserved it as ${preserved.name}")
            },
        ) { text ->
            val ledger = json.decodeFromString<LedgerFile>(text)
            check(ledger.version <= LEDGER_VERSION) { "unsupported ledger version ${ledger.version}" }
            ledger.strikes
        }
    } catch (e: java.io.IOException) {
        // The ledger is a safety aid, not user data: carry on without it rather than refusing to
        // load any bundle, but do not overwrite what could not be preserved (writeLedger would
        // replace it) — so writing is simply skipped for this session.
        Logger.warn("Could not read or preserve the bundle load ledger: ${e.message}")
        ledgerWritable = false
        emptyMap()
    }

    @Volatile
    private var ledgerWritable = true

    private fun writeLedger() {
        if (!ledgerWritable) return
        runCatching {
            if (strikes.isEmpty()) {
                ledgerFile.delete()
            } else {
                AtomicFiles.write(ledgerFile, json.encodeToString(LedgerFile(strikes = strikes.toMap())))
            }
        }.onFailure { Logger.warn("Could not record bundle load failures", it) }
    }

    @Serializable
    private data class Strike(val count: Int, val stamp: String)

    @Serializable
    private data class LedgerFile(val version: Int = LEDGER_VERSION, val strikes: Map<String, Strike> = emptyMap())

    private companion object {
        const val LEDGER_FILE = "bundle-load-failures.json"
        const val LEDGER_VERSION = 1

        const val HELD_BACK_AFTER = 2
    }
}

/**
 * Thrown in place of reading a bundle that has already ended the process while loading, from
 * the exact same file, more than once in a row.
 */
class PatchBundleHeldBackException(val sourceId: String) : Exception(
    "Source '$sourceId' previously crashed the app while loading and will not be read again " +
        "until its file changes"
)
