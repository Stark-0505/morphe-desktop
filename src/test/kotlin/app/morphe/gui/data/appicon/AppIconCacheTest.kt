/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppIconCacheTest {

    private var now = 1_000_000L
    private val dir: File = createTempDirectory("app-icon-cache-test").toFile()

    private fun cache() = AppIconCache(dir) { now }

    @Test
    fun `file names are a digest of the package id, never the id itself`() {
        val cache = cache()
        val name = cache.iconFile("com.example.app").name

        assertTrue(Regex("[0-9a-f]{64}\\.img").matches(name), name)
        assertNotEquals(cache.iconFile("com.example.app"), cache.iconFile("com.example.other"))
        assertEquals(cache.iconFile("com.example.app"), cache.iconFile("com.example.app"))
        // A hostile id cannot steer the path anywhere: only the digest reaches the filesystem.
        assertEquals(dir, cache.iconFile("../../etc/passwd").parentFile)
    }

    @Test
    fun `a committed icon replaces the previous one and clears the failure`() {
        val cache = cache()
        cache.recordFailure("com.example.app", IconFailure.TRANSIENT)
        cache.downloadFile("com.example.app").writeBytes(byteArrayOf(1, 2, 3))

        cache.commit("com.example.app", cache.downloadFile("com.example.app"))

        assertContentEquals(byteArrayOf(1, 2, 3), cache.readIcon("com.example.app"))
        assertNull(cache.activeFailure("com.example.app"))
        assertTrue(!cache.downloadFile("com.example.app").exists(), "the scratch file is consumed by the move")
    }

    @Test
    fun `a missing icon reads as null`() {
        assertNull(cache().readIcon("com.example.absent"))
    }

    @Test
    fun `a failure is active during its cooldown and expires after it`() {
        val cache = cache()
        cache.recordFailure("com.example.app", IconFailure.NO_LISTING)

        now += IconFailure.NO_LISTING.baseCooldownMs - 1
        assertEquals(IconFailure.NO_LISTING, cache.activeFailure("com.example.app"))

        now += 2
        assertNull(cache.activeFailure("com.example.app"))
    }

    @Test
    fun `the cooldown survives a restart`() {
        cache().recordFailure("com.example.app", IconFailure.NO_LISTING)

        now += 60_000
        val restarted = cache()

        assertEquals(IconFailure.NO_LISTING, restarted.activeFailure("com.example.app"))
        assertNull(restarted.activeFailure("com.example.untried"))
    }

    @Test
    fun `repeated transient failures back off, up to the cap`() {
        val cache = cache()
        val base = IconFailure.TRANSIENT.baseCooldownMs

        cache.recordFailure("com.example.app", IconFailure.TRANSIENT)
        now += base + 1
        assertNull(cache.activeFailure("com.example.app"))

        cache.recordFailure("com.example.app", IconFailure.TRANSIENT) // second consecutive: 2x
        now += base + 1
        assertEquals(IconFailure.TRANSIENT, cache.activeFailure("com.example.app"))
        now += base
        assertNull(cache.activeFailure("com.example.app"))

        repeat(20) { cache.recordFailure("com.example.app", IconFailure.TRANSIENT) }
        now += IconFailure.TRANSIENT.maxCooldownMs + 1
        assertNull(cache.activeFailure("com.example.app"), "the backoff never exceeds its cap")
    }

    @Test
    fun `Retry-After extends the cooldown`() {
        val cache = cache()
        cache.recordFailure("com.example.app", IconFailure.TRANSIENT, retryAfterMs = 2 * 60 * 60_000L)

        now += IconFailure.TRANSIENT.baseCooldownMs + 1
        assertEquals(IconFailure.TRANSIENT, cache.activeFailure("com.example.app"))
        now += 2 * 60 * 60_000L
        assertNull(cache.activeFailure("com.example.app"))
    }

    @Test
    fun `a clock that moved backwards does not lock a package out`() {
        val cache = cache()
        cache.recordFailure("com.example.app", IconFailure.NO_LISTING)
        now -= 10 * 24 * 60 * 60_000L

        assertNull(cache.activeFailure("com.example.app"))
    }

    @Test
    fun `a corrupt index is ignored instead of failing`() {
        File(dir, "failures.json").writeText("{ this is not json")

        val cache = cache()
        assertNull(cache.activeFailure("com.example.app"))

        cache.recordFailure("com.example.app", IconFailure.NO_ICON)
        assertEquals(IconFailure.NO_ICON, cache().activeFailure("com.example.app"), "the next write repairs the file")
    }

    @Test
    fun `clearing a failure is persisted`() {
        val cache = cache()
        cache.recordFailure("com.example.app", IconFailure.BLOCKED)
        cache.clearFailure("com.example.app")

        assertNull(cache().activeFailure("com.example.app"))
    }
}
