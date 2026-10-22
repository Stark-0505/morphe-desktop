/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.patches.PatchBundleHeldBackException
import app.morphe.engine.patches.PatchBundleLoadGuard
import app.morphe.engine.util.FilenameUtils
import app.morphe.engine.util.Logger
import app.morphe.engine.workspace.WorkspaceManager
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Loads patches in parallel from multiple patch bundle files. */
object MultiSourceLoader {

    // Shared across every source: state is keyed per sourceId internally, and a bundle
    // held back for repeatedly crashing the app must stay held back regardless of which
    // load call (quick-patch, expert mode, a manual refresh) happens to trigger it next.
    private val loadGuard = PatchBundleLoadGuard(MorpheData.patchesDir)

    /** Drops any remembered load-crash state for [sourceId] — call when a source is removed. */
    fun forgetLoadGuardState(sourceId: String) = loadGuard.forget(sourceId)

    data class SourceInput(
        val sourceId: String,
        val sourceName: String,
        val patchFile: File,
    )

    data class LoadedSource(
        val sourceId: String,
        val sourceName: String,
        val patches: Set<Patch<*>>,
        val error: Throwable? = null,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    data class Result(
        val perSource: List<LoadedSource>,
        val allPatches: Set<Patch<*>>,
        // Maps each deduplicated patch to the source IDs that contain it.
        val patchToSourceIds: Map<Patch<*>, Set<String>>,
    ) {
        val hasErrors: Boolean get() = perSource.any { !it.isSuccess }
    }

    /**
     * Load patches from each input in parallel. Each .mpp is copied to a temp file
     * before loading to work around Windows URLClassLoader file-locking.
     */
    suspend fun load(inputs: List<SourceInput>): Result = loadWith(inputs)

    /** [load] with its collaborators injectable for tests; production calls [load]. */
    internal suspend fun loadWith(
        inputs: List<SourceInput>,
        createShadowCopy: (File, String) -> File = { file, prefix -> WorkspaceManager.createShadowCopy(file, prefix = prefix) },
        loadPatches: (File) -> Set<Patch<*>> = { loadPatchesFromJar(setOf(it)) },
        guard: PatchBundleLoadGuard = loadGuard,
    ): Result = coroutineScope {
        val loaded = inputs.map { input ->
            async(Dispatchers.IO) { loadOne(input, createShadowCopy, loadPatches, guard) }
        }.awaitAll()

        // Deduplicate identical patches while tracking all contributing sources.
        val allPatches = loaded.flatMap { it.patches }.toSet()
        val patchToSourceIds: Map<Patch<*>, Set<String>> = loaded
            .flatMap { src -> src.patches.map { it to src.sourceId } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

        Result(
            perSource = loaded,
            allPatches = allPatches,
            patchToSourceIds = patchToSourceIds,
        )
    }

    /**
     * Loads one source, entirely inside its own failure boundary: preparing the shadow copy, the
     * guarded read, and building the result all happen under the same `try`, so nothing that goes
     * wrong with this source can escape and take the others down through `awaitAll()`.
     *
     * Cancellation is not a failure: it is rethrown immediately, so a cancelled load stays
     * cancelled instead of being reported as a broken bundle. `catch (Throwable)` is deliberately
     * narrowed to `Exception` + `LinkageError`: a bundle built against a newer patcher throws
     * `NoSuchMethodError` / `NoClassDefFoundError` / `ExceptionInInitializerError` (all
     * `LinkageError`s) which must be isolated per source, but `OutOfMemoryError` and the like say
     * nothing about this source and must not be swallowed.
     *
     * [createShadowCopy] and [loadPatches] are seams for tests; production uses the defaults.
     */
    internal suspend fun loadOne(
        input: SourceInput,
        createShadowCopy: (File, String) -> File = { file, prefix -> WorkspaceManager.createShadowCopy(file, prefix = prefix) },
        loadPatches: (File) -> Set<Patch<*>> = { loadPatchesFromJar(setOf(it)) },
        guard: PatchBundleLoadGuard = loadGuard,
    ): LoadedSource = withContext(Dispatchers.IO) {
        var tempCopy: File? = null
        try {
            // The id is a free-form string; as a file-name prefix it must not carry separators.
            tempCopy = createShadowCopy(input.patchFile, "morphe-mp-${FilenameUtils.sanitize(input.sourceId).take(40)}")
            val copy = tempCopy
            // Attributed to input.patchFile (the real source file, not the shadow copy) so a
            // crash is remembered against the bundle's actual identity, and so a replaced
            // file — different size/mtime — clears any prior strikes on its own.
            val patches = guard.read(input.sourceId, input.patchFile) { loadPatches(copy) }
            Logger.info("MultiSourceLoader: loaded ${patches.size} patches from '${input.sourceName}'")
            LoadedSource(
                sourceId = input.sourceId,
                sourceName = input.sourceName,
                patches = patches,
            )
        } catch (e: CancellationException) {
            runCatching { tempCopy?.delete() }
            throw e
        } catch (e: Exception) {
            failedSource(input, e, tempCopy)
        } catch (e: LinkageError) {
            failedSource(input, e, tempCopy)
        }
    }

    private fun failedSource(input: SourceInput, e: Throwable, tempCopy: File?): LoadedSource {
        // Nothing loaded from it, so the shadow copy has no class loader holding it open.
        runCatching { tempCopy?.delete() }
        // Do NOT swallow the real failure: many Errors have a null .message (e.g.
        // ExceptionInInitializerError) with the useful text on .cause. We always store an
        // exception whose message walks the full cause chain, and log the full stack.
        val error: Throwable = if (e is PatchBundleHeldBackException) {
            // Already a clear, user-facing message; don't run it through the generic
            // incompatibility/readableMessage paths below, which are about a different
            // failure mode (a bundle built against a newer/older patcher).
            e
        } else {
            val versionMsg = runCatching { PatcherCompatibility.incompatibilityMessage(input.patchFile) }.getOrNull()
            if (versionMsg != null) {
                PatchBundleIncompatibleException(versionMsg)
            } else {
                PatchSourceLoadException(e.readableMessage(), e)
            }
        }
        Logger.warn(
            "MultiSourceLoader: failed to load '${input.sourceName}': ${error.message}",
            e,
        )
        // Also stderr so IDE runs / headless CLI see the stack even if JUL is unconfigured.
        System.err.println("MultiSourceLoader: failed to load '${input.sourceName}': ${error.message}")
        e.printStackTrace(System.err)
        return LoadedSource(
            sourceId = input.sourceId,
            sourceName = input.sourceName,
            patches = emptySet(),
            error = error,
        )
    }
}
