/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.options

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
 * Covers the selection-management additions to [PatchPreferencesRepository]: the cross-source
 * snapshot used by "copy selection from another app/source", the per-app reset, the per-source
 * reset used when a source is removed, and that each of them is durable across a restart.
 */
class PatchPreferencesRepositoryTest {

    private fun newFile(): File = File(createTempDirectory().toFile(), "patch-preferences.json")

    private fun repo(file: File = newFile()) = PatchPreferencesRepository(file)

    private suspend fun PatchPreferencesRepository.seed(source: String, pkg: String, vararg enabled: String) =
        save(source, pkg, enabledPatchNames = enabled.toSet(), disabledPatchNames = emptySet())

    @Test
    fun `allSelections is empty before anything is saved`() = runBlocking {
        assertTrue(repo().allSelections().isEmpty())
    }

    @Test
    fun `allSelections returns every source and package that was saved`() = runBlocking {
        val repo = repo()
        repo.seed("source-a", "com.one", "Patch X")
        repo.seed("source-a", "com.two", "Patch Y")
        repo.seed("source-b", "com.one", "Patch Z")

        val all = repo.allSelections()

        assertEquals(setOf("source-a", "source-b"), all.keys)
        assertEquals(setOf("com.one", "com.two"), all.getValue("source-a").keys)
        assertEquals(setOf("com.one"), all.getValue("source-b").keys)
        assertTrue(all.getValue("source-a").getValue("com.one").patches.getValue("Patch X").enabled)
    }

    @Test
    fun `allSelections is a point-in-time snapshot unaffected by later changes`() = runBlocking {
        val repo = repo()
        repo.seed("source-a", "com.one", "Patch X")

        val snapshot = repo.allSelections()
        repo.resetForApp("com.one")

        assertNotNull(
            snapshot["source-a"]?.get("com.one"),
            "a snapshot already handed out must not change underneath its holder",
        )
        assertNull(repo.get("source-a", "com.one"))
    }

    @Test
    fun `resetForApp clears that package across every source and leaves other apps alone`() = runBlocking {
        val repo = repo()
        repo.seed("source-a", "com.one", "Patch X")
        repo.seed("source-b", "com.one", "Patch Z")
        repo.seed("source-b", "com.two", "Patch Y")

        repo.resetForApp("com.one")

        assertNull(repo.get("source-a", "com.one"))
        assertNull(repo.get("source-b", "com.one"))
        assertNotNull(repo.get("source-b", "com.two"))
        assertFalse(
            repo.allSelections().containsKey("source-a"),
            "a source left with no saved apps should not linger as an empty entry",
        )
    }

    @Test
    fun `resetForApp for an app with nothing saved changes nothing`() = runBlocking {
        val file = newFile()
        val repo = repo(file)
        repo.seed("source-a", "com.one", "Patch X")
        val before = file.readText()

        repo.resetForApp("com.unknown")

        assertEquals(before, file.readText(), "no write should happen when there was nothing to reset")
    }

    @Test
    fun `resetForSource drops every app saved under that source only`() = runBlocking {
        val repo = repo()
        repo.seed("source-a", "com.one", "Patch X")
        repo.seed("source-a", "com.two", "Patch Y")
        repo.seed("source-b", "com.one", "Patch Z")

        repo.resetForSource("source-a")

        assertNull(repo.get("source-a", "com.one"))
        assertNull(repo.get("source-a", "com.two"))
        assertNotNull(repo.get("source-b", "com.one"))
    }

    @Test
    fun `resetForSource for an unknown source is a no-op`() = runBlocking {
        val repo = repo()
        repo.seed("source-a", "com.one", "Patch X")

        repo.resetForSource("never-existed")

        assertNotNull(repo.get("source-a", "com.one"))
    }

    @Test
    fun `resets are durable across a restart`() = runBlocking {
        val file = newFile()
        val first = repo(file)
        first.seed("source-a", "com.one", "Patch X")
        first.seed("source-b", "com.two", "Patch Y")
        first.resetForApp("com.one")
        first.resetForSource("source-b")

        val second = repo(file)

        assertTrue(second.allSelections().isEmpty(), "a fresh instance must read back the reset state")
    }

    @Test
    fun `a saved selection survives a restart and is written atomically`() = runBlocking {
        val file = newFile()
        repo(file).seed("source-a", "com.one", "Patch X")

        assertNotNull(repo(file).get("source-a", "com.one"))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists(), "no temp file may be left behind")
    }
}
