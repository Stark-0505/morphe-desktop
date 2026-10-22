/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [ArchiveFiles]: content-addressed placement and content-verified equality. */
class ArchiveFilesTest {

    private fun dir(): File = createTempDirectory("archive-files-test").toFile()
    private fun file(d: File, name: String, text: String) = File(d, name).apply { writeText(text) }
    private fun place(src: File, archive: File, pkg: String = "com.example", version: String = "1.0") =
        ArchiveFiles.place(src, archive, pkg, version, "apk")

    @Test
    fun `the archive name states the content`() {
        val a = ArchiveFiles.contentFileName("com.example", "1.0", "ab".repeat(32), "apk")
        assertEquals("com.example_1.0_abababababababab_original.apk", a)
        assertNotEquals(a, ArchiveFiles.contentFileName("com.example", "1.0", "cd".repeat(32), "apk"))
    }

    @Test
    fun `placing copies the exact bytes and leaves the source alone`() {
        val d = dir(); val archive = File(d, "archive")
        val src = file(d, "in.apk", "original bytes")
        val placed = place(src, archive)
        assertTrue(placed.created)
        assertEquals("original bytes", placed.file.readText())
        assertEquals(FileChecksum.sha256(src), placed.sha256)
        assertEquals(src.length(), placed.size)
        assertTrue(src.exists(), "the source must never be touched by placing")
        assertEquals(listOf(placed.file.name), archive.list()!!.toList(), "no staging file may remain")
    }

    @Test
    fun `same size but different bytes land in different files and the first survives`() {
        val d = dir(); val archive = File(d, "archive")
        val first = place(file(d, "a.apk", "AAAAAAAA"), archive)
        val second = place(file(d, "b.apk", "BBBBBBBB"), archive)   // same package, same version, same length
        assertNotEquals(first.file.name, second.file.name)
        assertEquals("AAAAAAAA", first.file.readText(), "an earlier original must never be overwritten")
        assertEquals("BBBBBBBB", second.file.readText())
        assertEquals(2, archive.list()!!.size)
    }

    @Test
    fun `placing identical content twice reuses the existing archive`() {
        val d = dir(); val archive = File(d, "archive")
        val first = place(file(d, "a.apk", "same"), archive)
        val again = place(file(d, "b.apk", "same"), archive)
        assertTrue(first.created)
        assertFalse(again.created)
        assertEquals(first.file, again.file)
        assertEquals(1, archive.list()!!.size)
    }

    @Test
    fun `a prefix collision falls back to the full hash name without touching the squatter`() {
        val d = dir(); val archive = File(d, "archive").apply { mkdirs() }
        val src = file(d, "in.apk", "payload")
        val sha = FileChecksum.sha256(src)
        // A different file already occupies the short content name.
        val squatter = File(archive, ArchiveFiles.contentFileName("com.example", "1.0", sha, "apk")).apply { writeText("someone else") }
        val placed = place(src, archive)
        assertEquals("someone else", squatter.readText())
        assertEquals("payload", placed.file.readText())
        assertTrue(placed.file.name.contains(sha), "the long name carries the full hash")
    }

    @Test
    fun `a corrupt file at the full hash name is repaired with the verified copy`() {
        val d = dir(); val archive = File(d, "archive").apply { mkdirs() }
        val src = file(d, "in.apk", "payload")
        val sha = FileChecksum.sha256(src)
        File(archive, ArchiveFiles.contentFileName("com.example", "1.0", sha, "apk")).writeText("squatter")
        File(archive, ArchiveFiles.contentFileName("com.example", "1.0", sha, "apk", 64)).writeText("corrupt")
        val placed = place(src, archive)
        assertEquals("payload", placed.file.readText())
        assertTrue(placed.created)
    }

    @Test
    fun `a missing source fails cleanly leaving the archive directory empty`() {
        val d = dir(); val archive = File(d, "archive")
        assertFailsWith<IOException> { place(File(d, "ghost.apk"), archive) }
        assertEquals(emptyList(), archive.list()!!.toList(), "a failed placement must leave no staging file")
    }

    @Test
    fun `sameContent is false for equal size but different bytes`() {
        val d = dir()
        assertFalse(ArchiveFiles.sameContent(file(d, "a", "aaaa"), file(d, "b", "bbbb")))
    }

    @Test
    fun `sameContent is true for identical bytes and honours an expected hash`() {
        val d = dir()
        val a = file(d, "a", "same"); val b = file(d, "b", "same")
        val sha = FileChecksum.sha256(a)
        assertTrue(ArchiveFiles.sameContent(a, b))
        assertTrue(ArchiveFiles.sameContent(a, b, sha))
        assertFalse(ArchiveFiles.sameContent(a, b, "0".repeat(64)), "a wrong expected hash must veto the match")
    }

    @Test
    fun `sameContent is false when either file is missing`() {
        val d = dir()
        assertFalse(ArchiveFiles.sameContent(file(d, "a", "x"), File(d, "missing")))
        assertFalse(ArchiveFiles.sameContent(File(d, "missing"), file(d, "b", "x")))
    }

    @Test
    fun `matches verifies against a stated hash`() {
        val d = dir(); val a = file(d, "a", "data")
        assertTrue(ArchiveFiles.matches(a, FileChecksum.sha256(a)))
        assertFalse(ArchiveFiles.matches(a, "f".repeat(64)))
        assertFalse(ArchiveFiles.matches(File(d, "gone"), FileChecksum.sha256(a)))
    }
}
