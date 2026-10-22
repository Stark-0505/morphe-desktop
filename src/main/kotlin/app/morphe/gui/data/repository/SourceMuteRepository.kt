/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.MorpheData
import app.morphe.engine.util.Logger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which sources each app is kept from being patched by.
 *
 * A source switched off in [PatchSourceManager] is off for everything; this narrows one app
 * instead. Someone who patches one app from one source and another app from a second source
 * stops being asked which to use for the first — its selection screen simply doesn't offer
 * the muted source's patches. Patches added to the source they turned down never join that
 * app's selection behind their back.
 *
 * Apps are keyed by package name, not by a particular patched build: a repatch of the same
 * app is offered the same sources its earlier patches were.
 *
 * Ported from `app.morphe.manager.domain.repository.SourceMuteRepository`. Bundle identity
 * uses Desktop's stable [app.morphe.gui.data.model.PatchSource.id] string rather than
 * Manager's Room-assigned integer UID — Desktop already has a stable per-source id for
 * exactly this purpose (see [PatchSourceManager]), so there's no reason to introduce a
 * second identity scheme just to mirror Manager's Android storage layer.
 *
 * Persistence follows the same atomic-JSON-file pattern as [SeenPatchesRepository] and
 * [app.morphe.engine.PatchedAppStore]: writes go through [AtomicFiles] (crash mid-write
 * never leaves a truncated file), reads are cached in memory, and the file carries a
 * schema version for future migrations.
 */
class SourceMuteRepository(
    private val file: File = File(MorpheData.root, "source-mutes.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** packageName -> muted source ids. Packages that rule out nothing are absent. */
    // Immutable snapshot; replaced only after a successful write (see commit).
    private var cache: Map<String, Set<String>>? = null

    // Bumped on every mutation so Compose call sites can key off it the same way
    // PatchSourceManager.sourceVersion drives reloads on source list changes.
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        val mutes: Map<String, Set<String>> = emptyMap(),
    )

    private fun load(): Map<String, Set<String>> {
        cache?.let { return it }
        val loaded = AtomicFiles.readState(
            file = file,
            empty = emptyMap<String, Set<String>>(),
            onQuarantined = { preserved, cause ->
                Logger.error(
                    "Failed to read source-mutes.json (${cause.message}); " +
                        "preserved it as ${preserved.name} and starting empty",
                    cause,
                )
            },
        ) { text ->
            val stored = json.decodeFromString<StoreFile>(text)
            // A layout from a newer version is set aside rather than half-understood and rewritten.
            check(stored.version <= SCHEMA_VERSION) { "unsupported schema version ${stored.version}" }
            stored.mutes
        }
        cache = loaded
        return loaded
    }

    /** Persists [updated], then makes it the in-memory state and signals observers. Throws if it can't be written. */
    private fun commit(updated: Map<String, Set<String>>) {
        AtomicFiles.write(file, json.encodeToString(StoreFile(mutes = updated)))
        cache = updated
        _version.value++
    }

    suspend fun getMutedFor(packageName: String): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { load()[packageName] ?: emptySet() }
    }

    /** @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun mute(packageName: String, sourceId: String) = change(packageName) { it + sourceId }

    /** @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun unmute(packageName: String, sourceId: String) = change(packageName) { it - sourceId }

    /** Drops [sourceId] from every package's muted set.
     *  @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun resetForSource(sourceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            if (all.values.none { sourceId in it }) return@withLock
            commit(
                all.mapValues { (_, sources) -> sources - sourceId }
                    .filterValues { it.isNotEmpty() },
            )
        }
    }

    /** @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock { commit(emptyMap()) }
    }

    /** Read-modify-write of one package's muted set under a single lock, so concurrent
     *  mutes of different sources cannot overwrite each other. */
    private suspend fun change(packageName: String, transform: (Set<String>) -> Set<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = load()
                val current = all[packageName] ?: emptySet()
                val next = transform(current)
                if (next == current) return@withLock
                commit(if (next.isEmpty()) all - packageName else all + (packageName to next))
            }
        }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
