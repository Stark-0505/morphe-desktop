/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.patches.PatchBundleHeldBackException
import app.morphe.engine.patches.PatchBundleLoadGuard
import app.morphe.engine.patches.PatchBundleLoadGuardTest
import app.morphe.patcher.patch.Patch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MultiSourceLoader]'s per-source failure boundary: one bad bundle (or one that cannot even be
 * prepared) must never fail the others, cancellation must stay cancellation, and linkage errors
 * from third-party bundles must be isolated while genuine VM errors are not swallowed.
 * Collaborators are injected, so no real patch bundle, class loading or user data directory is used.
 */
class MultiSourceLoaderIsolationTest {

    private val tmp = createTempDirectory("msl-test").toFile()
    private val fixtureSequence = AtomicInteger()
    private fun guard() = PatchBundleLoadGuard(File(tmp, "guard-${System.nanoTime()}").apply { mkdirs() })
    private fun jar(name: String) = File(tmp, name).apply { writeText("jar:$name") }
    // Source IDs are untrusted metadata and may contain path separators or control characters.
    // Never derive a test fixture path from them: doing so can fail before the loader is exercised.
    private fun input(id: String, file: File = jar("source-${fixtureSequence.incrementAndGet()}.mpp")) =
        MultiSourceLoader.SourceInput(id, "Source $id", file)
    private val identityCopy: (File, String) -> File = { f, _ -> File(tmp, "copy-${f.name}").also { f.copyTo(it, overwrite = true) } }
    private val noPatches: (File) -> Set<Patch<*>> = { emptySet() }

    @Test
    fun `a shadow copy failure for one source does not fail the others`() = runBlocking {
        val failing = jar("bad.mpp")
        val result = MultiSourceLoader.loadWith(
            listOf(input("good-1"), input("bad", failing), input("good-2")),
            createShadowCopy = { f, p -> if (f == failing) throw IOException("disk full") else identityCopy(f, p) },
            loadPatches = noPatches,
            guard = guard(),
        )
        assertEquals(3, result.perSource.size)
        assertTrue(result.perSource.single { it.sourceId == "good-1" }.isSuccess)
        assertTrue(result.perSource.single { it.sourceId == "good-2" }.isSuccess)
        val bad = result.perSource.single { it.sourceId == "bad" }
        assertFalse(bad.isSuccess)
        assertNotNull(bad.error)
        assertTrue(result.hasErrors)
    }

    @Test
    fun `an exception while loading one bundle is isolated to that source`() = runBlocking {
        val broken = jar("broken.mpp")
        val result = MultiSourceLoader.loadWith(
            listOf(input("ok"), input("broken", broken)),
            createShadowCopy = identityCopy,
            loadPatches = { copy -> if (copy.name.contains("broken")) throw IllegalStateException("bad bundle") else emptySet() },
            guard = guard(),
        )
        assertTrue(result.perSource.single { it.sourceId == "ok" }.isSuccess)
        assertFalse(result.perSource.single { it.sourceId == "broken" }.isSuccess)
    }

    @Test
    fun `a LinkageError from a bundle built for another patcher is isolated and keeps its cause`() = runBlocking {
        val loaded = MultiSourceLoader.loadOne(
            input("newer"),
            createShadowCopy = identityCopy,
            loadPatches = { throw NoSuchMethodError("app.morphe.patcher.Something.missing()") },
            guard = guard(),
        )
        assertFalse(loaded.isSuccess)
        val error = assertNotNull(loaded.error)
        assertTrue(error.cause is NoSuchMethodError, "the real failure must stay reachable, was ${error.cause}")
    }

    @Test
    fun `an OutOfMemoryError is not swallowed as a broken bundle`() {
        assertFailsWith<OutOfMemoryError> {
            runBlocking {
                MultiSourceLoader.loadOne(
                    input("huge"),
                    createShadowCopy = identityCopy,
                    loadPatches = { throw OutOfMemoryError("Java heap space") },
                    guard = guard(),
                )
            }
        }
    }

    @Test
    fun `cancellation during a load stays cancellation and is not reported as a source failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            MultiSourceLoader.loadOne(
                input("slow"),
                createShadowCopy = identityCopy,
                loadPatches = { started.complete(Unit); runBlocking { release.await() }; emptySet() },
                guard = guard(),
            )
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun `an unusual source id does not break temp file preparation`() = runBlocking {
        val seen = mutableListOf<String>()
        // The default fixture path must be independent of the hostile source ID, and the behavior
        // under test is sanitizing the temp prefix derived from that ID.
        val hostileId = "../../weird/id with spaces\nand newline"
        val source = input(hostileId)
        assertTrue(source.patchFile.exists(), "the test fixture must be created before loader invocation")
        assertEquals(tmp.canonicalFile, source.patchFile.parentFile.canonicalFile)
        val loaded = MultiSourceLoader.loadOne(
            source,
            createShadowCopy = { f, prefix -> seen += prefix; identityCopy(f, prefix) },
            loadPatches = noPatches,
            guard = guard(),
        )
        assertTrue(loaded.isSuccess)
        assertTrue(seen.single().none { it == '/' || it == '\\' || it == '\n' }, seen.single())
    }

    @Test
    fun `a held back bundle surfaces its own clear message and does not run the loader`() = runBlocking {
        val file = jar("crashy.mpp")
        val calls = AtomicInteger()
        val loaded = MultiSourceLoader.loadOne(
            MultiSourceLoader.SourceInput("crashy", "Crashy", file),
            createShadowCopy = identityCopy,
            loadPatches = { calls.incrementAndGet(); emptySet() },
            guard = PatchBundleLoadGuardTest.guardHoldingBack(tmp, "crashy", file),
        )
        assertEquals(0, calls.get(), "a held-back bundle must not be loaded again")
        assertIs<PatchBundleHeldBackException>(loaded.error)
    }
}
