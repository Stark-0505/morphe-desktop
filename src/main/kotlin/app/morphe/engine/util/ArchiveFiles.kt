/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-level primitives behind the original-APK archive, kept free of any state, JSON or
 * logging so their guarantees can be tested directly.
 *
 * The central rule: **an archive file's name states its content** (a prefix of its SHA-256).
 * [place] therefore never overwrites a file with different bytes. A different APK that happens
 * to share a package and version name lands under a different name, so a history record that
 * points at the first one can never silently start resolving to the second, and a failure after
 * placement can never destroy a previously archived original.
 */
object ArchiveFiles {

    /** A file that holds exactly [sha256] / [size], at [file]. [created] is false when an
     *  identical copy was already there and nothing was written. */
    class Placed(val file: File, val sha256: String, val size: Long, val created: Boolean)

    /** Suffix of a copy still being written; never mistaken for an archive. */
    const val STAGING_SUFFIX = ".part"

    private val FILE_ID_LENGTHS = intArrayOf(16, 64)

    /** `<package>_<version>_<sha>_original.<ext>`; [idLength] hex chars of [sha256] identify content. */
    fun contentFileName(
        packageName: String,
        version: String,
        sha256: String,
        extension: String,
        idLength: Int = FILE_ID_LENGTHS.first(),
    ): String {
        val safePackage = FilenameUtils.sanitize(packageName).ifEmpty { "unknown" }
        val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" }).ifEmpty { "unspecified" }
        return "${safePackage}_${safeVersion}_${sha256.take(idLength)}_original.$extension"
    }

    /**
     * Copies [source] into [archiveDir] under a content-addressed name and returns where it
     * landed. The source is only read. The copy is written in full, hashed in the same pass and
     * flushed to a staging file first, so the archive directory never holds a partial copy under
     * a final name.
     *
     * - Nothing is ever overwritten with different bytes. If the name is taken by a file whose
     *   content differs (a prefix collision), the full-length hash name is used instead.
     * - If an identical copy already exists, it is reused and nothing is written.
     * - Only at the full-length name, where the name itself asserts the content, is a file that
     *   fails to match treated as corrupt and replaced by the verified copy.
     *
     * Throws [IOException] on any failure; the staging file is removed and the archive directory
     * is as it was.
     */
    @Throws(IOException::class)
    fun place(
        source: File,
        archiveDir: File,
        packageName: String,
        version: String,
        extension: String,
    ): Placed {
        Files.createDirectories(archiveDir.toPath())
        val staging = Files.createTempFile(archiveDir.toPath(), "incoming-", STAGING_SUFFIX).toFile()
        try {
            val (sha, size) = FileChecksum.copyWithSha256(source, staging)
            for ((index, idLength) in FILE_ID_LENGTHS.withIndex()) {
                val target = File(archiveDir, contentFileName(packageName, version, sha, extension, idLength))
                val lastResort = index == FILE_ID_LENGTHS.lastIndex
                if (!target.exists()) {
                    try {
                        Files.move(staging.toPath(), target.toPath())
                        return Placed(target, sha, size, created = true)
                    } catch (_: FileAlreadyExistsException) {
                        // Someone placed it between the check and the move; verify it below.
                    }
                }
                if (target.isFile && target.length() == size && FileChecksum.sha256(target) == sha) {
                    return Placed(target, sha, size, created = false)
                }
                if (lastResort) {
                    // The full-hash name says what the bytes must be; these are not. Repair it.
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    return Placed(target, sha, size, created = true)
                }
            }
            throw IOException("unreachable: no archive name was selected for ${source.name}")
        } finally {
            staging.delete()   // no-op once it has been moved into place
        }
    }

    /**
     * True only when both files exist and hold identical bytes, established by hashing **both**
     * now. Equal size, or a stored hash, is deliberately not enough: this decides whether a
     * user's file may be deleted. If [expectedSha256] is given the shared hash must equal it,
     * which also catches an archive that was corrupted after it was verified.
     */
    fun sameContent(a: File, b: File, expectedSha256: String? = null): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        val shaA = FileChecksum.sha256(a)
        if (expectedSha256 != null && shaA != expectedSha256) return false
        return shaA == FileChecksum.sha256(b)
    }

    /** True when [file] exists and hashes to [expectedSha256]. */
    fun matches(file: File, expectedSha256: String): Boolean =
        file.isFile && FileChecksum.sha256(file) == expectedSha256
}
