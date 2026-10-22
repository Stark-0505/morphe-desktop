/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Covers [PatchHistoryRecorder], the single post-patch step shared by the CLI, Expert mode and
 * Quick mode. The properties that matter most are about the user's file: it is only ever
 * removed after the archived copy is confirmed AND the record is durable, and a caller that
 * supplies no [OriginalApkRepository] (the CLI) never has its input touched.
 *
 * Plain text files stand in for APKs: [app.morphe.engine.apk.ApkInspector] returns null for
 * anything that isn't a readable archive, which exercises the version/package fallbacks.
 */
class PatchHistoryRecorderTest {

    private fun tempDir(): File = createTempDirectory().toFile()

    private fun fakeApk(dir: File, name: String, content: String = "fake apk bytes"): File =
        File(dir, name).apply { writeText(content) }

    private fun newStore(): PatchedAppStore = PatchedAppStore(File(tempDir(), "patched-apps.json"))

    private fun newArchive(retentionEnabled: Boolean = true) = OriginalApkRepository(
        file = File(tempDir(), "original-apks.json"),
        archiveDir = tempDir(),
        isRetentionEnabled = { retentionEnabled },
    )

    private fun record(
        store: PatchedAppStore,
        input: File,
        output: File,
        metadata: PatchEngine.HistoryMetadata? = null,
        packageName: String = "com.example.app",
        displayName: String? = null,
    ) = runBlocking {
        PatchHistoryRecorder.record(
            packageName = packageName,
            packageVersion = "9.9.9",
            displayName = displayName,
            inputApk = input,
            outputApk = output,
            metadata = metadata,
            store = store,
        )
    }

    @Test
    fun `without an archive repository the record points at the users file and nothing is deleted`() {
        val store = newStore()
        val dir = tempDir()
        val input = fakeApk(dir, "input.apk")
        val output = fakeApk(dir, "output.apk", "patched bytes")

        val result = record(store, input, output)

        val rec = result.getOrThrow()
        assertEquals(input.absolutePath, rec.inputApkPath)
        assertEquals(output.absolutePath, rec.outputApkPath)
        assertEquals(
            app.morphe.engine.util.FileChecksum.sha256(input), rec.inputApkSha256,
            "the record must vouch for the exact bytes it was patched from, so a repatch can verify them",
        )
        assertTrue(input.exists(), "the CLI case must never remove the input")
        assertEquals("com.example.app", rec.packageName)
        assertEquals("com.example.app", rec.id)
        assertEquals("com.example.app", rec.displayName)
        assertEquals("9.9.9", rec.apkVersion)
        assertEquals(output.length(), rec.outputApkSize)
        assertNotNull(rec.outputApkSha256)
        assertEquals(1, runBlocking { store.getAll() }.size)
    }

    @Test
    fun `with an archive repository the original is archived the record references it and the users copy is removed`() {
        val store = newStore()
        val archive = newArchive()
        val dir = tempDir()
        val input = fakeApk(dir, "input.apk", "original bytes")
        val output = fakeApk(dir, "output.apk")

        val rec = record(
            store, input, output,
            metadata = PatchEngine.HistoryMetadata(originalApkRepository = archive),
        ).getOrThrow()

        val archived = runBlocking { archive.get("com.example.app") }
        assertNotNull(archived)
        assertEquals(archived!!.filePath, rec.inputApkPath, "a repatch must read the managed copy")
        assertEquals(archived.sha256, rec.inputApkSha256, "the record carries the archive's verified hash")
        assertEquals("original bytes", File(rec.inputApkPath).readText())
        assertFalse(input.exists(), "the user's copy is redundant once the archive and record are durable")
    }

    @Test
    fun `when retention is disabled the user keeps their file and the record points at it`() {
        val store = newStore()
        val archive = newArchive(retentionEnabled = false)
        val dir = tempDir()
        val input = fakeApk(dir, "input.apk")
        val output = fakeApk(dir, "output.apk")

        val rec = record(
            store, input, output,
            metadata = PatchEngine.HistoryMetadata(originalApkRepository = archive),
        ).getOrThrow()

        assertEquals(input.absolutePath, rec.inputApkPath)
        assertTrue(input.exists())
        assertNull(runBlocking { archive.get("com.example.app") })
    }

    @Test
    fun `a record that cannot be persisted leaves the users original in place`() {
        // A store whose parent "directory" is a regular file can never be written.
        val blocker = fakeApk(tempDir(), "not-a-directory")
        val brokenStore = PatchedAppStore(File(blocker, "patched-apps.json"))
        val archive = newArchive()
        val dir = tempDir()
        val input = fakeApk(dir, "input.apk")
        val output = fakeApk(dir, "output.apk")

        val result = record(
            brokenStore, input, output,
            metadata = PatchEngine.HistoryMetadata(originalApkRepository = archive),
        )

        assertTrue(result.isFailure, "a failed write must surface as a failure, not a silent success")
        assertTrue(input.exists(), "nothing may be deleted when the record did not land")
    }

    @Test
    fun `the original package identity from the metadata is recorded`() {
        val store = newStore()
        val dir = tempDir()

        val rec = record(
            store, fakeApk(dir, "input.apk"), fakeApk(dir, "output.apk"),
            metadata = PatchEngine.HistoryMetadata(
                originalPackageName = "com.original.app",
                displayName = "Original App",
                appIconColorHex = "#FF8800",
            ),
            packageName = "com.renamed.app",
        ).getOrThrow()

        assertEquals("com.original.app", rec.packageName, "the pre-rename package is the app's identity")
        assertEquals("Original App", rec.displayName)
        assertEquals("#FF8800", rec.appIconColorHex)
    }

    @Test
    fun `an explicit display name wins over the metadata and blank values are ignored`() {
        val store = newStore()
        val dir = tempDir()

        val rec = record(
            store, fakeApk(dir, "input.apk"), fakeApk(dir, "output.apk"),
            metadata = PatchEngine.HistoryMetadata(displayName = "From Metadata", appIconColorHex = "  "),
            displayName = "Explicit",
        ).getOrThrow()

        assertEquals("Explicit", rec.displayName)
        assertNull(rec.appIconColorHex, "a blank color means none, not an empty-string color")
    }

    @Test
    fun `a result with no package name is refused and nothing is stored`() {
        val store = newStore()
        val dir = tempDir()

        val result = record(
            store, fakeApk(dir, "input.apk"), fakeApk(dir, "output.apk"),
            packageName = "  ",
        )

        assertTrue(result.isFailure)
        assertTrue(runBlocking { store.getAll() }.isEmpty())
    }

    @Test
    fun `recording the same app twice keeps a single record for it`() {
        val store = newStore()
        val dir = tempDir()
        val input = fakeApk(dir, "input.apk")

        record(store, input, fakeApk(dir, "out1.apk")).getOrThrow()
        record(store, input, fakeApk(dir, "out2.apk")).getOrThrow()

        val all = runBlocking { store.getAll() }
        assertEquals(1, all.size)
        assertTrue(all.single().outputApkPath.endsWith("out2.apk"), "the newest build replaces the older record")
    }
}
