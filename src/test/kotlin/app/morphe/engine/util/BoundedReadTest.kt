/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedReadTest {

    @Test
    fun `content within the limit is returned intact`() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        assertContentEquals(data, ByteArrayInputStream(data).readBytesBounded(10_000))
    }

    @Test
    fun `exactly the limit is allowed and one byte more is not`() {
        ByteArrayInputStream(ByteArray(100)).readBytesBounded(100)
        assertFailsWith<EntryTooLargeException> { ByteArrayInputStream(ByteArray(101)).readBytesBounded(100) }
    }

    @Test
    fun `an empty stream is an empty array`() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readBytesBounded(10).size)
    }

    @Test
    fun `a stream that never ends is stopped without being fully read`() {
        var produced = 0L
        val endless = object : InputStream() {
            override fun read(): Int { produced++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { produced += len; return len }
        }
        assertFailsWith<EntryTooLargeException> { endless.readBytesBounded(1_000_000) }
        assert(produced < 2_000_000) { "read $produced bytes from an unbounded stream" }
    }

    @Test
    fun `a zip bomb is stopped by the real decompressed size not the header`() {
        val zipFile = createTempFile("bomb", ".zip").toFile()
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            val zeros = ByteArray(1024 * 1024)
            repeat(64) { zos.write(zeros) }   // 64 MiB of zeros, a few hundred KiB on disk
            zos.closeEntry()
        }
        assert(zipFile.length() < 1_000_000) { "fixture should be tiny, was ${zipFile.length()}" }
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            assertFailsWith<EntryTooLargeException> { zip.getInputStream(entry).use { it.readBytesBounded(1024 * 1024) } }
        }
        zipFile.delete()
    }

    @Test
    fun `a negative limit is rejected`() {
        assertFailsWith<IllegalArgumentException> { ByteArrayInputStream(ByteArray(1)).readBytesBounded(-1) }
    }
}
