/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared persistence/recovery primitives. These tests assert the observable invariants the
 * state files rely on: a failed write leaves the old file intact, an unreadable file is
 * preserved (never overwritten, never silently "empty"), and quarantine never clobbers a
 * backup.
 */
class AtomicFilesTest {

    private fun dir(): File = createTempDirectory("atomic-files-test").toFile()

    // ---- write ----

    @Test
    fun `write creates the file and leaves no temp file behind`() {
        val d = dir()
        val f = File(d, "state.json")
        AtomicFiles.write(f, """{"a":1}""")
        assertEquals("""{"a":1}""", f.readText())
        assertEquals(listOf("state.json"), d.list()!!.toList())
    }

    @Test
    fun `a failed write throws and leaves the previous content untouched`() {
        val d = dir()
        val f = File(d, "state.json").apply { writeText("old") }
        // Make the target's "directory" a regular file by writing to a child of a file.
        val blocker = File(d, "blocker").apply { writeText("x") }
        assertFailsWith<IOException> { AtomicFiles.write(File(blocker, "state.json"), "new") }
        assertEquals("old", f.readText())
        assertEquals("x", blocker.readText())
    }

    @Test
    fun `concurrent writers never produce a mixed or partial file`() {
        val d = dir()
        val f = File(d, "state.json")
        val contents = (1..8).map { n -> "writer-$n:" + "x".repeat(20_000 * n) }
        val start = CountDownLatch(1)
        val threads = contents.map { c ->
            Thread {
                start.await()
                repeat(30) { AtomicFiles.write(f, c) }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(f.readText() in contents, "final file must be exactly one writer's complete content")
        assertEquals(listOf("state.json"), d.list()!!.toList(), "no .tmp file may be left behind")
    }

    // ---- quarantine ----

    @Test
    fun `quarantine preserves the content and removes the original`() {
        val d = dir()
        val f = File(d, "state.json").apply { writeText("garbage") }
        val kept = AtomicFiles.quarantine(f)
        assertFalse(f.exists())
        assertEquals("garbage", kept.readText())
        assertTrue(kept.name.startsWith("state.json.corrupt-"))
    }

    @Test
    fun `repeated quarantines never overwrite an earlier backup`() {
        val d = dir()
        val f = File(d, "state.json")
        val kept = (1..25).map { n ->
            f.writeText("version-$n")
            AtomicFiles.quarantine(f)
        }
        assertEquals(25, kept.map { it.name }.toSet().size, "every backup needs its own name")
        assertEquals((1..25).map { "version-$it" }.toSet(), kept.map { it.readText() }.toSet())
    }

    @Test
    fun `quarantine with a frozen clock still finds a free name`() {
        val d = dir()
        val f = File(d, "state.json")
        File(d, "state.json.corrupt-5").writeText("older backup")
        f.writeText("new garbage")
        val kept = AtomicFiles.quarantine(f, { 5L }, maxAttempts = 10)
        assertEquals("state.json.corrupt-5-1", kept.name)
        assertEquals("older backup", File(d, "state.json.corrupt-5").readText())
    }

    @Test
    fun `quarantine gives up with an IOException when no name is free and keeps the file`() {
        val d = dir()
        val f = File(d, "state.json").apply { writeText("precious") }
        File(d, "state.json.corrupt-5").writeText("a")
        File(d, "state.json.corrupt-5-1").writeText("b")
        assertFailsWith<IOException> { AtomicFiles.quarantine(f, { 5L }, maxAttempts = 1) }
        assertEquals("precious", f.readText())
    }

    // ---- readState ----

    @Test
    fun `readState returns the empty value for a missing file`() {
        assertEquals("EMPTY", AtomicFiles.readState(File(dir(), "nope.json"), "EMPTY") { it })
    }

    @Test
    fun `readState decodes a good file and does not touch it`() {
        val f = File(dir(), "state.json").apply { writeText("42") }
        assertEquals(42, AtomicFiles.readState(f, -1) { it.trim().toInt() })
        assertEquals("42", f.readText())
    }

    @Test
    fun `readState quarantines an undecodable file and reports where it went`() {
        val d = dir()
        val f = File(d, "state.json").apply { writeText("{ not json") }
        var reported: File? = null
        val result = AtomicFiles.readState(f, -1, { preserved, _ -> reported = preserved }) { it.trim().toInt() }
        assertEquals(-1, result)
        assertFalse(f.exists(), "the bad file must be moved aside so a save cannot overwrite it")
        assertEquals("{ not json", reported!!.readText())
    }

    @Test
    fun `readState refuses to continue when the bad file cannot be preserved`() {
        val f = File(dir(), "state.json").apply { writeText("{ not json") }
        assertFailsWith<IOException> {
            AtomicFiles.readState(f, -1, { _, _ -> }, { throw IOException("cannot move") }) { it.trim().toInt() }
        }
        assertEquals("{ not json", f.readText(), "the unreadable file must remain exactly where it was")
    }

    @Test
    fun `readState lets cancellation through instead of quarantining`() {
        val f = File(dir(), "state.json").apply { writeText("data") }
        assertFailsWith<CancellationException> {
            AtomicFiles.readState(f, "EMPTY") { throw CancellationException("cancelled") }
        }
        assertTrue(f.exists(), "a cancelled read is not a corrupt file")
    }
}

class AtomicFilesReplaceTest {
    private fun dir(): File = createTempDirectory("replace-test").toFile()

    @Test
    fun `replaceFile swaps the content and removes the source`() {
        val d = dir()
        val target = File(d, "dest").apply { writeText("old") }
        val src = File(d, "dest.part").apply { writeText("new") }
        AtomicFiles.replaceFile(src, target)
        assertEquals("new", target.readText())
        assertFalse(src.exists())
    }

    @Test
    fun `replaceFile creates the target when none exists`() {
        val d = dir()
        val src = File(d, "a.part").apply { writeText("fresh") }
        val target = File(File(d, "sub/dir"), "dest")
        AtomicFiles.replaceFile(src, target)
        assertEquals("fresh", target.readText())
    }

    @Test
    fun `a failed replace leaves the previous destination intact`() {
        val d = dir()
        val target = File(d, "dest").apply { writeText("last valid file") }
        val missingSource = File(d, "never-written.part")
        assertFailsWith<IOException> { AtomicFiles.replaceFile(missingSource, target) }
        assertEquals("last valid file", target.readText(), "the old file must survive a failed replacement")
    }
}
