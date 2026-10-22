/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.patches

import app.morphe.engine.util.FileStamp
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The crash-loop guard: a bundle that repeatedly kills the process is held back, while everything
 * else (ordinary load errors, a still-running other process, a replaced file, odd ids, damaged
 * state files) must never produce a false strike or be allowed to break loading.
 */
class PatchBundleLoadGuardTest {

    companion object {
        /** A guard whose ledger already says [sourceId]'s exact [file] crashed the app twice. */
        internal fun guardHoldingBack(root: File, sourceId: String, file: File): PatchBundleLoadGuard {
            val dir = File(root, "held-${System.nanoTime()}").apply { mkdirs() }
            val stamp = FileStamp.of(file)?.toString() ?: "unknown"
            File(dir, "bundle-load-failures.json").writeText(
                """{"version":1,"strikes":{"$sourceId":{"count":2,"stamp":"$stamp"}}}""",
            )
            return PatchBundleLoadGuard(dir)
        }

        /** A marker as left by a process that no longer exists. */
        internal fun writeDeadMarker(dir: File, sourceId: String, file: File, pid: Long = Long.MAX_VALUE - 7) {
            val stamp = FileStamp.of(file)?.toString() ?: "unknown"
            File(dir, LoadMarker.fileName(sourceId)).writeText(
                LoadMarker.encode(LoadMarker.Marker(sourceId, stamp, pid, processStartMs = null)),
            )
        }
    }

    private val root = createTempDirectory("guard-test").toFile()
    private fun stateDir() = File(root, "state-${System.nanoTime()}").apply { mkdirs() }
    private fun jar(text: String = "bundle bytes") = File(root, "b-${System.nanoTime()}.mpp").apply { writeText(text) }
    private fun markers(dir: File) = dir.listFiles().orEmpty().filter { it.name.startsWith(LoadMarker.PREFIX) }

    @Test
    fun `a normal load runs and leaves no marker or ledger behind`() {
        val dir = stateDir()
        val result = PatchBundleLoadGuard(dir).read("src", jar()) { "loaded" }
        assertEquals("loaded", result)
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `the marker exists while loading and its name does not contain the source id`() {
        val dir = stateDir()
        val id = "../../evil/id"
        var during: List<String> = emptyList()
        PatchBundleLoadGuard(dir).read(id, jar()) { during = markers(dir).map { it.name }; 1 }
        assertEquals(1, during.size)
        assertFalse(during.single().contains("evil") || during.single().contains(".."), during.single())
        assertEquals(emptyList(), markers(dir), "removed again once the load finished")
    }

    @Test
    fun `an ordinary load failure passes through, is not a strike, and leaves no marker`() {
        val dir = stateDir(); val file = jar()
        val guard = PatchBundleLoadGuard(dir)
        assertFailsWith<IllegalStateException> { guard.read("src", file) { throw IllegalStateException("bad bundle") } }
        assertEquals(emptyList(), markers(dir))
        assertEquals("ok", guard.read("src", file) { "ok" }, "an ordinary failure must not hold the bundle back")
    }

    @Test
    fun `one interrupted load is not yet a verdict`() {
        val dir = stateDir(); val file = jar()
        writeDeadMarker(dir, "src", file)
        assertEquals("ran", PatchBundleLoadGuard(dir).read("src", file) { "ran" })
    }

    @Test
    fun `two interrupted loads of the same unchanged file hold it back without loading it`() {
        val dir = stateDir(); val file = jar()
        writeDeadMarker(dir, "src", file)
        // First restart: strike one, and this load also fails without clearing it.
        assertFailsWith<IllegalStateException> { PatchBundleLoadGuard(dir).read("src", file) { throw IllegalStateException("x") } }
        writeDeadMarker(dir, "src", file)   // ...and the process died again during the next attempt
        var loaded = false
        assertFailsWith<PatchBundleHeldBackException> { PatchBundleLoadGuard(dir).read("src", file) { loaded = true } }
        assertFalse(loaded, "a held-back bundle must not be loaded again")
    }

    @Test
    fun `a replaced bundle starts with a clean record`() {
        val dir = stateDir(); val file = jar("old bytes")
        val guard = PatchBundleLoadGuard(dir)
        // file is held back ...
        val held = guardHoldingBack(root, "src", file)
        assertFailsWith<PatchBundleHeldBackException> { held.read("src", file) { } }
        // ... until its content changes
        file.writeText("new, longer bytes after an update")
        file.setLastModified(file.lastModified() + 10_000)
        assertEquals("fresh", held.read("src", file) { "fresh" })
        assertEquals("fine", guard.read("src", file) { "fine" })
    }

    @Test
    fun `a success clears an earlier strike`() {
        val dir = stateDir(); val file = jar()
        writeDeadMarker(dir, "src", file)
        PatchBundleLoadGuard(dir).read("src", file) { "ok" }
        assertFalse(File(dir, "bundle-load-failures.json").exists(), "the ledger entry must be gone after a good load")
    }

    @Test
    fun `a marker from a process that is still running is not counted as a crash`() {
        val dir = stateDir(); val file = jar()
        val (pid, start) = LoadMarker.currentProcess()
        val stamp = FileStamp.of(file)?.toString() ?: "unknown"
        File(dir, LoadMarker.fileName("src")).writeText(LoadMarker.encode(LoadMarker.Marker("src", stamp, pid, start)))

        PatchBundleLoadGuard(dir).read("other", jar()) { "x" }

        assertTrue(File(dir, LoadMarker.fileName("src")).exists(), "another live reader's marker must be left alone")
        assertFalse(File(dir, "bundle-load-failures.json").exists(), "and must not create a strike")
    }

    @Test
    fun `a staging file from an interrupted marker write is deleted and not counted`() {
        val dir = stateDir()
        File(dir, LoadMarker.fileName("src") + ".5120.tmp").writeText("half written")
        PatchBundleLoadGuard(dir).read("src", jar()) { "x" }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a malformed marker is discarded without creating a strike`() {
        val dir = stateDir()
        File(dir, LoadMarker.fileName("src")).writeText("garbage that is not a marker")
        PatchBundleLoadGuard(dir).read("src", jar()) { "x" }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun `a legacy marker from before hashed names still counts as an interrupted load`() {
        val dir = stateDir(); val file = jar()
        val stamp = FileStamp.of(file)?.toString() ?: "unknown"
        File(dir, "reading-legacy-src").writeText(stamp)

        // The strike is recorded (the ledger appears) and the old-format marker is consumed.
        assertFailsWith<IllegalStateException> {
            PatchBundleLoadGuard(dir).read("legacy-src", file) { throw IllegalStateException("x") }
        }
        assertFalse(File(dir, "reading-legacy-src").exists())
        assertTrue(File(dir, "bundle-load-failures.json").exists(), "the legacy interruption must be remembered")
    }

    @Test
    fun `a corrupt ledger is preserved and the guard still works`() {
        val dir = stateDir()
        File(dir, "bundle-load-failures.json").writeText("{ not json")
        assertEquals("ok", PatchBundleLoadGuard(dir).read("src", jar()) { "ok" })
        val backups = dir.listFiles().orEmpty().filter { ".corrupt-" in it.name }
        assertEquals(1, backups.size)
        assertEquals("{ not json", backups.single().readText())
    }

    @Test
    fun `concurrent reads of one source share a marker that outlives the first to finish`() {
        val dir = stateDir(); val file = jar()
        val guard = PatchBundleLoadGuard(dir)
        var markersWhileOuterRuns = -1
        guard.read("src", file) {
            guard.read("src", file) { "inner" }
            markersWhileOuterRuns = markers(dir).size
            "outer"
        }
        assertEquals(1, markersWhileOuterRuns, "finishing the inner read must not remove the outer read's marker")
        assertEquals(emptyList(), markers(dir))
    }

    @Test
    fun `forget removes what is recorded about a source`() {
        val dir = stateDir(); val file = jar()
        val held = guardHoldingBack(root, "src", file)
        held.forget("src")
        assertEquals("loaded", held.read("src", file) { "loaded" })
    }
}
