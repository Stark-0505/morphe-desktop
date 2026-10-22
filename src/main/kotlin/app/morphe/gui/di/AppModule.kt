/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.di

import app.morphe.engine.OriginalApkRepository
import app.morphe.engine.PatchedAppStore
import app.morphe.engine.config.EngineConfigRepository
import app.morphe.engine.network.HttpService
import app.morphe.engine.network.createHttpClient
import app.morphe.engine.options.PatchPreferencesRepository
import app.morphe.engine.util.KeystoreService
import app.morphe.engine.util.Logger
import app.morphe.gui.data.appicon.AppIconRepository
import app.morphe.gui.data.repository.AvatarRepository
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.data.repository.LanguageRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.data.repository.SeenPatchesRepository
import app.morphe.gui.data.repository.SourceMuteRepository
import app.morphe.gui.data.repository.UpdateCheckRepository
import app.morphe.gui.ui.screens.home.HomeViewModel
import app.morphe.gui.ui.screens.patches.PatchSelectionViewModel
import app.morphe.gui.ui.screens.patches.PatchesViewModel
import app.morphe.gui.ui.screens.patching.PatchingViewModel
import app.morphe.gui.ui.screens.quick.QuickPatchViewModel
import app.morphe.gui.util.DeviceMonitor
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Dependency injection module bindings. */
val appModule = module {

    single {
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    single {
        createHttpClient(json = get()) { message ->
            Logger.debug("HTTP: $message")
        }
    }

    single { EngineConfigRepository.shared }
    single { KeystoreService.shared }
    single { ConfigRepository() }
    single { LanguageRepository() }
    single { PatchPreferencesRepository() }
    single { SeenPatchesRepository() }
    single { SourceMuteRepository() }
    single { PatchSourceManager(get(), get(), get(), get(), get()) }
    single { UpdateCheckRepository(get()) }
    // Typed lookup: ChangelogRepository has a secondary (HttpClient) constructor, so an
    // untyped get() would be ambiguous between it and the primary fetcher constructor.
    single { ChangelogRepository(get<HttpClient>()) }
    single { AvatarRepository(get()) }
    // One instance, so in-flight lookups and the decoded-icon cache are shared by every
    // surface (home cards, the info dialog, Quick Patch) and by HomeViewModel's prefetch.
    single { AppIconRepository(HttpService(get())) }
    single { PatchedAppStore.shared }
    // The app has exactly one AdbManager, owned by DeviceMonitor (it's the thing actually
    // running the daemon-ownership/polling logic) and shared from here rather than
    // constructed fresh — see DeviceMonitor.adbManager's doc for why a second instance
    // would silently desync from the first.
    single { DeviceMonitor.adbManager }
    // The GUI is the only caller that archives originals (the CLI never moves a user's
    // input file); PatchEngine reaches this instance through HistoryMetadata.
    single {
        val configRepository = get<ConfigRepository>()
        val patchedAppStore = get<PatchedAppStore>()
        OriginalApkRepository(
            isRetentionEnabled = { configRepository.loadConfig().saveOriginalApks },
            // An archive a history record still points at is never pruned as "superseded".
            referencedInputPaths = { patchedAppStore.getAll().map { it.inputApkPath }.toSet() },
        )
    }

    viewModel {
        HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    viewModel {
        // seenPatchesRepository explicit, not left to its default, for the same reason
        // PatchSelectionViewModel's factory (below) already is: a defaulted
        // SeenPatchesRepository() here would be a second, un-injected instance racing
        // the shared one on a concurrent load-mutate-save cycle.
        QuickPatchViewModel(
            get(), get(), get(),
            seenPatchesRepository = get(),
            engineConfigRepository = get(),
            originalApkRepository = get(),
        )
    }
    viewModel { params ->
        val psm = get<PatchSourceManager>()
        PatchesViewModel(
            params.get(),
            psm.getActiveRepositorySync(),
            get(),
            psm.getLocalFilePath(),
            psm
        )
    }
    viewModel { params ->
        val psm = get<PatchSourceManager>()
        PatchSelectionViewModel(
            params.get(),
            params.get(),
            params.get(),
            params.get(),
            params.get(),
            psm.getActiveRepositorySync(),
            get(),
            get(),
            psm.getLocalFilePath(),
            params.get(),
            params.get(),
            params.get(),
            params.get(),
            params.get(),
            params.get(),
            // Explicit, not left to its default: SeenPatchesRepository keeps an
            // in-memory cache and its own mutex, both scoped to the instance,
            // not the file — a second, un-injected instance (what the default
            // `SeenPatchesRepository()` would silently create here, every time
            // this factory runs) can race the shared one on a concurrent
            // load-mutate-save cycle and lose a write, the same way AdbManager's
            // missing Koin wiring desynced its daemon-ownership state.
            seenPatchesRepository = get(),
            originalApkRepository = get(),
        )
    }
    viewModel { params ->
        PatchingViewModel(
            params.get(),
            get(),
        )
    }
}
