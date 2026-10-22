/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.options.PatchPreferencesRepository
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data side of the patch-selection screen's "Copy from…" chip: which saved selections are
 * offered as candidates, in what order, and what copying one of them actually contributes.
 * Everything is matched by saved patch *name*, so a renamed or dropped patch must shrink what a
 * candidate offers rather than landing the copy on the wrong row.
 */
class CopySelectionTest {

    private fun repo() = PatchPreferencesRepository(File(createTempDirectory().toFile(), "patch-preferences.json"))

    private fun source(
        id: String,
        name: String = id,
        url: String? = "https://github.com/acme/$id",
        type: PatchSourceType = PatchSourceType.GITHUB,
    ) = PatchSource(id = id, name = name, type = type, url = url)

    private suspend fun PatchPreferencesRepository.seed(
        source: String,
        pkg: String,
        enabled: Set<String>,
        disabled: Set<String> = emptySet(),
        options: Map<String, Map<String, kotlinx.serialization.json.JsonElement>> = emptyMap(),
    ) = save(source, pkg, enabledPatchNames = enabled, disabledPatchNames = disabled, options = options)

    private fun candidate(sourceId: String, pkg: String) = CopySelectionCandidate(
        sourceId = sourceId,
        sourceName = sourceId,
        packageName = pkg,
        packageDisplayName = pkg,
        sourcePatchCount = 0,
        applicableCount = 0,
        isSameSource = false,
        isSamePackage = false,
    )

    // ── Candidates ──────────────────────────────────────────────────────────

    @Test
    fun `nothing saved means nothing to copy from`() = runBlocking {
        val result = loadCopySelectionCandidates(repo(), listOf(source("a")), "com.one", "a", setOf("P1"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the target pairing is never offered to itself`() = runBlocking {
        val repo = repo()
        repo.seed("a", "com.one", setOf("P1"))
        repo.seed("a", "com.two", setOf("P1"))

        val result = loadCopySelectionCandidates(repo, listOf(source("a")), "com.one", "a", setOf("P1"))

        assertEquals(listOf("com.two"), result.map { it.packageName })
    }

    @Test
    fun `the same package under another source is offered`() = runBlocking {
        val repo = repo()
        repo.seed("a", "com.one", setOf("P1"))
        repo.seed("b", "com.one", setOf("P1"))

        val result = loadCopySelectionCandidates(repo, listOf(source("a"), source("b")), "com.one", "a", setOf("P1"))

        assertEquals(listOf("b"), result.map { it.sourceId })
        assertTrue(result.single().isSamePackage)
    }

    @Test
    fun `a selection saved under a source that no longer exists is not offered`() = runBlocking {
        val repo = repo()
        repo.seed("gone", "com.two", setOf("P1"))

        val result = loadCopySelectionCandidates(repo, listOf(source("a")), "com.one", "a", setOf("P1"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a saved selection that enables nothing is not offered`() = runBlocking {
        val repo = repo()
        repo.seed("a", "com.two", enabled = emptySet(), disabled = setOf("P1"))

        val result = loadCopySelectionCandidates(repo, listOf(source("a")), "com.one", "a", setOf("P1"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `counts reflect enabled patches and the overlap with the target by name`() = runBlocking {
        val repo = repo()
        repo.seed("a", "com.two", enabled = setOf("P1", "P2", "Renamed"), disabled = setOf("P3"))

        val result = loadCopySelectionCandidates(
            repo, listOf(source("a")), "com.one", "other", setOf("P1", "P2", "P3"),
        ).single()

        assertEquals(3, result.sourcePatchCount)   // P3 was explicitly disabled, so it is not counted
        assertEquals(2, result.applicableCount)    // "Renamed" no longer exists in the target
    }

    @Test
    fun `candidates are ordered same source, then same package, then overlap, then name`() = runBlocking {
        val repo = repo()
        val sources = listOf(
            source("a", url = "https://github.com/acme/patches"),
            // Same endpoint imported again under another id; hosts and paths compare case-insensitively
            source("b", url = "https://GitHub.com/Acme/Patches"),
            source("c", url = "https://github.com/other/patches"),
        )
        repo.seed("c", "com.one", setOf("P1", "P2", "P3"))   // other source, same package, overlap 3
        repo.seed("c", "com.two", setOf("P1", "P2"))         // other source, other package, overlap 2
        repo.seed("b", "com.two", setOf("P1"))               // same endpoint, overlap 1
        repo.seed("a", "com.two", setOf("P1"))               // same endpoint, overlap 1, sorts before b by name

        val result = loadCopySelectionCandidates(repo, sources, "com.one", "a", setOf("P1", "P2", "P3"))

        assertEquals(
            listOf("a" to "com.two", "b" to "com.two", "c" to "com.one", "c" to "com.two"),
            result.map { it.sourceId to it.packageName },
        )
        assertEquals(listOf(true, true, false, false), result.map { it.isSameSource })
    }

    @Test
    fun `two local sources are never treated as the same source`() = runBlocking {
        val repo = repo()
        val sources = listOf(
            source("a", url = null, type = PatchSourceType.LOCAL),
            source("b", url = null, type = PatchSourceType.LOCAL),
        )
        repo.seed("b", "com.two", setOf("P1"))

        val result = loadCopySelectionCandidates(repo, sources, "com.one", "a", setOf("P1"))

        assertFalse(result.single().isSameSource)
    }

    // ── What copying contributes ────────────────────────────────────────────

    @Test
    fun `copying keeps only enabled patches that still exist in the target`() = runBlocking {
        val repo = repo()
        repo.seed(
            "b", "com.two",
            enabled = setOf("P1", "P2", "Gone"),
            disabled = setOf("P3"),
            options = mapOf(
                "P1" to mapOf("color" to JsonPrimitive("red")),
                "Gone" to mapOf("x" to JsonPrimitive(1)),
            ),
        )

        val copied = resolveCopySelection(repo, candidate("b", "com.two"), setOf("P1", "P2", "P3"))

        assertNotNull(copied)
        assertEquals(setOf("P1", "P2"), copied.patchNames)
        assertEquals(setOf("P1"), copied.options.keys)
        assertEquals(JsonPrimitive("red"), copied.options.getValue("P1").getValue("color"))
    }

    @Test
    fun `copying a selection none of whose patches exist in the target contributes nothing`() = runBlocking {
        val repo = repo()
        repo.seed("b", "com.two", setOf("Gone"))

        assertNull(resolveCopySelection(repo, candidate("b", "com.two"), setOf("P1")))
    }

    @Test
    fun `copying a selection that has since been deleted contributes nothing`() = runBlocking {
        val repo = repo()
        repo.seed("b", "com.two", setOf("P1"))
        repo.resetForApp("com.two")

        assertNull(resolveCopySelection(repo, candidate("b", "com.two"), setOf("P1")))
    }

    @Test
    fun `a candidate sharing no patches with the target is reported with zero applicable patches`() = runBlocking {
        val repo = repo()
        repo.seed("other", "com.other", enabled = setOf("Patch X", "Patch Y"))
        val candidates = loadCopySelectionCandidates(
            repo, listOf(source("target"), source("other")), "com.app", "target", targetPatchNames = setOf("Unrelated"),
        )
        // Still listed (so the user can see why), but flagged so the picker can refuse to offer it:
        // choosing it could only ever copy nothing.
        assertEquals(1, candidates.size)
        assertEquals(0, candidates.single().applicableCount)
        assertEquals(2, candidates.single().sourcePatchCount)
    }

    @Test
    fun `options of patches that are absent from the target are not carried over`() = runBlocking {
        val repo = repo()
        repo.seed(
            "other", "com.other",
            enabled = setOf("Present", "Absent"),
            options = mapOf(
                "Present" to mapOf("k" to JsonPrimitive("kept")),
                "Absent" to mapOf("k" to JsonPrimitive("dropped")),
            ),
        )
        val copied = resolveCopySelection(repo, candidate("other", "com.other"), targetPatchNames = setOf("Present"))
        assertNotNull(copied)
        assertEquals(setOf("Present"), copied.patchNames)
        assertEquals(setOf("Present"), copied.options.keys)
    }
}
