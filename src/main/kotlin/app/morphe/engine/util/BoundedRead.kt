/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** An archive entry (or stream) produced more bytes than the reader was prepared to hold. */
class EntryTooLargeException(val limit: Int) : IOException("entry exceeds the $limit-byte limit")

/**
 * Reads this stream fully, but never holds more than [limit] bytes: the **actual decompressed**
 * byte count is what is capped, so a ZIP whose header understates an entry's size (a zip bomb)
 * cannot get past a check on the declared size.
 *
 * @throws EntryTooLargeException as soon as more than [limit] bytes have been produced; nothing
 *   beyond [limit] + one buffer is ever allocated.
 */
@Throws(IOException::class)
fun InputStream.readBytesBounded(limit: Int): ByteArray {
    require(limit >= 0) { "limit must not be negative" }
    val buffer = ByteArray(minOf(DEFAULT_BUFFER, limit.coerceAtLeast(1)))
    val out = ByteArrayOutputStream(minOf(DEFAULT_BUFFER, limit.coerceAtLeast(1)))
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw EntryTooLargeException(limit)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DEFAULT_BUFFER = 64 * 1024
