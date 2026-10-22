/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs against the real [MorpheData] layout, which the Gradle test task redirects into the build
 * directory through the existing `MORPHE_DATA_DIR` override, so no real user data is involved.
 */
class CacheManagerTest {

    private fun File.fill(name: String, bytes: Int): File =
        File(this, name).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    /** Fails before any file is touched when the override is missing (e.g. a run outside Gradle). */
    private fun requireSandboxedDataRoot() = assertTrue(
        MorpheData.root.absolutePath.contains("test-morphe-data"),
        "refusing to run against a real data directory: ${MorpheData.root} (set MORPHE_DATA_DIR)",
    )

    @Test
    fun `the data root used by tests is the build-local override`() = requireSandboxedDataRoot()

    @Test
    fun `app icons are counted in the cache size`() {
        requireSandboxedDataRoot()
        val before = CacheManager.getCacheSizeBytes()
        val icon = MorpheData.appIconsDir.fill("size-accounting-test.img", 1234)

        try {
            assertTrue(CacheManager.getCacheSizeBytes() - before >= 1234)
        } finally {
            icon.delete()
        }
    }

    @Test
    fun `clearing removes app icons and the failure index but never Icon Studio content`() {
        requireSandboxedDataRoot()
        val cachedIcon = MorpheData.appIconsDir.fill("clear-test.img", 10)
        val failureIndex = MorpheData.appIconsDir.fill("failures.json", 10)
        val userIcon = MorpheData.iconsDir.fill("com.example.app/clear-test-user-icon.png", 10)

        try {
            val result = CacheManager.clearCaches()

            assertTrue(result.success)
            assertTrue(!cachedIcon.exists(), "the disposable icon cache is wiped")
            assertTrue(!failureIndex.exists(), "so is its failure index")
            assertTrue(MorpheData.appIconsDir.isDirectory, "the directory itself is kept")
            assertTrue(userIcon.exists(), "Icon Studio's user-authored output is never touched")
            assertTrue(result.perDirectory.any { it.label == "App icons" })
        } finally {
            userIcon.parentFile.deleteRecursively()
        }
    }
}
