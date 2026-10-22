/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.MultiSourceLoader
import app.morphe.engine.options.PatchPreferencesRepository
import app.morphe.engine.patches.PatchProvider
import app.morphe.engine.patches.PatchRepository
import app.morphe.engine.patches.RemotePatchSourceFactory
import app.morphe.engine.util.Logger
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.morphe.engine.util.PersistenceFailures
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Visible UI mode for coordinating patch loading across ViewModels. */
enum class ActiveMode { QUICK, EXPERT }

/** Manages [PatchRepository] instances and lifecycle for configured patch sources. */
class PatchSourceManager(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository,
    private val sourceMuteRepository: SourceMuteRepository,
    private val patchPreferencesRepository: PatchPreferencesRepository,
    private val changelogRepository: ChangelogRepository,
) {

    private val repositories = mutableMapOf<String, PatchRepository>()

    private var cachedActiveRepo: PatchRepository? = null
    private var cachedActiveSource: PatchSource? = null

    private var cachedEnabledSources: List<PatchSource> = emptyList()

    private val _sourceVersion = MutableStateFlow(0)
    val sourceVersion: StateFlow<Int> = _sourceVersion.asStateFlow()

    private val _allSources = MutableStateFlow<List<PatchSource>>(emptyList())
    val allSources: StateFlow<List<PatchSource>> = _allSources.asStateFlow()

    // Active UI mode used to prevent inactive ViewModels from fetching patches.
    private val _activeMode = MutableStateFlow(ActiveMode.QUICK)
    val activeMode: StateFlow<ActiveMode> = _activeMode.asStateFlow()

    fun setActiveMode(mode: ActiveMode) {
        if (_activeMode.value != mode) {
            Logger.info("PatchSourceManager: active mode → $mode")
            _activeMode.value = mode
        }
    }

    /** Loads active and enabled patch sources from configuration. */
    suspend fun initialize() {
        configRepository.migrateSourceChannelFlags()
        val source = configRepository.getActivePatchSource()
        cachedActiveSource = source
        cachedActiveRepo = getRepositoryForSource(source)
        refreshEnabledSources()
        Logger.info("PatchSourceManager initialized with source '${source.name}' (type=${source.type})")
        Logger.info("Enabled sources: ${cachedEnabledSources.joinToString { it.name }}")
    }

    /**
     * Switch the active source, persist it, and signal the UI.
     */
    suspend fun switchSource(id: String) {
        configRepository.setActivePatchSource(id)
        val source = configRepository.getActivePatchSource()
        cachedActiveSource = source
        cachedActiveRepo = getRepositoryForSource(source)
        _sourceVersion.value++
        Logger.info("Switched active patch source to '${source.name}' (type=${source.type})")
    }

    /** Returns true if the active source is a local file. */
    fun isLocalSource(): Boolean {
        return cachedActiveSource?.type == PatchSourceType.LOCAL
    }

    /** Returns local file path if active source is local, or null otherwise. */
    fun getLocalFilePath(): String? {
        val source = cachedActiveSource ?: return null
        return if (source.type == PatchSourceType.LOCAL) source.filePath else null
    }

    /** Returns display name of active patch source. */
    fun getActiveSourceName(): String {
        return cachedActiveSource?.name ?: "Morphe Patches"
    }

    /** Returns true if active source is the default built-in repository. */
    fun isDefaultSource(): Boolean {
        return cachedActiveSource?.type == PatchSourceType.DEFAULT
    }

    /** Returns cached [PatchRepository] for active source, defaulting to built-in repo. */
    fun getActiveRepositorySync(): PatchRepository {
        return cachedActiveRepo ?: defaultMorpheRepository().also {
            if (!isLocalSource()) cachedActiveRepo = it
        }
    }

    /** Creates a [PatchRepository] pointed at default repository. */
    private fun defaultMorpheRepository(): PatchRepository {
        val remote = RemotePatchSourceFactory.build(
            PatchProvider.GITHUB,
            RemotePatchSourceFactory.DEFAULT_REPO_PATH,
            httpClient,
        )
        return PatchRepository(remote)
    }

    /** Returns cached or newly created [PatchRepository] for [source], or null if local. */
    fun getRepositoryForSource(source: PatchSource): PatchRepository? {
        if (source.type == PatchSourceType.LOCAL) return null

        return repositories.getOrPut(source.id) {
            val remote = if (!source.url.isNullOrBlank()) {
                RemotePatchSourceFactory.from(source.url, httpClient)
            } else null

            val finalRemote = remote ?: run {
                // Map source type to engine provider enum; default source uses GitHub.
                val provider = when (source.type) {
                    PatchSourceType.GITLAB -> PatchProvider.GITLAB
                    else -> PatchProvider.GITHUB
                }
                RemotePatchSourceFactory.build(
                    provider,
                    RemotePatchSourceFactory.DEFAULT_REPO_PATH,
                    httpClient,
                )
            }
            Logger.info("Creating PatchRepository for source '${source.name}' (repo=${finalRemote.repoPath}, provider=${finalRemote.provider})")
            PatchRepository(finalRemote)
        }
    }

    /** Returns active patch source configuration. */
    suspend fun getActiveSource(): PatchSource {
        return configRepository.getActivePatchSource()
    }

    /** Clears cached repository data and notifies observers. */
    fun notifyCacheCleared() {
        cachedActiveRepo?.clearCache()
        // Patch files and changelogs are one cache to the user: clearing one must not leave
        // the other serving data the user just asked to be rid of
        changelogRepository.invalidateAll()
        _sourceVersion.value++
    }

    /** Returns snapshot of currently enabled patch sources. */
    fun getEnabledSourcesSync(): List<PatchSource> = cachedEnabledSources

    /** Pairs each enabled source with its corresponding [PatchRepository], or null if local. */
    fun getEnabledRepositories(): List<Pair<PatchSource, PatchRepository?>> =
        cachedEnabledSources.map { it to getRepositoryForSource(it) }

    /** Toggles enablement of a patch source and notifies observers. */
    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        configRepository.setPatchSourceEnabled(id, enabled)
        refreshEnabledSources()
        _sourceVersion.value++
        Logger.info("Source '$id' enabled=$enabled. Enabled now: ${cachedEnabledSources.joinToString { it.name }}")
    }

    /** Adds a new patch source and refreshes enabled sources. */
    suspend fun addSource(source: PatchSource) {
        configRepository.addPatchSource(source)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    /** Removes a patch source by ID and clears its cached repository. */
    suspend fun removeSource(id: String) {
        // Looked up before the config write removes it, since invalidation needs
        // the source's own URL to know which changelog cache entry is its
        val removed = _allSources.value.firstOrNull { it.id == id }
        configRepository.removePatchSource(id)
        repositories.remove(id)
        // Attribution for already-tracked apps lives on their records, not in this cache, so
        // the source's changelog entries (stale fallback included) can go entirely
        removed?.let { changelogRepository.invalidate(it, dropStale = true) }
        // A removed source can't stay "muted for" anything, and a stale strike against a
        // file that no longer exists would only ever misattribute a future unrelated source
        // reusing the same id.
        // The source itself is already removed, so a failed cleanup of its leftovers is reported
        // rather than allowed to undo or abort the removal.
        try {
            sourceMuteRepository.resetForSource(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            PersistenceFailures.report("the removed source's mute settings", e)
        }
        try {
            patchPreferencesRepository.resetForSource(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            PersistenceFailures.report("the removed source's saved patch selections", e)
        }
        MultiSourceLoader.forgetLoadGuardState(id)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    // ── Source muting ────────────────────────────────────────────────────────
    //
    // Narrows which of the *enabled* sources are offered for one particular app, without
    // touching that source for anything else. See [SourceMuteRepository] for the persisted
    // state and the invariants (a mute is never recorded if it would leave an app with
    // nothing to patch from).

    /**
     * The enabled sources offered for [packageName], excluding its muted sources. If the
     * persisted mute state would otherwise hide every enabled source (for example after a
     * source was removed or coverage changed), retain the full list as a safe fallback.
     */
    suspend fun effectiveSourcesFor(packageName: String): List<PatchSource> {
        val muted = sourceMuteRepository.getMutedFor(packageName)
        val filtered = cachedEnabledSources.filterNot { it.id in muted }
        return filtered.ifEmpty { cachedEnabledSources }
    }

    suspend fun mutedSourceIdsForApp(packageName: String): Set<String> =
        sourceMuteRepository.getMutedFor(packageName)

    /**
     * Mutes [sourceId] for [packageName], unless doing so would remove the last source
     * known to have patches for that app. Returns false without persisting when it would.
     *
     * @param coveredByThisApp The set of enabled source ids that actually have a patch
     *   targeting [packageName] (e.g. from `EnabledSourcesLoader.Result.patchesBySource`
     *   filtered by each patch's compatible packages), when the caller has already computed it.
     *   When omitted, falls back to checking whether more than one source is currently
     *   offered for the app; this is necessarily coarser because it cannot inspect patch
     *   coverage for each source.
     */
    suspend fun muteSourceForApp(
        packageName: String,
        sourceId: String,
        coveredByThisApp: Set<String>? = null,
    ): Boolean {
        if (coveredByThisApp != null) {
            val alreadyMuted = sourceMuteRepository.getMutedFor(packageName)
            // Only block muting when this source currently contributes patches and no
            // other contributing source remains unmuted. A source with no applicable
            // patches can be muted without making the app unpatchable.
            val remainingSources = coveredByThisApp - alreadyMuted - sourceId
            if (sourceId in coveredByThisApp && remainingSources.isEmpty()) return false
        } else {
            val currentlyOffered = effectiveSourcesFor(packageName).map { it.id }.toSet()
            if (currentlyOffered.size <= 1 && sourceId in currentlyOffered) return false
        }
        sourceMuteRepository.mute(packageName, sourceId)
        return true
    }

    suspend fun unmuteSourceForApp(packageName: String, sourceId: String) =
        sourceMuteRepository.unmute(packageName, sourceId)

    /** Persists reordered patch source IDs and updates active snapshot. */
    suspend fun reorderSources(orderedIds: List<String>) {
        configRepository.reorderPatchSources(orderedIds)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    /** Updates patch source configuration and invalidates cached repository. */
    suspend fun updateSource(updated: PatchSource) {
        // Captured before the write: after it, the old URL is unreachable from the config,
        // and its changelog would sit in the cache under a URL nothing points at any more
        val previous = _allSources.value.firstOrNull { it.id == updated.id }
        configRepository.updatePatchSource(updated)
        repositories.remove(updated.id)
        previous?.let { changelogRepository.invalidate(it, dropStale = true) }
        changelogRepository.invalidate(updated)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    private suspend fun refreshEnabledSources() {
        val all = configRepository.loadConfig().patchSource
        val enabled = all.filter { it.enabled }
        cachedEnabledSources = enabled
        _allSources.value = all
    }
}
