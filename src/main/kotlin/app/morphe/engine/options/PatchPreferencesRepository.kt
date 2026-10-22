/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.options

import app.morphe.engine.MorpheData
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Persists patch selections and option values per source and package across sessions. */
class PatchPreferencesRepository(
    private val file: File = File(MorpheData.root, FILE_NAME),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    // Immutable snapshots: a mutation builds a new map, persists it, and only then replaces this,
    // so a failed write can never leave memory ahead of disk.
    private var cache: Map<String, Map<String, PatchBundle>>? = null

    private fun load(): Map<String, Map<String, PatchBundle>> {
        cache?.let { return it }
        val loaded = AtomicFiles.readState(
            file = file,
            empty = emptyMap<String, Map<String, PatchBundle>>(),
            onQuarantined = { preserved, cause ->
                Logger.warn(
                    "Could not read patch preferences (${cause.message}); " +
                        "preserved the file as ${preserved.name} and starting empty"
                )
            },
        ) { text -> json.decodeFromString<Map<String, Map<String, PatchBundle>>>(text) }
        cache = loaded
        return loaded
    }

    /**
     * Full snapshot: sourceId -> packageName -> saved selection. Used to build cross-bundle
     * "copy selection from another app/source" candidates — the one place this repository
     * needs to be enumerated rather than looked up by key. The result is immutable and a
     * point-in-time view: later saves do not change a snapshot already handed out.
     */
    suspend fun allSelections(): Map<String, Map<String, PatchBundle>> = withContext(Dispatchers.IO) {
        mutex.withLock { load() }
    }

    suspend fun get(sourceName: String, packageName: String): PatchBundle? = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()[sourceName]?.get(packageName)
        }
    }

    suspend fun has(sourceName: String, packageName: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()[sourceName]?.containsKey(packageName) == true
        }
    }

    /**
     * Saves the selection for ([sourceName], [packageName]).
     *
     * @throws java.io.IOException if it could not be written. Nothing changed in that case, neither
     *   on disk nor in memory, so the caller can report it and the old selection is still the saved one.
     */
    suspend fun save(
        sourceName: String,
        packageName: String,
        enabledPatchNames: Set<String>,
        disabledPatchNames: Set<String>,
        options: Map<String, Map<String, JsonElement>> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val existing = all[sourceName]?.get(packageName)

            val patches = mutableMapOf<String, PatchEntry>()
            for (name in enabledPatchNames) {
                patches[name] = PatchEntry(
                    enabled = true,
                    options = options[name] ?: emptyMap(),
                )
            }
            for (name in disabledPatchNames) {
                patches[name] = PatchEntry(
                    enabled = false,
                    options = options[name] ?: emptyMap(),
                )
            }

            val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val bundle = PatchBundle(
                meta = PatchBundleMeta(
                    createdAt = existing?.meta?.createdAt ?: now,
                    updatedAt = if (existing != null) now else null,
                    source = sourceName,
                    sha256 = null,
                ),
                patches = patches,
            )
            commit(all + (sourceName to ((all[sourceName] ?: emptyMap()) + (packageName to bundle))))
            Logger.info("Saved patch preferences for $sourceName / $packageName (${patches.size} entries)")
        }
    }

    /** Clears [packageName]'s saved selection across every source — used when an app's
     *  choices should start clean regardless of which bundle they came from.
     *  @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun resetForApp(packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            if (all.values.none { packageName in it }) return@withLock
            commit(
                all.mapValues { (_, byPkg) -> byPkg - packageName }
                    .filterValues { it.isNotEmpty() },
            )
            Logger.info("Reset patch preferences for $packageName across all sources")
        }
    }

    /** Clears every saved selection for [sourceId] (every app) — used when a source is
     *  removed entirely, so a future, unrelated source that happens to reuse the id doesn't
     *  inherit stale choices.
     *  @throws java.io.IOException if the change could not be written (nothing changed). */
    suspend fun resetForSource(sourceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            if (sourceId !in all) return@withLock
            commit(all - sourceId)
            Logger.info("Reset patch preferences for source $sourceId")
        }
    }

    /** Persists [updated] and only then makes it the in-memory state. Throws if it can't be written. */
    private fun commit(updated: Map<String, Map<String, PatchBundle>>) {
        AtomicFiles.write(file, json.encodeToString(updated))
        cache = updated
    }

    companion object {
        const val FILE_NAME = "patch-preferences.json"
    }
}
