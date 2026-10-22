/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.engine.MorpheConstants
import app.morphe.engine.OriginalApkRepository
import app.morphe.engine.PatchedAppStore
import app.morphe.engine.UpdateInfo
import app.morphe.engine.apk.ApkInspector
import app.morphe.engine.apk.ApkOutputNaming
import app.morphe.engine.apk.BundleFormats
import app.morphe.engine.config.EngineConfigRepository
import app.morphe.engine.model.PatchMetadata
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.model.SupportedApp
import app.morphe.engine.model.VersionResolution
import app.morphe.engine.model.VersionStatus
import app.morphe.engine.patches.PatchRepository
import app.morphe.engine.patches.PatchResolver
import app.morphe.engine.patches.SupportedAppCatalog
import app.morphe.engine.patches.resolveVersionStatus
import app.morphe.engine.readableMessage
import app.morphe.engine.util.AdbException
import app.morphe.engine.util.AdbManager
import app.morphe.engine.util.KeystoreService
import app.morphe.engine.util.Logger
import app.morphe.engine.util.isNewerVersion
import app.morphe.gui.data.appicon.AppIconRepository
import app.morphe.gui.data.model.SourceVersionPref
import app.morphe.gui.data.repository.ActiveMode
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.data.repository.changelogRequest
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.data.repository.UpdateCheckRepository
import app.morphe.gui.ui.screens.home.components.AppListFilter
import app.morphe.gui.ui.screens.home.components.HomeAppSortMode
import app.morphe.gui.util.ChecksumStatus
import app.morphe.gui.util.DeviceInstallState
import app.morphe.gui.util.DeviceMonitor
import app.morphe.gui.util.PatchedArtifactState
import app.morphe.gui.util.TrackedInstallResolver
import app.morphe.gui.util.TrackedInstallSnapshot
import app.morphe.gui.util.EnabledSourcesLoader
import app.morphe.gui.util.logIfFailed
import app.morphe.gui.util.PatchException
import app.morphe.gui.util.getUserMessage
import app.morphe.gui.util.humanizePatchLoadError
import app.morphe.morphe_desktop.generated.resources.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

class HomeViewModel(
    private val patchSourceManager: PatchSourceManager,
    private val configRepository: ConfigRepository,
    private val updateCheckRepository: UpdateCheckRepository,
    private val patchedAppStore: PatchedAppStore,
    private val changelogRepository: ChangelogRepository,
    private val originalApkRepository: OriginalApkRepository,
    private val appIconRepository: AppIconRepository,
    // Required, not defaulted: a default here is exactly what let this parameter go
    // missing from its Koin registration (AppModule.kt) without either the compiler
    // or a test catching it — the ViewModel silently got its own private AdbManager,
    // disconnected from DeviceMonitor's, instead of the shared one every other
    // consumer resolves through Koin. Upstream's own version of this class still
    // defaults this parameter; kept required here deliberately.
    private val adbManager: AdbManager,
    private val engineConfigRepository: EngineConfigRepository = EngineConfigRepository.shared,
    private val keystoreService: KeystoreService = KeystoreService.shared,
) : ViewModel() {

    /**
     * Device- and disk-side verdicts for the patched-app history. Owned here so
     * one reading of the device backs every card and the app information dialog.
     */
    private val trackedInstallResolver = TrackedInstallResolver(adbManager)

    /** Serializes the rebuild: a patch finishing and a device arriving can land together. */
    private val trackedStateLock = Mutex()

    private var patchRepository: PatchRepository = patchSourceManager.getActiveRepositorySync()
    private var localPatchFilePath: String? = patchSourceManager.getLocalFilePath()
    private var isDefaultSource: Boolean = patchSourceManager.isDefaultSource()

    private val _uiState = MutableStateFlow(HomeUiState(isDefaultSource = isDefaultSource))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Cached patches and supported apps
    private var cachedPatches: List<PatchMetadata> = emptyList()
    private var cachedPatchesFile: File? = null
    /** All resolved patch files across enabled sources. */
    private var cachedAllPatchFiles: List<File> = emptyList()
    private var loadJob: Job? = null

    fun getAllResolvedPatchFiles(): List<File> =
        cachedAllPatchFiles.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(cachedPatchesFile)

    /** Display names for each resolved patch file, ordered matching [getAllResolvedPatchFiles]. */
    fun getAllResolvedPatchSourceNames(): List<String> =
        resolvedPatchSources().map { it.source.name }

    /** Stable source ids for each entry in [getAllResolvedPatchFiles], in the same
     *  order. Patch selections are saved and restored under these, never the names. */
    fun getAllResolvedPatchSourceIds(): List<String> =
        resolvedPatchSources().map { it.source.id }

    private fun resolvedPatchSources(): List<EnabledSourcesLoader.ResolvedSource> =
        cachedSourcesResult?.resolved?.filter { it.patchFile != null }.orEmpty()

    init {
        viewModelScope.launch {
            val config = configRepository.loadConfig()
            val info = updateCheckRepository.getUpdateInfo()
            val dismissed = config.dismissedUpdateVersion
            val multiSourceShouldShow = !config.multiSourceHintDismissed &&
                    patchSourceManager.getEnabledSourcesSync().size > 1
            _uiState.value = _uiState.value.copy(
                updateInfo = info,
                dismissedUpdateVersion = dismissed,
                showMultiSourceHint = multiSourceShouldShow,
                appListFilter = runCatching {
                    AppListFilter.valueOf(config.homeAppListFilter)
                }.getOrDefault(AppListFilter.ALL),
                sortMode = HomeAppSortMode.fromPreference(config.homeAppSortMode),
            )
        }

        // Refresh badges and device state when history changes.
        viewModelScope.launch {
            patchedAppStore.changes.collect { refreshTrackedState() }
        }

        // Best-effort, once per app launch: drop original-APK records whose file
        // is gone (deleted outside the app, moved disk, etc.) and clean up any
        // `.part` staging file an interrupted save left behind — see
        // OriginalApkRepository.pruneMissingApks. Never blocks startup on this.
        viewModelScope.launch {
            runCatching { originalApkRepository.pruneMissingApks() }
        }

        // Refresh installed status for patched apps when the selected ADB device changes.
        viewModelScope.launch {
            DeviceMonitor.state
                .map { it.selectedDevice?.id to (it.selectedDevice?.isReady == true) }
                .distinctUntilChanged()
                // A different device knows nothing about the last one's verdicts
                .collect { refreshTrackedState(forceDeviceRefresh = true) }
        }

        // Load patches whenever expert mode becomes active.
        viewModelScope.launch {
            patchSourceManager.activeMode.collect { mode ->
                if (mode == ActiveMode.EXPERT) {
                    loadPatchesAndSupportedApps()
                }
            }
        }

        viewModelScope.launch {
            patchSourceManager.sourceVersion.drop(1).collect {
                // Quick mode handles its own source reload.
                if (patchSourceManager.activeMode.value != ActiveMode.EXPERT) return@collect
                Logger.info("HomeVM: Source changed, reloading patches...")
                patchRepository = patchSourceManager.getActiveRepositorySync()
                localPatchFilePath = patchSourceManager.getLocalFilePath()
                isDefaultSource = patchSourceManager.isDefaultSource()
                lastLoadedVersion = null
                cachedPatchesFile = null
                // Preserve update banner state across source changes.
                val carriedUpdate = _uiState.value.updateInfo
                val carriedDismissed = _uiState.value.dismissedUpdateVersion
                _uiState.value = HomeUiState(
                    isDefaultSource = isDefaultSource,
                    updateInfo = carriedUpdate,
                    dismissedUpdateVersion = carriedDismissed,
                )
                loadPatchesAndSupportedApps(forceRefresh = true)
            }
        }
    }

    /** Re-checks for updates; called after settings channel changes. */
    fun refreshUpdateCheck() {
        Logger.info("HomeVM: refreshUpdateCheck() called")
        viewModelScope.launch {
            updateCheckRepository.clearCache()
            val info = updateCheckRepository.getUpdateInfo()
            val dismissed = configRepository.loadConfig().dismissedUpdateVersion
            Logger.info("HomeVM: refresh result, info=${info?.latestVersion}, dismissed=$dismissed")
            _uiState.value = _uiState.value.copy(
                updateInfo = info,
                dismissedUpdateVersion = dismissed,
                updateBannerSessionDismissed = false,
            )
        }
    }

    /** Dismisses the update banner for the current session. */
    fun dismissUpdateForSession() {
        _uiState.value = _uiState.value.copy(updateBannerSessionDismissed = true)
    }

    /** Persistently dismisses the multi-source intro hint. */
    fun dismissMultiSourceHint() {
        _uiState.value = _uiState.value.copy(showMultiSourceHint = false)
        viewModelScope.launch {
            configRepository.setMultiSourceHintDismissed()
        }
    }

    /** Dismisses the failed sources banner for the current failure set. */
    fun dismissSourcesFailedBanner() {
        sourcesFailedBannerDismissed = true
        _uiState.value = _uiState.value.copy(showSourcesFailedBanner = false)
    }

    // Tracks the dismissed failure set; resets when the set of failed sources changes.
    private var lastFailedSourceIds: Set<String> = emptySet()
    private var sourcesFailedBannerDismissed: Boolean = false

    /**
     * Install the already-patched output APK for [packageName] onto the selected
     * device (no re-patch needed). On completion, refresh the device layer so the
     * "install pending" badge clears the moment the device reports the new version.
     */
    fun installPatchedApp(trackingKey: String) {
        val record = recordsByKey[trackingKey] ?: return
        val device = DeviceMonitor.state.value.selectedDevice ?: return
        if (!device.isReady || _uiState.value.installingApp != null) return
        _uiState.value = _uiState.value.copy(installingApp = trackingKey)
        viewModelScope.launch {
            // installApk records a non-Play installer by default so the Play Store
            // won't clobber the patched app with an official update.
            val result = adbManager.installApk(record.outputApkPath, device.id)

            // Configure app link routing if enabled.
            if (result.isSuccess) {
                val engineConfig = engineConfigRepository.loadConfig()
                if (engineConfig.autoRouteLinksAfterInstall) {
                    adbManager.setLinkHandling(
                        deviceId = device.id,
                        patchedPackage = record.installedPackageName,
                        stockPackage = if (engineConfig.disableStockLinksAfterInstall) record.packageName else null,
                        enable = true,
                    )
                }
            }

            val installError = result.exceptionOrNull()?.let {
                (it as? AdbException)?.getUserMessage() ?: getString(Res.string.home_install_failed, it.message ?: "")
            } ?: _uiState.value.error
            _uiState.value = _uiState.value.copy(
                installingApp = null,
                error = installError,
            )
            // Our own install changed what the device holds without changing
            // anything the resolver's fingerprint reads, so the verdict has to go.
            refreshTrackedState(forceDeviceRefresh = true)
        }
    }

    /**
     * Uninstall the patched app for [packageName] from the selected device. When
     * [alsoForget] is true, the recall record is removed afterward (uninstall +
     * delete history). Otherwise the record is kept (uninstall + keep history) so
     * the card stays as a not-installed entry the user can re-install/re-patch.
     *
     * Removing through Morphe (vs the launcher) keeps our device-state tracking
     * accurate. The tracked state is re-resolved on completion so the card flips
     * to not-installed immediately.
     */
    fun uninstallPatchedApp(trackingKey: String, alsoForget: Boolean) {
        val record = recordsByKey[trackingKey] ?: return
        val device = DeviceMonitor.state.value.selectedDevice ?: return
        if (!device.isReady || _uiState.value.uninstallingApp != null) return
        _uiState.value = _uiState.value.copy(uninstallingApp = trackingKey)
        viewModelScope.launch {
            val result = adbManager.uninstallApk(record.installedPackageName, device.id)
            val forgetFailure = if (result.isSuccess && alsoForget) deleteRecord(trackingKey) else null
            val uninstallError = result.exceptionOrNull()?.let {
                (it as? AdbException)?.getUserMessage() ?: getString(Res.string.home_uninstall_failed, it.message ?: "")
            } ?: forgetFailure?.let {
                getString(Res.string.home_uninstall_forget_failed, it.message ?: "")
            } ?: _uiState.value.error
            _uiState.value = _uiState.value.copy(
                uninstallingApp = null,
                error = uninstallError,
            )
            refreshTrackedState(forceDeviceRefresh = true)
        }
    }

    fun setSortMode(mode: HomeAppSortMode) {
        if (_uiState.value.sortMode == mode) return
        _uiState.value = _uiState.value.copy(sortMode = mode)
        viewModelScope.launch { configRepository.setHomeAppSortMode(mode.name) }
    }

    /** Switches the home apps tab and persists the preference. */
    fun setAppListFilter(filter: AppListFilter) {
        if (_uiState.value.appListFilter == filter) return
        _uiState.value = _uiState.value.copy(appListFilter = filter)
        viewModelScope.launch { configRepository.setHomeAppListFilter(filter.name) }
    }

    /** Persistently dismisses the update banner for the current version. */
    fun dismissUpdateForVersion() {
        val target = _uiState.value.updateInfo?.latestVersion ?: return
        _uiState.value = _uiState.value.copy(dismissedUpdateVersion = target)
        viewModelScope.launch {
            configRepository.setDismissedUpdateVersion(target)
        }
    }

    // Tracks the last loaded version to avoid unnecessary reloads.
    private var lastLoadedVersion: String? = null
    // Pinned version snapshot from the last load used to detect version preference changes.
    private var lastLoadedVersionsBySource: Map<String, SourceVersionPref> = emptyMap()

    /**
     * Load patches from all enabled sources via [EnabledSourcesLoader] and build
     * the union supported-apps list. Single-enabled-source case produces output
     * equivalent to the pre-multi-source flow.
     */
    private fun loadPatchesAndSupportedApps(forceRefresh: Boolean = false) {
        // An explicit refresh refreshes what the sources say about themselves as well; the
        // patches reloading while their changelogs keep serving old text would read as a
        // refresh that half-happened
        if (forceRefresh) changelogRepository.invalidateAll()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPatches = true, patchLoadError = null, showSourcesFailedBanner = false)

            try {
                val enabled = patchSourceManager.getEnabledRepositories()
                if (enabled.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingPatches = false,
                        patchLoadError = getString(Res.string.home_no_sources_enabled)
                    )
                    return@launch
                }

                // Load per-source version preferences.
                val prefs = configRepository.getSourceVersionPrefs()
                lastLoadedVersionsBySource = prefs
                val result = EnabledSourcesLoader.loadAll(enabled, prefs, engineConfigRepository.loadConfig().excludedMppPatterns)

                if (!result.anyLoaded) {
                    result.logIfFailed()
                    val firstThrowable = result.loaded.perSource.firstNotNullOfOrNull { it.error }
                    val firstError = result.resolved.firstNotNullOfOrNull { it.getUserErrorMessage() }
                        ?: firstThrowable?.let { humanizePatchLoadError(it) }
                        ?: getString(Res.string.error_could_not_load_patches)
                    val friendlyError = if (firstError.contains("zip", ignoreCase = true) || firstError.contains("END header", ignoreCase = true)) {
                        getString(Res.string.home_patch_file_corrupted)
                    } else {
                        firstError
                    }
                    result.loaded.perSource.filter { !it.isSuccess }.forEach { src ->
                        val err = src.error
                        if (err != null) {
                            Logger.error("Patch source '${src.sourceName}' failed to load", err)
                        }
                    }
                    // Retain snapshot so source management sheet displays failure state.
                    cachedSourcesResult = result
                    _uiState.value = _uiState.value.copy(
                        isLoadingPatches = false,
                        patchLoadError = friendlyError
                    )
                    return@launch
                }

                cachedPatches = result.unionPatches
                // Cache first resolved file for legacy single-file consumers.
                val firstResolved = result.resolved.firstOrNull { it.patchFile != null }
                cachedPatchesFile = firstResolved?.patchFile
                cachedAllPatchFiles = result.resolved.mapNotNull { it.patchFile }
                lastLoadedVersion = firstResolved?.resolvedVersion
                cachedSourcesResult = result

                // Walks every patch's compatibility list — off the main thread.
                val supportedApps = withContext(Dispatchers.IO) {
                    SupportedAppCatalog.extractSupportedAppsFromMetadata(result.unionPatches)
                }
                Logger.info(
                    "Loaded ${supportedApps.size} supported apps from " +
                            "${result.resolved.count { it.patchFile != null }} source(s): " +
                            supportedApps.map { it.displayName }
                )

                // Consider offline only when all resolved sources used cache fallback.
                val resolvedSources = result.resolved.filter { it.patchFile != null }
                val isOffline = resolvedSources.isNotEmpty() && resolvedSources.all { it.isOffline }
                val displayVersion = firstResolved?.resolvedVersion
                val sourceName = if (result.resolved.size == 1) {
                    firstResolved?.source?.name ?: patchSourceManager.getActiveSourceName()
                } else {
                    val count = result.resolved.count { it.patchFile != null }
                    getPluralString(Res.plurals.count_sources, count, count)
                }

                latestResolvedApps = null // Invalidate eager-resolved apps cache

                // Track failed source IDs across resolve and load phases for error banner.
                val failedSourceIds = buildSet {
                    result.resolved.forEach { if (it.error != null) add(it.source.id) }
                    result.loaded.perSource.forEach { if (!it.isSuccess) add(it.sourceId) }
                }
                if (failedSourceIds.isNotEmpty()) {
                    // Log failure details for troubleshooting.
                    val details = buildList {
                        result.resolved.forEach { r -> r.error?.let { add("${r.source.name}: $it") } }
                        result.loaded.perSource.forEach { s ->
                            if (!s.isSuccess) {
                                val err = s.error
                                add("${s.sourceName}: ${err?.readableMessage() ?: "failed to load"}")
                                if (err != null) {
                                    Logger.error("Patch source '${s.sourceName}' failed to load", err)
                                }
                            }
                        }
                    }
                    Logger.warn("Some patch sources failed to load, ${details.joinToString("; ")}")
                }
                if (failedSourceIds != lastFailedSourceIds) {
                    sourcesFailedBannerDismissed = false
                    lastFailedSourceIds = failedSourceIds
                }

                _uiState.value = _uiState.value.copy(
                    isLoadingPatches = false,
                    isOffline = isOffline,
                    supportedApps = supportedApps,
                    patchesVersion = displayVersion,
                    patchesChannel = firstResolved?.channel,
                    patchSourceName = sourceName,
                    patchLoadError = null,
                    showSourcesFailedBanner = failedSourceIds.isNotEmpty() && !sourcesFailedBannerDismissed,
                    failedSourcesCount = failedSourceIds.size,
                    failedSourceIds = failedSourceIds,
                )
                // Sources just (re)resolved, so every card's update and version
                // state has to be worked out against them again.
                rebuildTrackedState(forceDeviceRefresh = false)
                reanalyzeSelectedApk()
                eagerlyResolveLatestApps() // Update info against latest patch versions
            } catch (e: CancellationException) {
                // Ignore normal coroutine cancellation so state is not overwritten.
                throw e
            } catch (e: Throwable) {
                // Catch Throwable (including LinkageError from newer patchers) to avoid hung loading.
                Logger.error("Failed to load patches and supported apps", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingPatches = false,
                    patchLoadError = humanizePatchLoadError(e),
                )
            }
        }
    }

    /**
     * The patched-app history, keyed by [PatchedAppRecord.trackingKey] so the
     * app's own build and any copies of it stay apart.
     */
    @Volatile
    private var recordsByKey: Map<String, PatchedAppRecord> = emptyMap()

    /** Per-record update guidance, recomputed whenever sources or history change. */
    @Volatile
    private var updateInfoByKey: Map<String, RecallUpdateInfo> = emptyMap()

    /** The last reading of the device, shared by every card built from it. */
    @Volatile
    private var trackedInstalls: TrackedInstallSnapshot = TrackedInstallSnapshot.None

    /** Cards the user hid, and supported versions they turned down. */
    @Volatile
    private var homeAppPrefs: HomeAppPrefs = HomeAppPrefs()

    private data class HomeAppPrefs(
        val hidden: Set<String> = emptySet(),
        val ignoredVersions: Map<String, String> = emptyMap(),
    )

    /** The patched-app record filed under [trackingKey], or null if there is none. */
    fun getPatchedRecord(trackingKey: String): PatchedAppRecord? = recordsByKey[trackingKey]

    // Supported apps parsed from the latest patches to preview upgrade targets.
    private var latestResolvedApps: List<SupportedApp>? = null

    /**
     * When a newer patch than the loaded one exists, resolve+download the latest
     * patches in the background, parse their supported app versions, and rebuild
     * the cards against them. So a card can show "App vX → vY" up front.
     * Best-effort. Failures keep the loaded-patch info.
     */
    private fun eagerlyResolveLatestApps() {
        val anyBehind = cachedSourcesResult?.resolved?.any {
            it.patchFile != null && it.resolvedVersion != null &&
                isNewerVersion(it.latestAvailableVersion ?: it.resolvedVersion, it.resolvedVersion)
        } == true
        if (!anyBehind || recordsByKey.isEmpty()) return
        viewModelScope.launch {
            try {
                val enabled = patchSourceManager.getEnabledRepositories()
                val result = EnabledSourcesLoader.loadAll(enabled, emptyMap(), engineConfigRepository.loadConfig().excludedMppPatterns)
                latestResolvedApps = withContext(Dispatchers.IO) {
                    SupportedAppCatalog.extractSupportedAppsFromMetadata(result.unionPatches)
                }
                rebuildTrackedState(forceDeviceRefresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.error("Eager latest-patch resolve failed", e)
            }
        }
    }

    /**
     * Re-reads the history and the device. Called when the home screen appears,
     * which is also where it lands after a patch or an install elsewhere in the
     * app, so the verdicts are taken again rather than trusted.
     */
    fun refreshHomeApps() = refreshTrackedState(forceDeviceRefresh = true)

    /**
     * Rebuilds every home card from the history, the resolved sources and the
     * device, on [viewModelScope]. This is the live-refresh path; a full patch
     * reload calls [rebuildTrackedState] directly because it is already inside a
     * coroutine of its own.
     */
    private fun refreshTrackedState(forceDeviceRefresh: Boolean = false) {
        viewModelScope.launch { rebuildTrackedState(forceDeviceRefresh) }
    }

    /**
     * One pass over everything a card is made of: the stored records, what the
     * sources say about them now, and what the device holds.
     *
     * Serialized, because a patch finishing and a device arriving can land within
     * a frame of each other and the second reading must not overtake the first.
     * Failures leave the previous cards standing rather than emptying the screen.
     */
    private suspend fun rebuildTrackedState(forceDeviceRefresh: Boolean) = trackedStateLock.withLock {
        try {
            val config = configRepository.loadConfig()
            homeAppPrefs = HomeAppPrefs(
                hidden = config.hiddenHomeApps.toSet(),
                ignoredVersions = config.ignoredSupportedVersions,
            )

            val records = patchedAppStore.getAll()
                .map { withResolvedInput(it) }
                .associateBy { it.trackingKey }
            recordsByKey = records

            val apps = latestResolvedApps ?: _uiState.value.supportedApps
            updateInfoByKey = records.values.associate { it.trackingKey to recallUpdateInfo(it, apps) }

            if (forceDeviceRefresh) trackedInstallResolver.invalidateAll()
            val device = DeviceMonitor.state.value.selectedDevice?.takeIf { it.isReady }
            trackedInstalls = trackedInstallResolver.resolve(
                records = records.values,
                deviceId = device?.id,
                morpheSignatureIds = morpheSignatureIds(),
            )

            val homeApps = buildHomeApps(records)
            _uiState.value = _uiState.value.copy(
                homeApps = homeApps,
                ignoredVersions = homeAppPrefs.ignoredVersions,
            )

            // Off the critical path: cards render their placeholder immediately and the
            // icon fades in once resolved. Bounded; the cards ask for the rest on demand.
            appIconRepository.prefetch(homeApps.map { it.packageName })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.error("Failed to rebuild the home app list", e)
        }
    }

    /**
     * One card per app the sources bring, plus one per further build of it, plus
     * one for every app only the history still knows about — patched through a
     * source that has since been removed, or with universal patches no supported
     * app list mentions. Dropping those would put a patched app out of reach of
     * the actions that manage it.
     */
    private fun buildHomeApps(records: Map<String, PatchedAppRecord>): List<HomeAppItem> {
        val appsByPackage = _uiState.value.supportedApps.associateBy { it.packageName }
        val recordsByPackage = records.values.groupBy { it.packageName }
        val packages = appsByPackage.keys + recordsByPackage.keys
        return packages
            .flatMap { pkg -> homeAppSlots(pkg, recordsByPackage[pkg].orEmpty()) }
            .map { slot -> buildHomeApp(slot, appsByPackage[slot.packageName]) }
    }

    private fun buildHomeApp(slot: HomeAppSlot, app: SupportedApp?): HomeAppItem {
        val record = slot.record
        val install = record?.let { trackedInstalls.byTrackingKey[it.trackingKey] }
        val update = record?.let { updateInfoByKey[it.trackingKey] }
        val installedPackageName = record?.installedPackageName ?: slot.packageName

        // The newest version a rebuild would land on, staying in the channel the
        // app was patched on. Falls back to the source's recommendation for an
        // app Morphe has no build of.
        val supportedVersion = update?.appSuggestedVersion ?: app?.recommendedVersion

        // Live source data first, the record second: a card has to keep its name
        // and color once the source that declared them is gone.
        val displayName = app?.displayName?.takeIf { it.isNotBlank() }
            ?: record?.displayName?.takeIf { it.isNotBlank() }
            ?: SupportedApp.getDisplayName(slot.packageName)

        val deviceState = install?.deviceState ?: when {
            record != null -> DeviceInstallState.NO_DEVICE
            !trackedInstalls.deviceAttached -> DeviceInstallState.NO_DEVICE
            // Nothing was verified about a stock install and nothing needs to be:
            // it is on the device, and it is certainly not a build of Morphe's.
            installedPackageName in trackedInstalls.installedPackages -> DeviceInstallState.UNVERIFIED
            else -> DeviceInstallState.NOT_INSTALLED
        }
        val artifactState = install?.artifactState ?: PatchedArtifactState.PRESENT

        return HomeAppItem(
            id = slot.id,
            packageName = slot.packageName,
            installedPackageName = installedPackageName,
            displayName = displayName,
            version = record?.apkVersion ?: app?.recommendedVersion.orEmpty(),
            appIconColorHex = app?.appIconColor ?: record?.appIconColorHex,
            record = record,
            deviceState = deviceState,
            artifactState = artifactState,
            deviceVersion = install?.deviceVersion,
            deviceApkPath = install?.deviceApkPath,
            // A record with no verdict yet has not been judged, and must not be
            // presented as any of the states a judgement would have produced.
            isVerificationPending = record != null && install == null,
            hasPatchUpdate = update?.hasRelevantSourceUpdate == true,
            versionStatus = appVersionStatus(
                patchedVersion = record?.apkVersion,
                supportedVersion = supportedVersion,
                // Keyed by the package the sources know, which a renamed build
                // shares with the app it came from
                ignoredVersion = homeAppPrefs.ignoredVersions[slot.packageName],
            ),
            supportedVersion = supportedVersion,
            supportedApp = app,
            updateInfo = update,
            isHidden = slot.id in homeAppPrefs.hidden,
        )
    }

    /**
     * Answers the offer to rebuild [packageName] at [version], leaving later
     * versions to be offered on their own.
     */
    fun ignoreSupportedVersion(packageName: String, version: String) {
        viewModelScope.launch {
            configRepository.setIgnoredSupportedVersion(packageName, version)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Undoes [ignoreSupportedVersion], so whatever the sources support is offered again. */
    fun stopIgnoringSupportedVersion(packageName: String) {
        viewModelScope.launch {
            configRepository.setIgnoredSupportedVersion(packageName, null)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Hides or restores one card. The history behind it is never touched. */
    fun setAppHidden(id: String, hidden: Boolean) {
        viewModelScope.launch {
            configRepository.setHomeAppHidden(id, hidden)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Shows or hides the hidden cards for the rest of this session. */
    fun setShowHiddenApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showHiddenApps = show)
    }

    /**
     * The bundles that produced [record]'s build, named and counted for the app
     * information dialog.
     *
     * Patches are resolved to the names their bundle gives them where the source
     * is still around, and shown as recorded where it is not: a build patched by
     * a source the user has since removed still has to be able to say what went
     * into it. Pure map work over already-loaded data, so it costs a dialog
     * nothing to ask.
     */
    fun appliedBundles(record: PatchedAppRecord): List<AppliedBundle> {
        val patchesBySource = cachedSourcesResult?.patchesBySource.orEmpty()
        val sourceNames = patchSourceManager.allSources.value.associate { it.id to it.name }
        val snapshots = record.sourcesSnapshot.associateBy { it.sourceId }

        return record.patchSelectionByBundle
            .filterValues { it.isNotEmpty() }
            .map { (sourceId, selected) ->
                val snapshot = snapshots[sourceId]
                val patches = patchesBySource[sourceId]
                val namesByUniqueId = patches?.associate { it.uniqueId to it.name }.orEmpty()
                val resolved = selected.mapNotNull { namesByUniqueId[it] }.sorted()
                val unresolved = selected
                    .filterNot { it in namesByUniqueId }
                    // A unique id leads with the patch's own name, which reads far
                    // better than the whole id when its bundle cannot name it
                    .map { it.substringBefore('|') }
                    .sorted()
                AppliedBundle(
                    sourceId = sourceId,
                    title = sourceNames[sourceId]
                        ?: snapshot?.sourceName?.takeIf { it.isNotBlank() }
                        ?: sourceId,
                    version = snapshot?.version?.takeIf { it.isNotBlank() && it != "unknown" },
                    available = patches != null,
                    patchNames = resolved,
                    unresolvedNames = unresolved,
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Explicitly remove the record filed under [trackingKey] from the history and
     * refresh the cards. The only way a record leaves the store. We never
     * auto-delete. Touches no files. Re-patching the app recreates the record.
     */
    fun forgetPatchedApp(trackingKey: String) {
        // delete() emits a change → the store observer rebuilds the cards.
        viewModelScope.launch {
            deleteRecord(trackingKey)?.let { showError(getString(Res.string.home_forget_failed, it.message ?: "")) }
        }
    }

    /** Deletes the history record filed under [trackingKey]. Returns the failure if it couldn't be
     *  persisted (the record is then still there), or null on success. */
    private suspend fun deleteRecord(trackingKey: String): Exception? = try {
        patchedAppStore.delete(trackingKey)
        trackedInstallResolver.invalidate(trackingKey)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("Failed to remove $trackingKey from the patched-app history", e)
        e
    }

    /**
     * Per-source patch freshness plus app-version freshness for [record],
     * comparing the snapshot it was patched with against the currently resolved
     * sources and the supported app's recommended/experimental versions. The app
     * suggestion stays in the channel the user patched on (stable vs experimental).
     *
     * This is the one place update state is worked out. The card's rebuild badge,
     * the dialog's banner and its source rows all read the result rather than
     * each deciding for themselves what "outdated" means.
     */
    private suspend fun recallUpdateInfo(
        record: PatchedAppRecord,
        apps: List<SupportedApp>,
    ): RecallUpdateInfo {
        val resolvedBySource = resolvedVersionBySource()   // what Re-patch will use right now
        val latestBySource = latestAvailableBySource()     // newest available (may need downloading)
        val app = apps.find { it.packageName == record.packageName }
        val appNames = changelogAppNames(app?.displayName, record.displayName, record.packageName)
        val sources = record.sourcesSnapshot
            // Include only sources that provided patches for this record.
            .filter { snap ->
                val sel = record.patchSelectionByBundle[snap.sourceId]
                sel == null || sel.isNotEmpty()
            }
            .map { snap ->
                val latest = latestBySource[snap.sourceId]
                val outdated = isNewerVersion(latest, snap.version)
                RecallUpdateInfo.SourceUpdate(
                    sourceId = snap.sourceId,
                    name = snap.sourceName,
                    usedVersion = snap.version,
                    resolvedVersion = resolvedBySource[snap.sourceId],
                    latestAvailableVersion = latest,
                    outdated = outdated,
                    hasRelevantChanges = outdated && publishedChangesFor(snap, latest, appNames),
                )
            }
        val used = record.apkVersion
        val (suggested, channel) = suggestedAppVersion(app, used)
        val latestStable = app?.recommendedVersion
        // Supported if the patch targets any version (recommendedVersion null), or the
        // used version is in its stable/experimental lists. Unknown app → assume yes.
        val usedSupported = app == null || app.recommendedVersion == null ||
            app.supportedVersions.any { it.equals(used, ignoreCase = true) } ||
            app.experimentalVersions.any { it.equals(used, ignoreCase = true) }
        return RecallUpdateInfo(
            sources = sources,
            appUsedVersion = used,
            appChannel = channel,
            appSuggestedVersion = suggested,
            appOutdated = isNewerVersion(suggested, used),
            appUsedSupported = usedSupported,
            latestStableVersion = latestStable,
            stableUpdateAvailable = isNewerVersion(latestStable, used),
        )
    }

    /**
     * Whether a newer release of [snap]'s source actually says anything about this
     * app. A source that bumped its version without touching the app is not a
     * reason to tell the user to rebuild.
     *
     * Everything that leaves the question open answers yes: no resolved source to
     * read a changelog from, no changelog, or no name to match its scopes against.
     * Staying quiet about a real update is the worse failure.
     */
    private suspend fun publishedChangesFor(
        snap: PatchedAppRecord.PatchedSourceSnapshot,
        latest: String?,
        appNames: Set<String>,
    ): Boolean {
        val resolved = cachedSourcesResult?.resolved?.firstOrNull { it.source.id == snap.sourceId }
            ?: return true
        val prerelease = resolved.channel == PatchResolver.Channel.DEV_LATEST ||
            resolved.channel == PatchResolver.Channel.DEV_OLDER
        val relevant = changelogRepository.hasRelevantChanges(
            resolved.source.changelogRequest(sinceVersion = snap.version, appNames = appNames)
                .copy(prerelease = prerelease)
        )
        if (!relevant) {
            Logger.debug(
                "Changelog: '${snap.sourceName}' ${snap.version} -> $latest lists no scoped " +
                    "changes for ${appNames.first()} (tried ${appNames.joinToString(", ")}), no badge"
            )
        }
        return relevant
    }

    /** Determines the suggested app version to target, preserving the release channel previously used. */
    private fun suggestedAppVersion(
        app: SupportedApp?,
        used: String,
    ): Pair<String?, RecallUpdateInfo.AppChannel> {
        if (app == null) return null to RecallUpdateInfo.AppChannel.UNKNOWN
        val latestStable = app.recommendedVersion
        val latestExperimental = app.experimentalVersions.firstOrNull()
        val onExperimental = app.experimentalVersions.any { it.equals(used, ignoreCase = true) } ||
            (latestStable != null && isNewerVersion(used, latestStable))
        return if (onExperimental) {
            (latestExperimental ?: latestStable) to RecallUpdateInfo.AppChannel.EXPERIMENTAL
        } else {
            (latestStable ?: latestExperimental) to RecallUpdateInfo.AppChannel.STABLE
        }
    }

    /** source id → version currently resolved/downloaded (what Re-patch uses now).
     *  Keyed by the source's stable id, not its display name — compared against
     *  PatchedAppRecord.sourcesSnapshot, which persists across sessions, so a
     *  renameable name is the wrong join key here (see PatchPreferencesRepository's
     *  class doc for the general reasoning). */
    private fun resolvedVersionBySource(): Map<String, String?> =
        cachedSourcesResult?.resolved
            ?.filter { it.patchFile != null }
            ?.associate { it.source.id to it.resolvedVersion }
            ?: emptyMap()

    /** source id → newest available version (falls back to resolved when unknown/offline). */
    private fun latestAvailableBySource(): Map<String, String?> =
        cachedSourcesResult?.resolved
            ?.filter { it.patchFile != null }
            ?.associate { it.source.id to (it.latestAvailableVersion ?: it.resolvedVersion) }
            ?: emptyMap()
    /**
     * [record] with [PatchedAppRecord.inputApkPath] set to the original a repatch should read,
     * per [OriginalApkRepository.resolveInputApk] (the Morphe-managed copy first). Records saved
     * since archives became canonical already name it; this presents older ones, which still name
     * the user's own file, the same way, so every consumer (row, detail dialog, repatch) agrees.
     * Read-side only: nothing is persisted. When no original is usable the recorded path is kept
     * so the "not found" dialog can still show where it was.
     */
    private suspend fun withResolvedInput(record: PatchedAppRecord): PatchedAppRecord {
        val resolved = try {
            originalApkRepository.resolveInputApk(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warn("Could not resolve the original APK for ${record.packageName}: ${e.message}")
            null
        } ?: return record
        return if (resolved.absolutePath == record.inputApkPath) record else record.copy(inputApkPath = resolved.absolutePath)
    }

    /**
     * Resolves every enabled source to its latest release, downloading what is missing, without
     * touching the version prefs saved in settings. Returns the .mpp paths, the matching
     * source names and the matching source ids, all in the same order.
     */
    suspend fun resolvePatchFiles(
        onDownloadProgress: ((String, Float) -> Unit)? = null,
    ): Result<Triple<List<String>, List<String>, List<String>>> = try {
        val result = EnabledSourcesLoader.loadAll(
            patchSourceManager.getEnabledRepositories(),
            emptyMap(),
            engineConfigRepository.loadConfig().excludedMppPatterns,
            onDownloadProgress,
        )
        val resolvedOk = result.resolved.filter { it.patchFile != null }

        if (resolvedOk.isEmpty()) {
            Result.failure(PatchException("Could not resolve patch files", Res.string.home_could_not_resolve_patch_files))
        } else {
            Result.success(
                Triple(
                    resolvedOk.map { it.patchFile!!.absolutePath },
                    resolvedOk.map { it.source.name },
                    resolvedOk.map { it.source.id },
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("Failed to resolve patch files", e)
        Result.failure(e)
    }

    /** Returns signature IDs for default and custom keystores to identify Morphe-signed apps. */
    private suspend fun morpheSignatureIds(): Set<String> = keystoreService.getKnownSignatureIds()

    /** Returns the snapshot of the most recent multi-source load result. */
    fun getResolvedSourcesSnapshot(): EnabledSourcesLoader.Result? = cachedSourcesResult
    private var cachedSourcesResult: EnabledSourcesLoader.Result? = null

    /** Re-analyzes the selected APK against updated supported apps. */
    private suspend fun reanalyzeSelectedApk() {
        val file = _uiState.value.selectedApk ?: return
        val refreshed = withContext(Dispatchers.IO) { parseApkManifest(file) } ?: return
        _uiState.value = _uiState.value.copy(apkInfo = refreshed)
    }

    /** Retries loading patches and supported apps. */
    fun retryLoadPatches() {
        loadPatchesAndSupportedApps(forceRefresh = true)
    }

    /** Reloads patches if version preferences changed while on another screen. */
    fun refreshPatchesIfNeeded() {
        // A patch that just finished moved the selected APK into Morphe's managed storage,
        // so the selection would now point at a file that's gone.
        if (_uiState.value.selectedApk?.exists() == false) clearSelection()
        viewModelScope.launch {
            val saved = configRepository.getSourceVersionPrefs()
            if (saved != lastLoadedVersionsBySource) {
                Logger.info("Patches versions changed across sources: $lastLoadedVersionsBySource -> $saved, reloading...")
                loadPatchesAndSupportedApps(forceRefresh = true)
            }
        }
    }

    /** Returns the cached patches file. */
    fun getCachedPatchesFile(): File? = cachedPatchesFile

    fun onFileSelected(file: File) {
        viewModelScope.launch {
            Logger.info("File selected: ${file.absolutePath}")

            _uiState.value = _uiState.value.copy(isAnalyzing = true)

            val validationResult = withContext(Dispatchers.IO) {
                validateAndAnalyzeApk(file)
            }

            if (validationResult.isValid) {
                _uiState.value = _uiState.value.copy(
                    selectedApk = file,
                    apkInfo = validationResult.apkInfo,
                    error = null,
                    isReady = true,
                    isAnalyzing = false
                )
                Logger.info("APK analyzed successfully: ${validationResult.apkInfo?.appName ?: file.name}")
            } else {
                _uiState.value = _uiState.value.copy(
                    selectedApk = null,
                    apkInfo = null,
                    error = validationResult.getUserErrorMessage() ?: validationResult.errorMessage,
                    isReady = false,
                    isAnalyzing = false
                )
                Logger.warn("APK validation failed: ${validationResult.errorMessage}")
            }
        }
    }

    fun onFilesDropped(files: List<File>) {
        val apkFile = files.firstOrNull { BundleFormats.isApkOrBundle(it) }
        if (apkFile != null) {
            onFileSelected(apkFile)
        } else {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    error = getString(Res.string.error_drop_valid_apk),
                    isReady = false
                )
            }
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedApk = null,
            apkInfo = null,
            error = null,
            isDragHovering = false,
            isReady = false,
            isAnalyzing = false
        )
        Logger.info("APK selection cleared")
    }

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setDragHover(isHovering: Boolean) {
        _uiState.value = _uiState.value.copy(isDragHovering = isHovering)
    }

    private suspend fun validateAndAnalyzeApk(file: File): ApkValidationResult {
        if (!file.exists()) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "File does not exist: ${file.absolutePath}",
                errorRes = Res.string.home_validation_file_not_exist
            )
        }

        if (!file.isFile) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "Selected item is not a file: ${file.absolutePath}",
                errorRes = Res.string.home_validation_not_a_file
            )
        }

        if (!BundleFormats.isApkOrBundle(file)) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "Invalid APK file extension: ${file.name}",
                errorRes = Res.string.home_validation_invalid_extension
            )
        }

        if (file.length() < 1024) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "APK file is too small (${file.length()} bytes): ${file.name}",
                errorRes = Res.string.home_validation_file_too_small
            )
        }

        val apkInfo = parseApkManifest(file)

        return if (apkInfo != null) {
            ApkValidationResult(true, apkInfo = apkInfo)
        } else {
            ApkValidationResult(
                isValid = false,
                errorMessage = "Failed to parse APK manifest: ${file.name}",
                errorRes = Res.string.home_validation_parse_failed
            )
        }
    }

    /** Parses APK metadata directly from its manifest. */
    private suspend fun parseApkManifest(file: File): ApkInfo? {
        val inspection = ApkInspector.inspect(file) ?: run {
            Logger.warn(
                "Full APK manifest parse failed for ${file.name}. " +
                    "Falling back to limited-info mode (filename heuristics + fuzzy match)."
            )
            return parseApkManifestMinimal(file)
        }

        return try {
            val packageName = inspection.packageName
            val versionName = inspection.versionName ?: getString(Res.string.unknown)
            val versionCode = inspection.versionCode
            val minSdk = inspection.minSdkVersion

            val loadedApps = _uiState.value.supportedApps
            val dynamicSupportedApp = loadedApps.find { it.packageName == packageName }
            val isSupported = dynamicSupportedApp != null ||
                (loadedApps.isEmpty() && packageName in MorpheConstants.FALLBACK_PACKAGES)

            if (!isSupported) {
                Logger.warn("Unsupported package: $packageName, no compatible patches found")
            }

            // Prefer supported app display name, falling back to parsed label or package name.
            val appName = dynamicSupportedApp?.displayName
                ?: SupportedApp.resolveDisplayName(packageName, inspection.applicationLabel)

            val versionResolution = if (dynamicSupportedApp != null) {
                resolveVersionStatus(versionName, dynamicSupportedApp, versionCode)
            } else {
                VersionResolution(VersionStatus.UNKNOWN, null)
            }
            val suggestedVersion = versionResolution.suggestedVersion
            val versionStatus = versionResolution.status

            val architectures = inspection.architectures.toList()

            // TODO: Re-enable when checksums are provided via .mpp files
            val checksumStatus = ChecksumStatus.NotConfigured

            Logger.info(
                "Parsed APK: $packageName v${inspection.versionName ?: "unknown"}" +
                    (versionCode?.let { " build $it" } ?: "") +
                    " (recommended=$suggestedVersion, minSdk=$minSdk, archs=$architectures)"
            )

            ApkInfo(
                fileName = file.name,
                filePath = file.absolutePath,
                fileSize = file.length(),
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                architectures = architectures,
                minSdk = minSdk,
                suggestedVersion = suggestedVersion,
                versionStatus = versionStatus,
                checksumStatus = checksumStatus,
                isUnsupportedApp = !isSupported
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warn(
                "Full APK manifest parse failed for ${file.name}: ${e.message}. " +
                    "Falling back to limited-info mode (filename heuristics + fuzzy match)."
            )
            parseApkManifestMinimal(file)
        }
    }

    /** Fallback parser using filename heuristics and architecture inspection when full parse fails. */
    private suspend fun parseApkManifestMinimal(file: File): ApkInfo {
        val (packageFromName, versionFromName) = ApkOutputNaming.extractPackageAndVersionFromFilename(file.name)
        val supportedApps = _uiState.value.supportedApps

        // Match by exact package name first, falling back to fuzzy name matching.
        val matched = packageFromName
            ?.let { pkg -> supportedApps.firstOrNull { it.packageName == pkg } }
            ?: SupportedAppCatalog.fuzzyMatchApp(file.name, supportedApps)

        val packageName = packageFromName ?: matched?.packageName.orEmpty()
        val displayName = matched?.displayName
            ?: packageFromName?.substringAfterLast('.', "")
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension

        val versionResolution = if (matched != null && versionFromName != null) {
            resolveVersionStatus(versionFromName, matched)
        } else {
            VersionResolution(VersionStatus.UNKNOWN, null)
        }

        val architectures = ApkInspector.extractArchitectures(file).toList()

        Logger.info(
            "Limited-info parse for ${file.name}: package=$packageName, " +
                "version=${versionFromName ?: "unknown"}, matched=${matched?.displayName ?: "none"}"
        )

        return ApkInfo(
            fileName = file.name,
            filePath = file.absolutePath,
            fileSize = file.length(),
            appName = displayName,
            packageName = packageName,
            versionName = versionFromName ?: getString(Res.string.unknown),
            architectures = architectures,
            minSdk = null,
            suggestedVersion = versionResolution.suggestedVersion,
            versionStatus = versionResolution.status,
            checksumStatus = ChecksumStatus.NotConfigured,
            isUnsupportedApp = matched == null,
            hasLimitedInfo = true,
        )
    }

    // TODO: Re-enable checksum verification when checksums are provided via .mpp files
    // private fun verifyChecksum(
    //     file: File, packageName: String, version: String,
    //     architectures: List<String>, recommendedVersion: String?
    // ): app.morphe.gui.util.ChecksumStatus { ... }

}

/** Update guidance for a patched app comparing against resolved patch sources and app versions. */
data class RecallUpdateInfo(
    val sources: List<SourceUpdate>,
    val appUsedVersion: String,
    val appChannel: AppChannel,
    /** Latest version in [appChannel], or null if unknown. */
    val appSuggestedVersion: String?,
    val appOutdated: Boolean,
    /** Whether the patched app version is still supported by the evaluated patch. */
    val appUsedSupported: Boolean = true,
    /** Latest stable app version the evaluated patch supports, if any. */
    val latestStableVersion: String? = null,
    /** Whether a newer stable version exists than what was patched. */
    val stableUpdateAvailable: Boolean = false,
) {
    /**
     * Whether any source that patched this app has published changes to it since.
     * The single condition behind the card's rebuild badge and the dialog's patch
     * update banner — a version bump that says nothing about this app is not one.
     */
    val hasRelevantSourceUpdate: Boolean get() = sources.any { it.hasRelevantChanges }

    data class SourceUpdate(
        /** Stable source id, which outlives a rename of the source's display name. */
        val sourceId: String,
        val name: String,
        /** Version recorded when this app was patched. */
        val usedVersion: String,
        val resolvedVersion: String?,
        /** Newest available version. */
        val latestAvailableVersion: String?,
        /** True if [latestAvailableVersion] is newer than [usedVersion]. */
        val outdated: Boolean,
        /**
         * True when that newer release actually lists changes for this app, per
         * its changelog. Everything that leaves the question open counts as yes.
         */
        val hasRelevantChanges: Boolean = false,
    )

    enum class AppChannel { STABLE, EXPERIMENTAL, UNKNOWN }
}

data class HomeUiState(
    val selectedApk: File? = null,
    val apkInfo: ApkInfo? = null,
    val error: String? = null,
    val isDragHovering: Boolean = false,
    val isReady: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isLoadingPatches: Boolean = true,
    val isOffline: Boolean = false,
    val isDefaultSource: Boolean = true,
    val supportedApps: List<SupportedApp> = emptyList(),
    /**
     * Every home card, resolved. The one semantic state the app cards and the
     * installed-app information dialog both read.
     */
    val homeApps: List<HomeAppItem> = emptyList(),
    val appListFilter: AppListFilter = AppListFilter.ALL,
    val sortMode: HomeAppSortMode = HomeAppSortMode.RECOMMENDED,
    /** Whether hidden cards are being shown for the rest of this session. */
    val showHiddenApps: Boolean = false,
    /**
     * Supported versions the user turned down, by original package. Exposed so
     * the information dialog can offer to take the refusal back only while the
     * version it answered is still the one on offer.
     */
    val ignoredVersions: Map<String, String> = emptyMap(),
    /** Tracking key of the app being installed to the device from its patched APK. */
    val installingApp: String? = null,
    /** Tracking key of the app being uninstalled from the device. */
    val uninstallingApp: String? = null,
    val patchesVersion: String? = null,
    val patchesChannel: PatchResolver.Channel? = null,
    val patchSourceName: String? = null,
    val patchLoadError: String? = null,
    val updateInfo: UpdateInfo? = null,
    val dismissedUpdateVersion: String? = null,
    val updateBannerSessionDismissed: Boolean = false,
    /** True if the multi-source intro hint should be displayed. */
    val showMultiSourceHint: Boolean = false,
    /** True if at least one patch source failed to load while others succeeded. */
    val showSourcesFailedBanner: Boolean = false,
    /** Count of sources that failed to load. */
    val failedSourcesCount: Int = 0,
    /** Source IDs that failed to load. */
    val failedSourceIds: Set<String> = emptySet(),
) {
    /** True if an update is available and has not been dismissed for the session or version. */
    val showUpdateBanner: Boolean
        get() = updateInfo != null &&
                updateInfo.latestVersion != dismissedUpdateVersion &&
                !updateBannerSessionDismissed

}

data class ApkInfo(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int? = null,
    val architectures: List<String> = emptyList(),
    val minSdk: Int? = null,
    val suggestedVersion: String? = null,
    val versionStatus: VersionStatus = VersionStatus.UNKNOWN,
    val checksumStatus: ChecksumStatus = ChecksumStatus.NotConfigured,
    val isUnsupportedApp: Boolean = false,
    /** True if manifest parsing fell back to heuristics, producing approximate details. */
    val hasLimitedInfo: Boolean = false,
)

data class ApkValidationResult(
    val isValid: Boolean,
    val apkInfo: ApkInfo? = null,
    val errorMessage: String? = null,
    val errorRes: StringResource? = null,
    val errorArgs: List<Any> = emptyList(),
) {
    suspend fun getUserErrorMessage(): String? {
        val res = errorRes ?: return errorMessage
        return getString(res, *errorArgs.toTypedArray())
    }
}
