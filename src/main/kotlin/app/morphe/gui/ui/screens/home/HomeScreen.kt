/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.gui.LocalNavController
import app.morphe.gui.PatchSelectionParams
import app.morphe.gui.PatchSelectionScreenRoute
import app.morphe.engine.options.PatchPreferencesRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.navigateComplex
import app.morphe.gui.ui.components.MorpheErrorBar
import app.morphe.gui.ui.components.SourceManagementSheet
import app.morphe.gui.ui.screens.home.components.ForgetConfirmDialog
import app.morphe.gui.ui.screens.home.components.FullScreenDropZone
import app.morphe.gui.ui.screens.home.components.HeaderBar
import app.morphe.gui.ui.screens.home.components.HomeAppsPane
import app.morphe.gui.ui.screens.home.components.HomeBanners
import app.morphe.gui.ui.screens.home.components.MiddleContent
import app.morphe.gui.ui.screens.home.components.RepatchMissingApkDialog
import app.morphe.gui.ui.screens.home.components.UninstallConfirmDialog
import app.morphe.gui.ui.screens.home.components.VersionWarningDialog
import app.morphe.gui.ui.screens.home.components.handleContinue
import app.morphe.gui.ui.screens.home.components.openFilePicker
import app.morphe.gui.util.revealInFileManager
import app.morphe.gui.util.PatchException
import app.morphe.gui.util.getUserMessage
import app.morphe.gui.util.humanizePatchLoadError
import app.morphe.gui.util.sourceChannelMap
import app.morphe.gui.util.sourceErrorMap
import app.morphe.gui.util.sourcePatchCountMap
import app.morphe.gui.util.sourceUpdateAvailableMap
import app.morphe.gui.util.sourceVersionMap
import app.morphe.morphe_desktop.generated.resources.*
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import app.morphe.engine.util.PersistenceFailures
import kotlinx.coroutines.CancellationException
import java.io.IOException

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel()
) {
    HomeScreenContent(viewModel = viewModel)
}

@Composable
fun HomeScreenContent(
    viewModel: HomeViewModel
) {
    val navController = LocalNavController.current
    val uiState by viewModel.uiState.collectAsState()

    // The device is polled, not streamed, and the screen is where the app lands
    // after patching or installing elsewhere, so the verdicts are taken again here.
    LaunchedEffect(Unit) { viewModel.refreshHomeApps() }

    val coroutineScope = rememberCoroutineScope()
    val patchSourceManager: PatchSourceManager = koinInject()
    val patchPreferencesRepository: PatchPreferencesRepository = koinInject()
    val allSources by patchSourceManager.allSources.collectAsState()

    var showSourceManagementSheet by rememberSaveable { mutableStateOf(false) }

    // Navigates to patch selection using resolved patch bundles.
    fun launchPatch(
        record: PatchedAppRecord,
        apkPath: String,
        patchFilePaths: List<String>,
        sourceNames: List<String>,
        sourceIds: List<String>,
    ) {
        if (patchFilePaths.isEmpty()) return
        navController.navigateComplex(
            PatchSelectionScreenRoute,
            PatchSelectionParams(
                apkPath = apkPath,
                apkName = record.displayName,
                patchesFilePath = patchFilePaths.first(),
                packageName = record.packageName,
                patchesFilePaths = patchFilePaths,
                patchSourceNames = sourceNames,
                patchSourceIds = sourceIds,
                initialSelectionByBundle = record.patchSelectionByBundle,
                initialPatchOptions = record.patchOptionValues,
                apkVersion = record.apkVersion,
            )
        )
    }

    // Set when a record's original input APK is gone; the dialog it drives asks for a replacement
    // APK and then repatches with the record's saved selection.
    var repatchMissingRecord by remember { mutableStateOf<PatchedAppRecord?>(null) }

    fun repatchWithApk(record: PatchedAppRecord, apkPath: String) {
        launchPatch(
            record, apkPath,
            viewModel.getAllResolvedPatchFiles().map { it.absolutePath },
            viewModel.getAllResolvedPatchSourceNames(),
            viewModel.getAllResolvedPatchSourceIds(),
        )
    }
    val onRepatch: (String) -> Unit = onRepatch@{ trackingKey ->
        val record = viewModel.getPatchedRecord(trackingKey) ?: return@onRepatch
        // HomeViewModel hands out records whose input path is already resolved to the
        // Morphe-managed original when one exists, so this never prefers a stale copy in
        // the user's own folders. Only when nothing usable is left do we ask for the APK.
        if (File(record.inputApkPath).exists()) {
            repatchWithApk(record, record.inputApkPath)
        } else {
            repatchMissingRecord = record
        }
    }

    var forgetConfirm by remember { mutableStateOf<PatchedAppRecord?>(null) }
    val onForget: (String) -> Unit = { key -> forgetConfirm = viewModel.getPatchedRecord(key) }
    forgetConfirm?.let { record ->
        ForgetConfirmDialog(
            record = record,
            onDismiss = { forgetConfirm = null },
            onConfirm = {
                viewModel.forgetPatchedApp(record.trackingKey)
                forgetConfirm = null
            },
        )
    }

    var uninstallConfirm by remember { mutableStateOf<PatchedAppRecord?>(null) }
    var uninstallAlsoForget by remember { mutableStateOf(false) }
    val onUninstall: (String) -> Unit = { key ->
        uninstallAlsoForget = false
        uninstallConfirm = viewModel.getPatchedRecord(key)
    }
    uninstallConfirm?.let { record ->
        UninstallConfirmDialog(
            record = record,
            alsoForget = uninstallAlsoForget,
            onAlsoForgetChange = { uninstallAlsoForget = it },
            onDismiss = { uninstallConfirm = null },
            onConfirm = {
                viewModel.uninstallPatchedApp(record.trackingKey, alsoForget = uninstallAlsoForget)
                uninstallConfirm = null
            },
        )
    }

    repatchMissingRecord?.let { record ->
        RepatchMissingApkDialog(
            record = record,
            onDismiss = { repatchMissingRecord = null },
            onApkPicked = { path -> repatchWithApk(record, path) },
        )
    }

    // Tapping a patched card opens the app information dialog. The dialog is
    // addressed by id and its item is re-read from the live list on every
    // recomposition, so a device connecting, a patch finishing or an external
    // uninstall updates what is open rather than leaving a stale snapshot up.
    var detailId by remember { mutableStateOf<String?>(null) }
    val detailItem = detailId?.let { id -> uiState.homeApps.firstOrNull { it.id == id } }

    // The dialog closes on its own if its record leaves the history — "Forget"
    // from inside the dialog would otherwise leave it open over nothing.
    LaunchedEffect(detailId, detailItem) {
        if (detailId != null && detailItem == null) detailId = null
    }

    detailItem?.let { item ->
        val record = item.record ?: return@let
        // Pure map work over data already in memory, keyed so it is redone whenever
        // the record, the loaded sources (a rename invalidates the bundle titles
        // this maps to), or the supported-app list actually change. allSources was
        // missing from this key: renaming a source in SourceDetailsDialog while this
        // dialog was open behind it left the old name memoized here indefinitely.
        val appliedBundles = remember(record, uiState.supportedApps, allSources) {
            viewModel.appliedBundles(record)
        }
        var mutedSourceIds by remember(item.id) { mutableStateOf<Set<String>>(emptySet()) }
        LaunchedEffect(item.id) {
            mutedSourceIds = patchSourceManager.mutedSourceIdsForApp(record.packageName)
        }

        InstalledAppInfoDialog(
            item = item,
            appliedBundles = appliedBundles,
            installing = uiState.installingApp == item.id,
            uninstalling = uiState.uninstallingApp == item.id,
            mutedSourceIds = mutedSourceIds,
            onDismiss = { detailId = null },
            onPatch = { detailId = null; onRepatch(item.id) },
            onUpdate = {
                coroutineScope.launch {
                    // The record is handed out with its input path already resolved to the
                    // Morphe-managed original when there is one; only ask for the APK again
                    // when nothing usable is left.
                    if (!File(record.inputApkPath).exists()) {
                        detailId = null
                        repatchMissingRecord = record
                        return@launch
                    }
                    // Every enabled source resolves to its latest release, without touching
                    // the version pins saved in settings.
                    viewModel.resolvePatchFiles()
                        .onSuccess { (files, names, ids) ->
                            detailId = null
                            launchPatch(record, record.inputApkPath, files, names, ids)
                        }
                        .onFailure {
                            viewModel.showError(
                                (it as? PatchException)?.getUserMessage() ?: humanizePatchLoadError(it)
                            )
                        }
                }
            },
            onInstall = { viewModel.installPatchedApp(item.id) },
            onUninstall = { detailId = null; onUninstall(item.id) },
            onForget = { detailId = null; onForget(item.id) },
            onOpenFolder = {
                coroutineScope.launch { revealInFileManager(File(record.outputApkPath).parentFile) }
            },
            onResetSelections = {
                coroutineScope.launch {
                    // Clears saved patch choices for this app across every source — the
                    // next patch of it starts from each bundle's own .mpp defaults rather
                    // than whatever was picked last time. Does not touch the patched
                    // history record itself (that's "Forget", a separate action) or any
                    // file on disk — just the remembered selection.
                    try {
                        patchPreferencesRepository.resetForApp(record.packageName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        PersistenceFailures.report("the reset of this app's saved patch selections", e)
                    }
                }
            },
            onToggleHidden = { viewModel.setAppHidden(item.id, !item.isHidden) },
            onToggleSourceMute = { sourceId ->
                coroutineScope.launch {
                    // record.patchSelectionByBundle's keys are exactly the sources that
                    // applied a patch to this app — real per-app coverage, not just "every
                    // enabled source" — so the precise appsToKeepFrom-based last-source
                    // protection in muteSourceForApp applies rather than its coarser fallback.
                    try {
                        if (sourceId in mutedSourceIds) {
                            patchSourceManager.unmuteSourceForApp(record.packageName, sourceId)
                        } else {
                            patchSourceManager.muteSourceForApp(
                                packageName = record.packageName,
                                sourceId = sourceId,
                                coveredByThisApp = record.patchSelectionByBundle.keys,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        PersistenceFailures.report("this source's mute setting", e)
                    }
                    // Re-read what is really stored, so the switch can't show a change that was not saved.
                    mutedSourceIds = patchSourceManager.mutedSourceIdsForApp(record.packageName)
                }
            },
            // Offered only while there is a newer supported version to turn down,
            // and taken back only while the one turned down is still on offer
            onIgnoreVersion = item.versionStatus
                ?.takeIf { item.showsVersionBadge }
                ?.let { status ->
                    { viewModel.ignoreSupportedVersion(item.packageName, status.supportedVersion) }
                },
            onStopIgnoringVersion = item.supportedVersion
                ?.takeIf { supported ->
                    uiState.ignoredVersions[item.packageName] == supported
                }
                ?.let { { viewModel.stopIgnoringSupportedVersion(item.packageName) } },
        )
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry) {
        viewModel.refreshPatchesIfNeeded()
    }

    if (showSourceManagementSheet) {
        val snapshot = viewModel.getResolvedSourcesSnapshot()
        SourceManagementSheet(
            sources = allSources,
            sourceVersions = snapshot.sourceVersionMap(),
            sourceChannels = snapshot.sourceChannelMap(),
            sourceErrors = snapshot.sourceErrorMap(),
            sourcePatchCounts = snapshot.sourcePatchCountMap(),
            sourceUpdateAvailable = snapshot.sourceUpdateAvailableMap(),
            isLoading = uiState.isLoadingPatches,
            onToggleEnabled = { id, enabled ->
                coroutineScope.launch {
                    patchSourceManager.setSourceEnabled(id, enabled)
                    // Reload patches to update available apps and version badges.
                    viewModel.retryLoadPatches()
                }
            },
            onAdd = { source ->
                coroutineScope.launch { patchSourceManager.addSource(source) }
            },
            onEdit = { updated ->
                coroutineScope.launch { patchSourceManager.updateSource(updated) }
            },
            onRemove = { id ->
                coroutineScope.launch { patchSourceManager.removeSource(id) }
            },
            onReorder = { orderedIds ->
                coroutineScope.launch {
                    patchSourceManager.reorderSources(orderedIds)
                    // Reload patches to reflect updated source priority.
                    viewModel.retryLoadPatches()
                }
            },
            onDismiss = { showSourceManagementSheet = false },
            onRefresh = { viewModel.retryLoadPatches() },
            enabled = !uiState.isAnalyzing,
        )
    }

    FullScreenDropZone(
        onDragHoverChange = { viewModel.setDragHover(it) },
        onFilesDropped = { viewModel.onFilesDropped(it) },
        enabled = !uiState.isAnalyzing
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val padding = 24.dp

            var showVersionWarningDialog by remember { mutableStateOf(false) }

            if (showVersionWarningDialog && uiState.apkInfo != null) {
                VersionWarningDialog(
                    versionStatus = uiState.apkInfo!!.versionStatus,
                    currentVersion = uiState.apkInfo!!.versionName,
                    suggestedVersion = uiState.apkInfo!!.suggestedVersion ?: "",
                    onConfirm = {
                        showVersionWarningDialog = false
                        val patchesFile = viewModel.getCachedPatchesFile()
                        if (patchesFile != null) {
                            navController.navigateComplex(
                                PatchSelectionScreenRoute,
                                PatchSelectionParams(
                                    apkPath = uiState.apkInfo!!.filePath,
                                    apkName = uiState.apkInfo!!.appName,
                                    patchesFilePath = patchesFile.absolutePath,
                                    packageName = uiState.apkInfo!!.packageName,
                                    apkArchitectures = uiState.apkInfo!!.architectures,
                                    apkVersion = uiState.apkInfo!!.versionName,
                                    patchesFilePaths = viewModel.getAllResolvedPatchFiles().map { it.absolutePath },
                                    patchSourceNames = viewModel.getAllResolvedPatchSourceNames(),
                                    patchSourceIds = viewModel.getAllResolvedPatchSourceIds(),
                                )
                            )
                        }
                    },
                    onDismiss = { showVersionWarningDialog = false }
                )
            }

            val patchesLoaded = !uiState.isLoadingPatches && viewModel.getCachedPatchesFile() != null
            val onRetry: () -> Unit = { viewModel.retryLoadPatches() }
            val onClearClick: () -> Unit = { viewModel.clearSelection() }
            val onChangeClick: () -> Unit = {
                coroutineScope.launch {
                    openFilePicker()?.let { file ->
                        viewModel.onFileSelected(file)
                    }
                }
            }
            val onContinueClick: () -> Unit = {
                handleContinue(uiState, viewModel, navController) {
                    showVersionWarningDialog = true
                }
            }

            val resolvedSnapshot = viewModel.getResolvedSourcesSnapshot()
//            val versionsBySource: Map<String, String?> = resolvedSnapshot
//                ?.resolved
//                ?.associate { it.source.id to it.resolvedVersion }
//                ?: emptyMap()
            // Source names whose patches target the currently-selected APK's package.
            // Used by ApkInfoCard's "FROM" row to surface multi-source provenance.
            val selectedPackageName = uiState.apkInfo?.packageName
            val patchSourcesForSelectedApk: List<String> = remember(resolvedSnapshot, allSources, selectedPackageName) {
                val snapshot = resolvedSnapshot
                if (selectedPackageName == null || snapshot == null) {
                    emptyList()
                } else {
                    snapshot.patchesBySource.entries
                        .filter { (_, patches) ->
                            patches.any { p -> p.compatiblePackages.any { it.name == selectedPackageName } }
                        }
                        .mapNotNull { (sourceId, _) ->
                            allSources.firstOrNull { it.id == sourceId }?.name
                        }
                }
            }

            // Per-package source attribution map used by the supported-apps cards.
            // Walks every patch of every source, so it is recomputed only when the loaded
            // snapshot or the source list changes — not on every recomposition — and each card
            // just looks up its own list.
            val sourceNamesByPackage: Map<String, List<String>> = remember(resolvedSnapshot, allSources) {
                if (resolvedSnapshot == null) {
                    emptyMap()
                } else {
                    val sourceIdToName = allSources.associate { it.id to it.name }
                    val accum = mutableMapOf<String, MutableList<String>>()
                    resolvedSnapshot.patchesBySource.forEach { (sourceId, patches) ->
                        val name = sourceIdToName[sourceId] ?: return@forEach
                        val packages = patches.flatMap { it.compatiblePackages.map { p -> p.name } }
                            .filter { it.isNotBlank() }
                            .toSet()
                        packages.forEach { pkg ->
                            accum.getOrPut(pkg) { mutableListOf() }.add(name)
                        }
                    }
                    accum
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    HeaderBar(
                        uiState = uiState,
                        onRetry = onRetry,
                        onUpdateChannelChanged = { viewModel.refreshUpdateCheck() },
                        onManageSourcesClick = { showSourceManagementSheet = true },
                        sources = allSources,
                    )

                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            HomeBanners(
                                uiState = uiState,
                                onDismissUpdateSession = { viewModel.dismissUpdateForSession() },
                                onDismissUpdateVersion = { viewModel.dismissUpdateForVersion() },
                                onDismissMultiSourceHint = { viewModel.dismissMultiSourceHint() },
                                onManageSources = { showSourceManagementSheet = true },
                                onDismissSourcesFailed = { viewModel.dismissSourcesFailedBanner() },
                            )
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    // Small cute padding for small cute space
                                    // between the HeaderBar's bottom
                                    // divider and the actual body section.
                                    .padding(
                                        start = 10.dp,
                                        end = padding,
                                        top = 4.dp,
                                        bottom = padding,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                            val bodyViewport = this.maxHeight
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(padding),
                                verticalAlignment = Alignment.Top,
                            ) {
                                // Left: browse/discover apps (wizard step 1). One card
                                // per app, patched or not, over the same semantic state
                                // the information dialog reads.
                                HomeAppsPane(
                                    apps = uiState.homeApps,
                                    filter = uiState.appListFilter,
                                    onFilterChange = { viewModel.setAppListFilter(it) },
                                    sortMode = uiState.sortMode,
                                    onSortModeChange = { viewModel.setSortMode(it) },
                                    showHidden = uiState.showHiddenApps,
                                    onShowHiddenChange = { viewModel.setShowHiddenApps(it) },
                                    sourceNamesByPackage = sourceNamesByPackage,
                                    isLoading = uiState.isLoadingPatches,
                                    loadError = uiState.patchLoadError,
                                    onRetry = onRetry,
                                    onManageSources = { showSourceManagementSheet = true },
                                    onOpenInfo = { detailId = it.id },
                                    onToggleHidden = { viewModel.setAppHidden(it.id, !it.isHidden) },
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .heightIn(max = bodyViewport),
                                )
                                // Right: APK info / drop zone (wizard step 2, pick the
                                // APK you want patched). Content centers vertically when
                                // it fits, scrolls when it doesn't, so the CONTINUE
                                // button is never clipped off the bottom.
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .align(Alignment.CenterVertically)
                                        .heightIn(max = bodyViewport)
                                        .padding(top = 16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    MiddleContent(
                                        uiState = uiState,
                                        patchesLoaded = patchesLoaded,
                                        onClearClick = onClearClick,
                                        onChangeClick = onChangeClick,
                                        onContinueClick = onContinueClick,
                                        patchSourceNames = patchSourcesForSelectedApk,
                                    )
                                }
                            }
                            }
                    }
                }

                // Custom error notification bar replacing Material3 Snackbar.
                uiState.error?.let { error ->
                    MorpheErrorBar(
                        message = error,
                        onDismiss = { viewModel.clearError() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    )
                }

            }
        }
    }
}
