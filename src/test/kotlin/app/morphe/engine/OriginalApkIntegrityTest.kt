/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.FileChecksum
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data-preservation invariants of [OriginalApkRepository]:
 * - a user's input is never deleted unless the archive is proven identical, byte for byte;
 * - two different APKs that share a package and version name never share (or overwrite) a file;
 * - a failed index write never destroys an earlier archive or leaves an orphan;
 * - a history record can never silently resolve to a different APK than the one it was patched from.
 */
class OriginalApkIntegrityTest {

    private fun dir(): File = createTempDirectory("orig-apk-test").toFile()
    private fun apk(d: File, name: String, text: String) = File(d, name).apply { writeText(text) }

    private class Fixture(
        val archiveDir: File,
        val indexFile: File,
        val referenced: MutableSet<String> = mutableSetOf(),
        var retention: Boolean = true,
    ) {
        val repo get() = make()
        fun make() = OriginalApkRepository(
            file = indexFile,
            archiveDir = archiveDir,
            isRetentionEnabled = { retention },
            referencedInputPaths = { referenced.toSet() },
        )
    }

    private fun fixture(): Fixture {
        val root = dir()
        return Fixture(File(root, "archive"), File(root, "original-apks.json"))
    }

    private fun record(
        pkg: String = "com.example.app",
        version: String = "1.0",
        inputPath: String,
        sha: String? = null,
    ) = PatchedAppRecord(
        id = pkg, packageName = pkg, displayName = "Example", apkVersion = version,
        inputApkPath = inputPath, inputApkSha256 = sha, outputApkPath = "/out.apk",
        patchedAt = 1L, patchedWithMorpheVersion = "test",
    )

    // ---- discardSource: verified, never size-only ----

    @Test
    fun `discardSource removes the users file when it is byte-identical to the archive`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val src = apk(dir(), "in.apk", "original bytes")
        val archived = repo.saveOriginalApk("com.example.app", "1.0", src)!!
        repo.discardSource(src, archived)
        assertFalse(src.exists())
        assertEquals("original bytes", archived.readText())
    }

    @Test
    fun `discardSource keeps a file of the same size but different bytes`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val archived = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "a.apk", "AAAAAAAAAA"))!!
        val impostor = apk(dir(), "b.apk", "BBBBBBBBBB")   // identical length, different content
        assertEquals(archived.length(), impostor.length())

        repo.discardSource(impostor, archived)

        assertTrue(impostor.exists(), "equal size alone must never be enough to delete a user's file")
    }

    @Test
    fun `discardSource keeps the file when the archive was altered after it was verified`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val src = apk(dir(), "in.apk", "original bytes")
        val archived = repo.saveOriginalApk("com.example.app", "1.0", src)!!
        val bytes = archived.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()   // flip one byte: identical length, different content
        archived.writeBytes(bytes)
        assertEquals(src.length(), archived.length())

        repo.discardSource(src, archived)

        assertTrue(src.exists(), "a corrupted archive must not be treated as a safe backup")
    }

    @Test
    fun `discardSource keeps the file when the archive is not one the repository registered`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val src = apk(dir(), "in.apk", "bytes")
        val stranger = apk(dir(), "elsewhere.apk", "bytes")   // identical, but never archived
        repo.discardSource(src, stranger)
        assertTrue(src.exists())
    }

    @Test
    fun `discardSource never touches a registered archive`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val archived = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "in.apk", "bytes"))!!
        repo.discardSource(archived, archived)
        assertTrue(archived.exists())
    }

    // ---- same package + version, different bytes ----

    @Test
    fun `different APKs with the same package and version get separate files`() = runBlocking {
        val fx = fixture()
        val repo = fx.make()
        val first = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "a.apk", "AAAAAAAA"))!!
        fx.referenced += first.absolutePath      // a history record points at the first one
        val second = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "b.apk", "BBBBBBBB"))!!

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertEquals("AAAAAAAA", first.readText(), "the first original must be byte-for-byte unchanged")
        assertEquals("BBBBBBBB", second.readText())
    }

    @Test
    fun `a record with a hash resolves only to the bytes it was patched from`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val srcA = apk(dir(), "a.apk", "AAAAAAAA")
        val archivedA = repo.saveOriginalApk("com.example.app", "1.0", srcA)!!
        val shaA = FileChecksum.sha256(archivedA)
        val rec = record(inputPath = archivedA.absolutePath, sha = shaA)
        fx.referenced += archivedA.absolutePath

        repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "b.apk", "BBBBBBBB"))   // same pkg+version

        val resolved = repo.resolveInputApk(rec)
        assertNotNull(resolved)
        assertEquals("AAAAAAAA", resolved.readText(), "must not drift to the other build with the same version name")
    }

    @Test
    fun `a record whose file was replaced by different bytes resolves to nothing rather than the wrong APK`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val user = apk(dir(), "mine.apk", "ORIGINAL")
        val rec = record(inputPath = user.absolutePath, sha = FileChecksum.sha256(user))
        user.writeText("SOMETHING-ELSE")   // user saved a different APK over the same path

        assertNull(repo.resolveInputApk(rec), "identity cannot be confirmed, so no file may be returned")
    }

    @Test
    fun `a record finds its archive by hash when the recorded path is gone`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val src = apk(dir(), "in.apk", "payload")
        val archived = repo.saveOriginalApk("com.example.app", "1.0", src)!!
        val rec = record(inputPath = File(dir(), "moved-away.apk").absolutePath, sha = FileChecksum.sha256(archived))

        assertEquals(archived.absolutePath, repo.resolveInputApk(rec)?.absolutePath)
    }

    @Test
    fun `a truncated archive is not returned for a record that has a hash`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val archived = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "in.apk", "full content here"))!!
        val rec = record(inputPath = archived.absolutePath, sha = FileChecksum.sha256(archived))
        archived.writeText("full con")   // truncated

        assertNull(repo.resolveInputApk(rec))
    }

    // ---- updates, clones and older history ----

    @Test
    fun `an older record keeps its original after a newer version is patched`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val v1 = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "v1.apk", "v1 bytes"))!!
        val recV1 = record(version = "1.0", inputPath = v1.absolutePath, sha = FileChecksum.sha256(v1))
        fx.referenced += v1.absolutePath

        val v2 = repo.saveOriginalApk("com.example.app", "2.0", apk(dir(), "v2.apk", "v2 bytes!"))!!

        assertTrue(v1.exists(), "an archive a history record references must survive a later save")
        assertEquals("v1 bytes", repo.resolveInputApk(recV1)?.readText())
        assertEquals("v2 bytes!", v2.readText())
    }

    @Test
    fun `an unreferenced superseded archive is pruned and a referenced one is kept`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val v1 = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "v1.apk", "v1"))!!
        val v2 = repo.saveOriginalApk("com.example.app", "2.0", apk(dir(), "v2.apk", "v22"))!!
        assertFalse(v1.exists(), "nothing references v1, so it is reclaimed")
        fx.referenced += v2.absolutePath
        repo.saveOriginalApk("com.example.app", "3.0", apk(dir(), "v3.apk", "v333"))
        assertTrue(v2.exists(), "v2 is referenced by a record, so it stays")
    }

    @Test
    fun `clones of one package keep separate originals`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val appOwn = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "own.apk", "own build"))!!
        fx.referenced += appOwn.absolutePath
        val cloneSource = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "clone.apk", "other build"))!!
        fx.referenced += cloneSource.absolutePath

        assertEquals("own build", appOwn.readText())
        assertEquals("other build", cloneSource.readText())
        assertEquals(2, repo.getAll().size)
    }

    // ---- failure safety ----

    @Test
    fun `a failed index write leaves no orphan archive and the source untouched`() {
        val root = dir()
        val blocker = File(root, "blocker").apply { writeText("x") }   // index lives "inside" a file
        val archiveDir = File(root, "archive")
        val repo = OriginalApkRepository(File(blocker, "original-apks.json"), archiveDir)
        val src = apk(dir(), "in.apk", "payload")

        assertFailsWith<IOException> { runBlocking { repo.saveOriginalApk("com.example.app", "1.0", src) } }

        assertTrue(src.exists())
        assertEquals(0, archiveDir.listFiles().orEmpty().size, "a failed save must not strand a copy")
    }

    @Test
    fun `a failed index write after an earlier archive does not damage that archive`() {
        val fx = fixture(); val repo = fx.make()
        val first = runBlocking { repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "a.apk", "AAAAAAAA")) }!!
        fx.referenced += first.absolutePath
        // Break persistence: the index path now holds a non-empty directory, so the final move fails.
        fx.indexFile.delete()
        fx.indexFile.mkdirs(); File(fx.indexFile, "keep").writeText("x")

        val clash = apk(dir(), "b.apk", "BBBBBBBB")   // same package, same version, same size
        assertFailsWith<IOException> { runBlocking { repo.saveOriginalApk("com.example.app", "1.0", clash) } }

        assertEquals("AAAAAAAA", first.readText(), "the previously archived original must be unchanged")
        assertTrue(clash.exists())
        assertEquals(1, fx.archiveDir.listFiles().orEmpty().count { it.isFile }, "only the first archive remains")
    }

    @Test
    fun `retention disabled archives nothing and the record keeps pointing at the users file`() = runBlocking {
        val fx = fixture(); fx.retention = false
        val repo = fx.make()
        val user = apk(dir(), "mine.apk", "bytes")
        assertNull(repo.saveOriginalApk("com.example.app", "1.0", user))
        val rec = record(inputPath = user.absolutePath, sha = FileChecksum.sha256(user))
        assertEquals(user.absolutePath, repo.resolveInputApk(rec)?.absolutePath)
        assertTrue(user.exists())
    }

    // ---- persistence, restart and legacy data ----

    @Test
    fun `archives and their records survive a restart`() = runBlocking {
        val fx = fixture()
        val archived = fx.make().saveOriginalApk("com.example.app", "1.0", apk(dir(), "in.apk", "payload"))!!

        val reloaded = fx.make()   // a fresh instance reading the same files

        assertEquals(archived.absolutePath, reloaded.get("com.example.app")?.filePath)
        assertEquals(1, reloaded.getAll().size)
    }

    @Test
    fun `an unreadable index is preserved and not overwritten by the next save`() = runBlocking {
        val fx = fixture()
        fx.indexFile.parentFile.mkdirs()
        fx.indexFile.writeText("{ this is not json")

        val repo = fx.make()
        assertTrue(repo.getAll().isEmpty())
        repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "in.apk", "payload"))

        val backups = fx.indexFile.parentFile.listFiles().orEmpty().filter { ".corrupt-" in it.name }
        assertEquals(1, backups.size)
        assertEquals("{ this is not json", backups.single().readText())
    }

    @Test
    fun `an index written by a newer schema is set aside instead of being read as empty and rewritten`() = runBlocking {
        val fx = fixture()
        fx.indexFile.parentFile.mkdirs()
        val future = """{"version": 99, "apks": [], "newThing": true}"""
        fx.indexFile.writeText(future)

        fx.make().getAll()

        val backups = fx.indexFile.parentFile.listFiles().orEmpty().filter { ".corrupt-" in it.name }
        assertEquals(future, backups.single().readText(), "data this version doesn't understand must be preserved")
    }

    @Test
    fun `a schema 1 index with one row per package loads and still resolves a legacy record`() = runBlocking {
        val fx = fixture()
        fx.archiveDir.mkdirs()
        val legacyFile = File(fx.archiveDir, "com.example.app_1.0_original.apk").apply { writeText("legacy bytes") }
        fx.indexFile.writeText(
            """{"version":1,"apks":[{"packageName":"com.example.app","version":"1.0",""" +
                """"filePath":${quote(legacyFile.absolutePath)},"fileSize":${legacyFile.length()},"lastUsed":1}]}""",
        )
        val repo = fx.make()

        assertEquals(1, repo.getAll().size)
        val legacyRecord = record(inputPath = File(dir(), "gone.apk").absolutePath)   // no input hash: pre-dates it
        assertEquals(legacyFile.absolutePath, repo.resolveInputApk(legacyRecord)?.absolutePath)
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\") + "\""

    @Test
    fun `pruneMissingApks drops a record whose archive file vanished`() = runBlocking {
        val fx = fixture(); val repo = fx.make()
        val archived = repo.saveOriginalApk("com.example.app", "1.0", apk(dir(), "in.apk", "payload"))!!
        archived.delete()
        repo.pruneMissingApks()
        assertNull(repo.get("com.example.app"))
    }
}
