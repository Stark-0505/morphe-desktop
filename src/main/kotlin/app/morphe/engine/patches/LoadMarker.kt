/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.patches

import java.security.MessageDigest
import java.util.Base64

/**
 * The on-disk marker [PatchBundleLoadGuard] leaves while a bundle is being read, so a crash in the
 * middle of a read can be recognised the next time the app starts.
 *
 * Kept free of I/O and serialization so its rules are testable directly:
 * - The **file name is derived from a hash** of the source id, never the id itself, so an id
 *   containing `/`, `..`, control characters or a great length cannot escape the state directory or
 *   exceed a file-name limit. The real id travels inside the marker.
 * - A marker is recognised by the [SUFFIX]; staging files from an atomic write end in `.tmp` and are
 *   never mistaken for one.
 * - The marker records which process wrote it, so a marker belonging to a process that is *still
 *   running* (another Morphe window or the CLI sharing the data directory) is not read as a crash.
 */
internal object LoadMarker {
    const val PREFIX = "reading-"
    const val SUFFIX = ".marker"
    private const val VERSION = "v1"

    data class Marker(
        val sourceId: String,
        val stamp: String,
        val pid: Long,
        /** Epoch millis the writing process started, or null if the platform could not say. */
        val processStartMs: Long?,
    )

    fun fileName(sourceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }.take(32) + SUFFIX
    }

    fun isMarkerFile(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** A staging file left by an interrupted atomic write of a marker (never a committed marker). */
    fun isStagingFile(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(".tmp")

    fun currentProcess(): Pair<Long, Long?> {
        val handle = ProcessHandle.current()
        return handle.pid() to handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)
    }

    fun encode(marker: Marker): String = listOf(
        VERSION,
        marker.pid.toString(),
        marker.processStartMs?.toString() ?: "",
        marker.stamp,
        Base64.getUrlEncoder().withoutPadding().encodeToString(marker.sourceId.toByteArray(Charsets.UTF_8)),
    ).joinToString("\n")

    /** Null for anything that is not a well-formed current-format marker. */
    fun decode(text: String): Marker? {
        val lines = text.split("\n")
        if (lines.size != 5 || lines[0] != VERSION) return null
        val pid = lines[1].toLongOrNull() ?: return null
        val start = lines[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        val sourceId = try {
            String(Base64.getUrlDecoder().decode(lines[4]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (sourceId.isBlank()) return null
        return Marker(sourceId, lines[3], pid, start)
    }

    /**
     * Whether the process that wrote [marker] is still running. A recycled pid is told apart by the
     * start time. When the platform cannot report one, a live pid is trusted.
     */
    fun writerIsAlive(marker: Marker, lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) }): Boolean {
        val handle = lookup(marker.pid) ?: return false
        if (!handle.isAlive) return false
        val start = marker.processStartMs ?: return true
        val actual = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null) ?: return true
        // Start times are reported with second granularity on some platforms.
        return kotlin.math.abs(actual - start) < 2_000
    }
}
