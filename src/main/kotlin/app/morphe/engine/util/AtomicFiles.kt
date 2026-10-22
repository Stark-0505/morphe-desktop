/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable write + corrupt-file quarantine for the small JSON state files under
 * `morphe-data/` (`patched-apps.json`, `original-apks.json`, preferences, ...). Shared so
 * every store persists and recovers with the same guarantees instead of carrying its own copy.
 *
 * The recovery policy every state file follows:
 * 1. An unreadable or incompatible file is never treated as "empty" while it is still on disk
 *    where a later save could overwrite it. [readState] moves it aside first.
 * 2. If it cannot be moved aside, loading **fails** ([IOException]); the caller must not carry
 *    on with an empty state, because the next save would destroy whatever is recoverable.
 * 3. Writes go through [write], which is atomic, and a throw means "not persisted".
 */
object AtomicFiles {

    /**
     * Writes [content] to [target] so a crash never leaves a half-written file: the bytes are
     * flushed to a uniquely named sibling `.tmp` file first, then moved over [target]. The
     * temp name is unique per call, so two writers racing on the same target cannot clobber
     * each other's staging file.
     *
     * Throws [IOException] on any failure, leaving [target] as it was. Callers must treat
     * a throw as "not persisted".
     */
    @Throws(IOException::class)
    fun write(target: File, content: String) {
        val dir = target.absoluteFile.parentFile
        Files.createDirectories(dir.toPath())
        val tmp = Files.createTempFile(dir.toPath(), "${target.name}.", ".tmp").toFile()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: IOException) {
                // ATOMIC_MOVE isn't supported on every filesystem, so fall back.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Moves the finished file [source] onto [target], replacing it, **without ever removing
     * [target] first**: the destination is replaced in a single step or not at all. If the move
     * fails, [target] still holds its previous content and an [IOException] is thrown; [source] is
     * left for the caller to discard. This is what a download or export must use instead of
     * "delete the old file, then rename".
     */
    @Throws(IOException::class)
    fun replaceFile(source: File, target: File) {
        Files.createDirectories(target.absoluteFile.parentFile.toPath())
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            // ATOMIC_MOVE isn't supported everywhere (e.g. across file systems).
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Moves an unreadable state file aside (`<name>.corrupt-<timestamp>[-n]`) instead of
     * letting the next save silently overwrite it, so whatever is still recoverable in
     * it survives. The name is collision-safe: an existing backup is never replaced, and two
     * quarantines in the same millisecond get distinct names. Returns the preserved file.
     *
     * Throws [IOException] if it can't be preserved. The caller must not proceed to
     * overwrite it in that case.
     */
    @Throws(IOException::class)
    fun quarantine(file: File): File = quarantine(file, System::currentTimeMillis, MAX_QUARANTINE_ATTEMPTS)

    /** [quarantine] with an injectable clock and attempt limit, so collisions are testable. */
    @Throws(IOException::class)
    internal fun quarantine(file: File, clock: () -> Long, maxAttempts: Int): File {
        val dir = file.absoluteFile.parentFile
        val stamp = clock()
        var attempt = 0
        while (true) {
            val name = "${file.name}.corrupt-$stamp" + if (attempt == 0) "" else "-$attempt"
            val dest = File(dir, name)
            try {
                // No REPLACE_EXISTING: an earlier backup must never be overwritten.
                Files.move(file.toPath(), dest.toPath())
                return dest
            } catch (_: FileAlreadyExistsException) {
                if (++attempt > maxAttempts) {
                    throw IOException("Could not find a free quarantine name for ${file.name}")
                }
            }
        }
    }

    /**
     * Loads persisted state from [file] under the recovery policy above.
     *
     * - Missing file: returns [empty].
     * - Readable: returns what [decode] produces.
     * - [decode] (or reading) fails with anything but cancellation: the file is moved aside with
     *   [quarantine], [onQuarantined] is told where it went and why, and [empty] is returned.
     *   If the move fails, the [IOException] propagates and nothing is treated as loaded.
     *
     * [decode] should throw for a file it cannot fully understand (including a schema newer than
     * it supports) rather than return a partial result that would drop data on the next save.
     */
    @Throws(IOException::class)
    fun <T> readState(
        file: File,
        empty: T,
        onQuarantined: (preserved: File, cause: Exception) -> Unit = { _, _ -> },
        decode: (String) -> T,
    ): T = readState(file, empty, onQuarantined, ::quarantine, decode)

    /** [readState] with an injectable quarantine step, so its failure path is testable. */
    @Throws(IOException::class)
    internal fun <T> readState(
        file: File,
        empty: T,
        onQuarantined: (preserved: File, cause: Exception) -> Unit,
        quarantine: (File) -> File,
        decode: (String) -> T,
    ): T {
        if (!file.exists()) return empty
        return try {
            decode(file.readText())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val preserved = quarantine(file)   // throws if it cannot be preserved: never "empty" then
            onQuarantined(preserved, e)
            empty
        }
    }

    private const val MAX_QUARANTINE_ATTEMPTS = 1000
}
