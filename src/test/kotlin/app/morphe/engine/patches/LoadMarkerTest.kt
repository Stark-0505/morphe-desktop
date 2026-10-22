/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.patches

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadMarkerTest {

    @Test
    fun `file names never contain the source id so hostile ids cannot escape the directory`() {
        for (id in listOf("../../etc/passwd", "a/b\\c", "x".repeat(5_000), "id with \n newline", "ünï:cödé*?\"<>|")) {
            val name = LoadMarker.fileName(id)
            assertTrue(name.length < 64, "name must be short, was ${name.length}")
            assertFalse(name.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }, name)
            assertFalse(name.contains(".."))
            assertTrue(LoadMarker.isMarkerFile(name))
        }
    }

    @Test
    fun `different ids get different names and the same id is stable`() {
        assertNotEquals(LoadMarker.fileName("a"), LoadMarker.fileName("b"))
        assertEquals(LoadMarker.fileName("same"), LoadMarker.fileName("same"))
    }

    @Test
    fun `a marker round-trips including an awkward source id`() {
        val m = LoadMarker.Marker("weird/../id\nwith newline ✓", "123-456", pid = 4242L, processStartMs = 1_700_000_000_000L)
        assertEquals(m, LoadMarker.decode(LoadMarker.encode(m)))
    }

    @Test
    fun `a marker without a known process start time round-trips`() {
        val m = LoadMarker.Marker("src", "unknown", pid = 7L, processStartMs = null)
        assertEquals(m, LoadMarker.decode(LoadMarker.encode(m)))
    }

    @Test
    fun `malformed or legacy content is not a marker`() {
        assertNull(LoadMarker.decode(""))
        assertNull(LoadMarker.decode("123-456"))                       // legacy: just a stamp
        assertNull(LoadMarker.decode("v2\n1\n\nstamp\nYQ"))            // unknown version
        assertNull(LoadMarker.decode("v1\nnot-a-pid\n\nstamp\nYQ"))
        assertNull(LoadMarker.decode("v1\n1\nnot-a-time\nstamp\nYQ"))
        assertNull(LoadMarker.decode("v1\n1\n\nstamp\n!!!not-base64!!!"))
        assertNull(LoadMarker.decode("v1\n1\n\nstamp\n"))              // blank source id
    }

    @Test
    fun `staging files are recognised and are not markers`() {
        val staging = LoadMarker.fileName("src") + ".83745.tmp"
        assertTrue(LoadMarker.isStagingFile(staging))
        assertFalse(LoadMarker.isMarkerFile(staging))
        assertFalse(LoadMarker.isStagingFile(LoadMarker.fileName("src")))
    }

    @Test
    fun `the current process is seen as alive and a dead pid is not`() {
        val (pid, start) = LoadMarker.currentProcess()
        assertTrue(LoadMarker.writerIsAlive(LoadMarker.Marker("s", "x", pid, start)))
        assertTrue(LoadMarker.writerIsAlive(LoadMarker.Marker("s", "x", pid, null)))
        assertFalse(LoadMarker.writerIsAlive(LoadMarker.Marker("s", "x", Long.MAX_VALUE - 1, null)))
    }

    @Test
    fun `a recycled pid with a different start time is not the writer`() {
        val (pid, start) = LoadMarker.currentProcess()
        if (start == null) return   // platform cannot report a start time: nothing to distinguish
        assertFalse(LoadMarker.writerIsAlive(LoadMarker.Marker("s", "x", pid, start - 3_600_000L)))
    }
}
