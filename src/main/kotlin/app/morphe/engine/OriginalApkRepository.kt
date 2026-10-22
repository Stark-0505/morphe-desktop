/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.apk.BundleFormats
import app.morphe.engine.util.ArchiveFiles
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.FileChecksum
import app.morphe.engine.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One retained original APK record. */
@Serializable
data class OriginalApk(
    val packageName: String,
    val version: String,
    val filePath: String,
    val fileSize: Long,
    val sha256: String? = null,
    val lastUsed: Long,
)

/**
 * Owns the pre-patch (original) APK of each app: after a successful patch the
 * file the user picked is archived under [archiveDir], and that managed copy
 * becomes the canonical input for every later repatch/update, so the user's own
 * copy (a Downloads folder that gets cleaned, a temp dir) is no longer needed.
 *
 * Lifecycle, in the order callers must follow:
 * 1. [saveOriginalApk] copies the source through a staging file, verifies it,
 *    and durably registers it. The source is left untouched.
 * 2. The caller persists whatever references the archive (the patched-app record).
 * 3. [discardSource] removes the user's copy. Only now is it redundant, and only if its
 *    bytes are re-verified against the archive at that moment.
 *
 * If anything before step 3 fails, the user's file is still where it was.
 * [resolveInputApk] is the single place that decides which original a repatch reads.
 *
 * **Identity.** An archive's file name states its content (see [ArchiveFiles]), so a different
 * APK that shares a package and version name is archived *next to* the earlier one, never over
 * it. Records reference an archive by path *and* by SHA-256
 * ([PatchedAppRecord.inputApkSha256]); [resolveInputApk] only returns a file whose hash matches,
 * and returns null — never a different file — when none does.
 *
 * **Retention.** After a save, other archives of the same package are removed only if no
 * history record still references them ([referencedInputPaths]); a v1 record must keep its v1
 * original even after v2 is patched.
 *
 * **Persistence.** Rows (one per archive file) live in one JSON file, written atomically; the
 * cache only advances once a write is on disk. Persistence failures throw [IOException]; nothing
 * here reports success for something that isn't on disk. Index files written by earlier versions
 * (schema 1, one row per package) load unchanged. An unreadable index is moved aside, never
 * overwritten, and if it cannot be moved aside loading fails instead of starting empty.
 */
class OriginalApkRepository(
    private val file: File = File(MorpheData.root, "original-apks.json"),
    private val archiveDir: File = MorpheData.originalApksDir,
    private val isRetentionEnabled: suspend () -> Boolean = { true },
    /** Paths of archives that history records still point at; these are never pruned. */
    private val referencedInputPaths: suspend () -> Set<String> = { emptySet() },
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()
    private var cache: List<OriginalApk>? = null

    // replay = 1 so a collector that subscribes after a save still sees it.
    private val _changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** Emits whenever a record is saved, its use is marked, pruned, or deleted —
     *  and to a new collector immediately if that happened before it subscribed. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    suspend fun getAll(): List<OriginalApk> = withContext(Dispatchers.IO) {
        mutex.withLock { load() }
    }

    /** The most recently used archive of [packageName], or null. */
    suspend fun get(packageName: String): OriginalApk? = withContext(Dispatchers.IO) {
        mutex.withLock { load().filter { it.packageName == packageName }.maxByOrNull { it.lastUsed } }
    }

    /** The row for exactly this archive [file], if it is one of ours. */
    suspend fun recordFor(file: File): OriginalApk? = withContext(Dispatchers.IO) {
        val canonical = file.canonicalFileOrNull() ?: return@withContext null
        mutex.withLock { load().firstOrNull { it.canonicalOrNull() == canonical } }
    }

    /**
     * Archives [sourceFile] as the original APK for ([packageName], [version]) and
     * returns the managed file. Returns null — "nothing was archived, and that is fine" — when
     * retention is disabled (see [isRetentionEnabled]) or [sourceFile] is not a file.
     * A non-null result guarantees the file at that path holds exactly [sourceFile]'s
     * bytes (verified, flushed to disk) and is registered durably.
     *
     * [sourceFile] is never modified or deleted here; see [discardSource].
     *
     * The same bytes already archived are reused (only [OriginalApk.lastUsed] is refreshed);
     * different bytes, even under the same package, version and size, get their own file.
     * Re-saving the managed copy itself, which is what repatching from it does, is a refresh.
     *
     * @throws IOException (or another exception) if archiving or persisting failed.
     *   The source is untouched, no previously archived file has been changed, and a fresh archive
     *   that could not be registered is removed again.
     */
    suspend fun saveOriginalApk(
        packageName: String,
        version: String,
        sourceFile: File,
    ): File? = withContext(Dispatchers.IO) {
        if (!isRetentionEnabled()) {
            Logger.debug("Original APK retention disabled, skipping save for $packageName")
            return@withContext null
        }
        if (!sourceFile.isFile) {
            Logger.warn("Source for $packageName is not a file, skipping original APK save")
            return@withContext null
        }
        mutex.withLock { saveLocked(packageName, version, sourceFile) }
    }

    private suspend fun saveLocked(packageName: String, version: String, source: File): File {
        val rows = load()
        // canonicalFile resolves symlinks / relative-vs-absolute spelling, so "same file via
        // a different path string" is recognized instead of causing a self-copy.
        val sourceCanonical = source.canonicalFile

        // Repatching from the managed copy itself: nothing to copy or re-hash.
        rows.firstOrNull { it.canonicalOrNull() == sourceCanonical && it.fileSize == source.length() }
            ?.let { return touch(rows, it) }

        // The exact same APK is already archived for this package and version: hash the source
        // once and compare, instead of copying it again.
        val sameSize = rows.filter {
            it.packageName == packageName && it.version == version && it.fileSize == source.length() &&
                File(it.filePath).isFile
        }
        if (sameSize.isNotEmpty()) {
            val sourceSha = FileChecksum.sha256(source)
            sameSize.firstOrNull { (it.sha256 ?: FileChecksum.sha256(File(it.filePath))) == sourceSha }
                ?.let { return touch(rows, it) }
        }

        val extension = if (BundleFormats.isBundle(source)) source.extension.lowercase() else "apk"
        val placed = ArchiveFiles.place(source, archiveDir, packageName, version, extension)
        val row = OriginalApk(
            packageName = packageName,
            version = version,
            filePath = placed.file.absolutePath,
            fileSize = placed.size,
            sha256 = placed.sha256,
            lastUsed = System.currentTimeMillis(),
        )
        val registered = rows.filterNot { it.canonicalOrNull() == placed.file.canonicalFile } + row
        try {
            persist(registered)
        } catch (e: Exception) {
            // Not registered, so don't leave an orphan behind. Placement never overwrites, so a
            // file we just created holds content nothing else can reference.
            if (placed.created) placed.file.delete()
            throw e
        }

        pruneSuperseded(packageName, keep = placed.file, spare = sourceCanonical)

        _changes.tryEmit(Unit)
        Logger.info("Saved original APK for $packageName v$version")
        return placed.file
    }

    /** Refreshes [OriginalApk.lastUsed] on an unchanged archive and returns its file. */
    private fun touch(rows: List<OriginalApk>, record: OriginalApk): File {
        persist(rows.map { if (it == record) it.copy(lastUsed = System.currentTimeMillis()) else it })
        _changes.tryEmit(Unit)
        return File(record.filePath)
    }

    /**
     * Removes other archives of [packageName] that nothing references any more: not [keep], not
     * [spare] (the caller's own source), and not a path some history record still points at. The
     * index is rewritten first and files are deleted afterwards, so a failed write leaves files
     * and index agreeing.
     */
    private suspend fun pruneSuperseded(packageName: String, keep: File, spare: File?) {
        val rows = load()
        val referenced = referencedCanonicalPaths()
        val keepCanonical = keep.canonicalFile
        val doomed = rows.filter {
            it.packageName == packageName &&
                it.canonicalOrNull() != keepCanonical &&
                it.canonicalOrNull() != spare &&
                it.canonicalOrNull() !in referenced
        }
        if (doomed.isEmpty()) return
        try {
            persist(rows - doomed.toSet())
        } catch (e: Exception) {
            Logger.warn("Kept superseded original APKs for $packageName: could not update the index (${e.message})")
            return
        }
        doomed.forEach { if (File(it.filePath).delete()) Logger.debug("Deleted superseded original APK ${it.filePath}") }
    }

    private suspend fun referencedCanonicalPaths(): Set<File> =
        referencedInputPaths().mapNotNull { File(it).canonicalFileOrNull() }.toSet()

    /**
     * Removes the user's own [source] file, after [saveOriginalApk] returned [archived] for it
     * AND everything that references [archived] has been persisted. This is the "move" half of
     * archive-and-move; until it runs, the source is untouched.
     *
     * The archive must be a registered one, and **both files are hashed again right now**: the
     * source is only removed if its bytes equal the archive's and match the recorded hash. Equal
     * size is not evidence. If that cannot be established, the source stays.
     *
     * Best-effort and deliberately non-throwing: a source that can't be removed (locked,
     * read-only, already gone) is merely left where it is. Never touches the archive itself or
     * any registered archive.
     */
    suspend fun discardSource(source: File, archived: File): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!source.exists()) return@withLock
                val sourceCanonical = source.canonicalFile
                val rows = load()
                val isManaged = sourceCanonical == archived.canonicalFile ||
                    rows.any { it.canonicalOrNull() == sourceCanonical }
                if (isManaged) return@withLock
                val row = rows.firstOrNull { it.canonicalOrNull() == archived.canonicalFile }
                if (row == null || !ArchiveFiles.sameContent(archived, source, row.sha256)) {
                    Logger.warn("Keeping ${source.name}: the archived copy could not be verified against it")
                    return@withLock
                }
                Files.delete(source.toPath())
                Logger.info("Moved original APK ${source.name} into Morphe's managed storage")
            } catch (e: Exception) {
                Logger.warn("Could not remove ${source.name} after archiving it: ${e.message}")
            }
        }
    }

    /**
     * The original APK a repatch of [record] should read, or null when none is usable and the
     * user has to pick one. A repatch must never run on an APK that merely *looks* right.
     *
     * Records that carry [PatchedAppRecord.inputApkSha256] (everything written since it was
     * introduced): the record's own path is used if its bytes hash to that value; otherwise any
     * archive with that hash. If neither verifies, the answer is null. In particular a different
     * APK that was archived under the same package and version name is never substituted.
     *
     * Older records without a hash cannot be verified, so identity is the next best thing: the
     * archive for the record's package **and version** (whose own hash is checked against its
     * index row), else the record's recorded path if the file still exists.
     */
    suspend fun resolveInputApk(record: PatchedAppRecord): File? = withContext(Dispatchers.IO) {
        val rows = mutex.withLock { load() }
        val expected = record.inputApkSha256
        if (expected != null) {
            val recorded = File(record.inputApkPath)
            if (ArchiveFiles.matches(recorded, expected)) return@withContext recorded
            val byHash = rows.filter { it.sha256 == expected }.map { File(it.filePath) }
                .firstOrNull { ArchiveFiles.matches(it, expected) }
            if (byHash == null) Logger.warn("No APK matching the recorded hash for ${record.packageName} v${record.apkVersion}")
            return@withContext byHash
        }
        val archived = rows
            .filter { it.packageName == record.packageName && it.version == record.apkVersion }
            .maxByOrNull { it.lastUsed }
            ?.takeIf { row -> File(row.filePath).isFile && (row.sha256 == null || ArchiveFiles.matches(File(row.filePath), row.sha256)) }
            ?.let { File(it.filePath) }
        archived ?: File(record.inputApkPath).takeIf { it.isFile }
    }

    /**
     * Drops records whose archived file is gone (nothing left to describe,
     * and reporting a stale size or offering a no-op action would be worse
     * than removing the record), removes unreferenced superseded archives, and deletes any
     * `.part` staging file left behind by a save that never finished.
     *
     * @throws IOException if the pruned index could not be persisted.
     */
    suspend fun pruneMissingApks(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val remaining = all.filter { File(it.filePath).exists() }
            if (remaining.size != all.size) {
                persist(remaining)
                Logger.info("Pruned ${all.size - remaining.size} missing original-APK record(s)")
                _changes.tryEmit(Unit)
            }
            // Superseded archives that only a replaced record used to reference.
            for (pkg in remaining.map { it.packageName }.distinct()) {
                val newest = load().filter { it.packageName == pkg }.maxByOrNull { it.lastUsed } ?: continue
                pruneSuperseded(pkg, keep = File(newest.filePath), spare = null)
            }

            val stagedFiles = archiveDir.listFiles { f -> f.name.endsWith(STAGING_SUFFIX) }.orEmpty()
            for (staged in stagedFiles) {
                staged.delete()
                Logger.debug("Dropped staged original APK left by an interrupted save: ${staged.name}")
            }
        }
    }

    /**
     * Remove every archived original for [packageName]: the files and their records.
     * The records go first, so a failure to persist leaves files and records intact.
     *
     * @throws IOException if the index could not be persisted.
     */
    suspend fun delete(packageName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val existing = all.filter { it.packageName == packageName }
            if (existing.isEmpty()) return@withLock
            persist(all - existing.toSet())
            existing.forEach {
                if (!File(it.filePath).delete()) Logger.warn("Removed the record but could not delete ${it.filePath}")
            }
            _changes.tryEmit(Unit)
        }
    }

    private fun load(): List<OriginalApk> {
        cache?.let { return it }
        val loaded = AtomicFiles.readState(
            file = file,
            empty = emptyList(),
            onQuarantined = { preserved, cause ->
                Logger.warn(
                    "Could not read original-apks.json (${cause.message}); " +
                        "preserved it as ${preserved.name} and starting empty"
                )
            },
        ) { text ->
            val stored = json.decodeFromString(StoreFile.serializer(), text)
            // Never guess at a layout a newer version wrote: setting it aside beats dropping rows.
            check(stored.version <= SCHEMA_VERSION) { "unsupported schema version ${stored.version}" }
            stored.apks
        }
        cache = loaded
        return loaded
    }

    /** Writes [all] durably; the cache only advances once it is on disk. */
    private fun persist(all: List<OriginalApk>) {
        val content = json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, all))
        AtomicFiles.write(file, content)
        cache = all
    }

    private fun OriginalApk.canonicalOrNull(): File? = File(filePath).canonicalFileOrNull()

    private fun File.canonicalFileOrNull(): File? = try { canonicalFile } catch (_: IOException) { null }

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        val apks: List<OriginalApk> = emptyList(),
    )

    companion object {
        /** 2: one row per archive file (schema 1 had one per package; its rows load unchanged). */
        private const val SCHEMA_VERSION = 2

        /** Marks a copy still being written, so an interrupted save is never mistaken for an archive. */
        internal const val STAGING_SUFFIX = ArchiveFiles.STAGING_SUFFIX
    }
}
