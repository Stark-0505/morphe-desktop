/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.MorpheData
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Tracks which patch names have already been shown to the user for each
 * (package, source), so a bundle update can tell a genuinely new patch
 * apart from one the user previously saw and left disabled — see
 * `PatchSelectionViewModel`'s `newPatchesByBundle`.
 *
 * Hardened to match `app.morphe.engine.PatchedAppStore`'s established
 * JSON-persistence pattern: an atomic staging-file write (a crash mid-write
 * must never leave a truncated `seen-patches.json` behind), an in-memory
 * cache (avoids re-parsing the whole file on every lookup — this is called
 * once per bundle on every patch-selection load), and a schema version field
 * for future migrations. Was previously a direct, non-atomic `writeText`
 * with no cache on every call.
 *
 * Keyed by `sourceId` — a patch source's stable id, not its (renameable)
 * display name — supplied by the caller from `BundlePatches.sourceId`.
 *
 * Reads the pre-hardening bare-map format (no version envelope, and keyed by
 * source *name*) transparently: [load] falls back to it when the new
 * enveloped format fails to parse. The next [save] rewrites the file in the
 * new format — no separate one-time migration step needed for a file this
 * small and already-lossy on rename (a bare-name-keyed entry for a
 * since-renamed source was already unreachable before this change, same as
 * every other name-keyed store this session has fixed).
 */
class SeenPatchesRepository(
    private val file: File = File(MorpheData.root, "seen-patches.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    // Immutable snapshot, replaced only after a successful write.
    private var cache: Map<String, Map<String, List<String>>>? = null

    private fun load(): Map<String, Map<String, List<String>>> {
        cache?.let { return it }
        val loaded = AtomicFiles.readState(
            file = file,
            empty = emptyMap<String, Map<String, List<String>>>(),
            onQuarantined = { preserved, cause ->
                Logger.warn(
                    "Could not read seen-patches.json (${cause.message}); " +
                        "preserved it as ${preserved.name} and starting empty"
                )
            },
        ) { text -> decode(text) }
        cache = loaded
        return loaded
    }

    /** Reads the current envelope, or the pre-hardening bare map; throws for anything else so the caller sets the file aside. */
    private fun decode(text: String): Map<String, Map<String, List<String>>> {
        val enveloped = runCatching { json.decodeFromString<StoreFile>(text) }.getOrNull()
        if (enveloped != null) {
            check(enveloped.version <= SCHEMA_VERSION) { "unsupported schema version ${enveloped.version}" }
            return enveloped.packages
        }
        // Pre-hardening bare format: no "version"/"packages" envelope.
        return json.decodeFromString<Map<String, Map<String, List<String>>>>(text)
    }

    suspend fun get(packageName: String, sourceId: String): Set<String>? =
        withContext(Dispatchers.IO) {
            mutex.withLock { load()[packageName]?.get(sourceId)?.toSet() }
        }

    /**
     * Records which patches of [sourceId] were present for [packageName].
     *
     * @throws java.io.IOException if it could not be written; nothing changed in memory or on disk.
     */
    suspend fun save(packageName: String, sourceId: String, patchNames: Set<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = load()
                val updated = all + (packageName to ((all[packageName] ?: emptyMap()) + (sourceId to patchNames.sorted())))
                AtomicFiles.write(file, json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, updated)))
                cache = updated
            }
        }

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        // No default: a pre-hardening bare-map file has no "packages" key at
        // all, so decoding it as StoreFile must fail outright (missing
        // required field) and fall through to the bare-format parser in
        // [load] — a default here would let decodeFromString "succeed" on an
        // old file by silently defaulting packages to empty, discarding every
        // existing seen-patches record instead of reading it.
        val packages: Map<String, Map<String, List<String>>>,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
