/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.MorpheData
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import app.morphe.engine.util.PersistenceFailures
import app.morphe.engine.util.isDevTag
import app.morphe.gui.data.model.AppConfig
import app.morphe.gui.data.model.DEFAULT_PATCH_SOURCE
import app.morphe.gui.data.model.FollowMode
import app.morphe.gui.data.model.SourceVersionPref
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.UpdateChannelPreference
import app.morphe.gui.data.model.MorpheFill
import app.morphe.gui.data.model.AppCardColorDefaults
import app.morphe.gui.data.model.AppCardColorMode
import app.morphe.gui.data.model.AppCardColorValues
import app.morphe.gui.ui.theme.ThemePreference
import app.morphe.gui.util.toHexString
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Repository for managing app configuration (config.json)
 */
open class ConfigRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "#kind"
    }

    private var cachedConfig: AppConfig? = null

    /** Set when an unreadable config.json could not be moved aside: saving would destroy it. */
    @Volatile
    private var persistenceBlocked = false

    /**
     * Load config from file, or return default if not exists.
     */
    open suspend fun loadConfig(): AppConfig = withContext(Dispatchers.IO) {
        cachedConfig?.let { return@withContext it }

        // One-time migration from the legacy per-OS app-data path to the
        // unified morphe-data location. Runs once and is a no-op thereafter.
        ConfigMigration.runIfNeeded()

        val configFile = MorpheData.configFile

        try {
            if (configFile.exists()) {
                val content = configFile.readText()
                val config = migrateCardFills(json.decodeFromString<AppConfig>(content))
                cachedConfig = config
                Logger.info("Config loaded from ${configFile.absolutePath}")
                config
            } else {
                Logger.info("No config file found, using defaults")
                val default = AppConfig()
                saveConfig(default)
                default
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Corrupt/incompatible config: every patch source, preference and saved
            // choice lives in this one file, so silently overwriting it with defaults
            // on the next save would be the worst possible answer to a read failure.
            // Move it aside first — same recovery contract as PatchedAppStore and
            // OriginalApkRepository — so whatever is still readable in it survives
            // for the user or a future migration to recover from.
            val preserved = runCatching { AtomicFiles.quarantine(configFile) }.getOrNull()
            if (preserved == null) {
                // Starting from defaults is fine for this session, but saving would now overwrite
                // an unreadable file we could not preserve. Block writes instead (see saveConfig).
                persistenceBlocked = true
            }
            Logger.error(
                if (preserved != null) {
                    "Failed to load config (${e.message}); preserved it as ${preserved.name} and starting from defaults"
                } else {
                    "Failed to load config (${e.message}); could not preserve the original, starting from defaults"
                },
                e,
            )
            AppConfig()
        }
    }

    /**
     * Save config to file.
     */
    /**
     * Persists [config], merged into the shared `config.json`. Returns true only if it is on disk.
     *
     * On failure it returns false and reports to [PersistenceFailures] (which the app surfaces to
     * the user) instead of throwing, because many fire-and-forget settings changes call this and
     * an uncaught exception there would take down a UI coroutine. Either way the failure is never
     * silent, and the cached config only advances after a successful write, so the in-memory
     * settings never claim something disk does not have.
     *
     * An unreadable existing file is moved aside first (never overwritten); if that is impossible
     * nothing is written. Cancellation propagates.
     */
    suspend fun saveConfig(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            if (persistenceBlocked) {
                throw java.io.IOException("config.json is unreadable and could not be preserved, so it is not being overwritten")
            }
            val configFile = MorpheData.configFile
            val existingObj = if (configFile.exists()) {
                try {
                    json.parseToJsonElement(configFile.readText()).jsonObject
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Merging into nothing would erase the engine-owned keys that share this file.
                    val preserved = try {
                        AtomicFiles.quarantine(configFile)
                    } catch (q: java.io.IOException) {
                        persistenceBlocked = true
                        throw q
                    }
                    Logger.warn("config.json was unreadable (${e.message}); preserved it as ${preserved.name}")
                    JsonObject(emptyMap())
                }
            } else JsonObject(emptyMap())

            val guiObj = json.encodeToJsonElement(AppConfig.serializer(), config).jsonObject
            val mergedMap = existingObj.toMutableMap()
            guiObj.forEach { (k, v) -> mergedMap[k] = v }
            val mergedJson = json.encodeToString(JsonObject.serializer(), JsonObject(mergedMap))

            // config.json is shared with EngineConfigRepository, so the GUI keys are merged into
            // what is already there. Written atomically: a crash mid-write must never leave this
            // file — the source of every setting Morphe has — truncated or unreadable.
            configFile.parentFile?.mkdirs()
            AtomicFiles.write(configFile, mergedJson)
            cachedConfig = config
            Logger.info("Config saved to ${configFile.absolutePath}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PersistenceFailures.report("settings", e)
            false
        }
    }

    /**
     * Update language preference.
     */
    /** Whether the original APK of a patched app is kept in Morphe's managed storage. Returns whether it was saved. */
    suspend fun setSaveOriginalApks(enabled: Boolean): Boolean {
        val current = loadConfig()
        return saveConfig(current.copy(saveOriginalApks = enabled))
    }

    suspend fun setLanguage(language: String) {
        val current = loadConfig()
        saveConfig(current.copy(language = language))
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        val current = loadConfig()
        saveConfig(current.copy(themePreference = theme.name))
    }

    suspend fun setUseSharpCorners(enabled: Boolean) {
        val current = loadConfig()
        saveConfig(current.copy(useSharpCorners = enabled))
    }

    suspend fun setHomeAppSortMode(mode: String) {
        val current = loadConfig()
        saveConfig(current.copy(homeAppSortMode = mode))
    }

    /**
     * Persists the whole app card color configuration in one write: the mode together with all
     * four colors. The colors are kept even for [AppCardColorMode.DEFAULT], so switching modes
     * back and forth does not discard them.
     *
     * This is the only entry point that writes app card colors. The editor holds a draft and
     * calls it once on Save, rather than writing each movement of the picker.
     */
    suspend fun setAppCardColors(mode: AppCardColorMode, values: AppCardColorValues) {
        val current = loadConfig()
        saveConfig(
            current.copy(
                appCardColorMode = mode.name,
                customAppCardColors = AppCardColorDefaults.encodeColorValues(values),
            )
        )
    }

    /**
     * Moves a config written before app card colors became a universal configuration onto the
     * new fields, once.
     *
     * The old global fill maps cleanly: a solid becomes [AppCardColorMode.SOLID], a gradient's
     * outer and middle stops become the three stops of [AppCardColorMode.GRADIENT], and the
     * accent fill becomes [AppCardColorMode.ACCENT]. Per-app fills have nowhere to go — colors
     * are one choice for every card now — so they are dropped rather than guessed at.
     *
     * A config that already carries a mode or colors is left alone, so this cannot overwrite a
     * choice made since upgrading.
     */
    private fun migrateCardFills(config: AppConfig): AppConfig {
        val legacy = config.globalCardFill
        if (legacy == null && config.cardFills.isEmpty()) return config

        val alreadyConfigured = config.appCardColorMode != AppCardColorMode.DEFAULT.name ||
            config.customAppCardColors.isNotBlank()

        val migrated = if (alreadyConfigured) {
            config
        } else {
            when (legacy) {
                is MorpheFill.Accent -> config.copy(appCardColorMode = AppCardColorMode.ACCENT.name)

                is MorpheFill.Solid -> config.copy(
                    appCardColorMode = AppCardColorMode.SOLID.name,
                    customAppCardColors = AppCardColorDefaults.encodeColorValues(
                        AppCardColorValues(solidHex = Color(legacy.argb).toHexString())
                    ),
                )

                is MorpheFill.Gradient -> {
                    val ordered = legacy.stops.sortedBy { it.position }
                    if (ordered.isEmpty()) {
                        config
                    } else {
                        val start = Color(ordered.first().argb)
                        val middle = Color(ordered[ordered.size / 2].argb)
                        val end = Color(ordered.last().argb)
                        config.copy(
                            appCardColorMode = AppCardColorMode.GRADIENT.name,
                            customAppCardColors = AppCardColorDefaults.encodeColorValues(
                                AppCardColorValues(
                                    startHex = start.toHexString(),
                                    middleHex = middle.toHexString(),
                                    endHex = end.toHexString(),
                                    solidHex = middle.toHexString(),
                                )
                            ),
                        )
                    }
                }

                // An image fill was never something a card could resolve per app, and there is
                // no color to carry over from one
                else -> config
            }
        }

        Logger.info("Migrated legacy card fills to the universal app card color configuration")
        return migrated.copy(globalCardFill = null, cardFills = emptyMap())
    }

    suspend fun setBackgroundType(type: String) {
        val current = loadConfig()
        saveConfig(current.copy(backgroundType = type))
    }

    suspend fun setEnableParallax(enabled: Boolean) {
        val current = loadConfig()
        saveConfig(current.copy(enableParallax = enabled))
    }
    
    suspend fun setCustomAccentColorArgb(color: Int?) {
        val current = loadConfig()
        saveConfig(current.copy(customAccentColorArgb = color))
    }

    suspend fun setGroupPatchesByCategory(enabled: Boolean) {
        val current = loadConfig()
        saveConfig(current.copy(groupPatchesByCategory = enabled))
    }

    /** Sets the version tracking preference for a patch source. */
    suspend fun setSourceVersionPref(sourceId: String, pref: SourceVersionPref) {
        val current = loadConfig()
        saveConfig(current.copy(sourceVersionPrefs = current.sourceVersionPrefs + (sourceId to pref)))
    }

    /** Returns per-source version preferences, migrating legacy fields on first read. */
    suspend fun getSourceVersionPrefs(): Map<String, SourceVersionPref> {
        val current = loadConfig()
        if (current.sourceVersionPrefs.isNotEmpty()) return current.sourceVersionPrefs

        val legacyTags: Map<String, String> = when {
            current.lastPatchesVersionBySource.isNotEmpty() -> current.lastPatchesVersionBySource
            current.lastPatchesVersion != null -> mapOf(DEFAULT_PATCH_SOURCE.id to current.lastPatchesVersion)
            else -> emptyMap()
        }
        if (legacyTags.isEmpty()) return emptyMap()

        val migrated = legacyTags.mapValues { (_, tag) ->
            val mode = if (tag.isDevTag()) FollowMode.FOLLOW_DEV else FollowMode.FOLLOW_STABLE
            SourceVersionPref(mode = mode)
        }
        saveConfig(current.copy(sourceVersionPrefs = migrated))
        return migrated
    }

    suspend fun migrateSourceChannelFlags() {
        val prefs = getSourceVersionPrefs()
        val config = loadConfig()
        if (config.sourceChannelFlagsSeeded) return

        val seeded = config.patchSource.map { source ->
            val wantsDev = prefs[source.id].followsPreRelease()
            if (source.usePreRelease == wantsDev) source else source.copy(usePreRelease = wantsDev)
        }
        saveConfig(config.copy(patchSource = seeded, sourceChannelFlagsSeeded = true))
        Logger.info("Seeded source pre-release flags: ${seeded.filter { it.usePreRelease }.map { it.name }}")
    }

    /** Records the update version dismissed by the user, or null to clear. */
    suspend fun setDismissedUpdateVersion(version: String?) {
        val current = loadConfig()
        saveConfig(current.copy(dismissedUpdateVersion = version))
    }

    /** Persists explicit update channel preference and clears prior dismissal. */
    suspend fun setUpdateChannelPreference(pref: UpdateChannelPreference) {
        val current = loadConfig()
        saveConfig(
            current.copy(
                updateChannelPreference = pref.name,
                userDidChooseUpdateChannel = true,
                dismissedUpdateVersion = null,
            )
        )
    }

    /** Returns active update channel preference, deriving from current build if unset. */
    suspend fun getOrInitUpdateChannelPreference(currentVersion: String): UpdateChannelPreference {
        val current = loadConfig()
        val saved = current.getUpdateChannelPreference()
        if (current.userDidChooseUpdateChannel && saved != null) {
            return saved
        }
        val derived = if (currentVersion.contains("dev")) {
            UpdateChannelPreference.DEV
        } else {
            UpdateChannelPreference.STABLE
        }
        if (saved != derived) {
            saveConfig(current.copy(updateChannelPreference = derived.name))
        }
        return derived
    }

    suspend fun setUseSimplifiedMode(enabled: Boolean) {
        val current = loadConfig()
        saveConfig(current.copy(useSimplifiedMode = enabled))
    }

    /** Persists the expanded state of a settings section. */
    suspend fun setCollapsibleSectionExpanded(id: String, expanded: Boolean) {
        val current = loadConfig()
        val updated = current.collapsibleSectionStates + (id to expanded)
        saveConfig(current.copy(collapsibleSectionStates = updated))
    }

    /** Returns the currently active patch source. */
    suspend fun getActivePatchSource(): PatchSource {
        val config = loadConfig()
        return config.patchSource.find { it.id == config.activePatchSourceId }
            ?: DEFAULT_PATCH_SOURCE
    }

    /** Sets the active patch source by ID. */
    suspend fun setActivePatchSource(id: String) {
        val current = loadConfig()
        if (current.patchSource.any { it.id == id }) {
            saveConfig(current.copy(activePatchSourceId = id))
        }
    }

    /** Adds a new patch source to the configuration. */
    suspend fun addPatchSource(source: PatchSource) {
        val current = loadConfig()
        val updated = current.copy(patchSource = current.patchSource + source)
        saveConfig(updated)
    }

    /** Updates an existing patch source. */
    suspend fun updatePatchSource(updated: PatchSource) {
        val current = loadConfig()
        val existing = current.patchSource.find { it.id == updated.id }
        if (existing == null) return

        val newSource = if (!existing.deletable) {
            existing.copy(
                usePreRelease = updated.usePreRelease,
                useExperimentalVersions = updated.useExperimentalVersions
            )
        } else {
            updated
        }

        val updatedSources = current.patchSource.map { if (it.id == updated.id) newSource else it }
        saveConfig(current.copy(patchSource = updatedSources))
    }

    /** Persists reordered patch sources if [orderedIds] matches current sources. */
    suspend fun reorderPatchSources(orderedIds: List<String>) {
        val current = loadConfig()
        val byId = current.patchSource.associateBy { it.id }
        if (orderedIds.size != current.patchSource.size || orderedIds.toSet() != byId.keys) return
        val reordered = orderedIds.map { byId.getValue(it) }
        saveConfig(current.copy(patchSource = reordered))
    }

    /** Marks multi-source introduction banner as dismissed. */
    suspend fun setMultiSourceHintDismissed() {
        val current = loadConfig()
        if (current.multiSourceHintDismissed) return
        saveConfig(current.copy(multiSourceHintDismissed = true))
    }

    /** Persists selected home apps filter tab. */
    suspend fun setHomeAppListFilter(value: String) {
        val current = loadConfig()
        if (current.homeAppListFilter == value) return
        saveConfig(current.copy(homeAppListFilter = value))
    }

    /**
     * Hide or unhide one home card, by its [HomeAppItem][app.morphe.gui.ui.screens.home.HomeAppItem]
     * id. Hiding never touches the patched-app history: the card comes back
     * exactly as it was.
     */
    suspend fun setHomeAppHidden(id: String, hidden: Boolean) {
        val current = loadConfig()
        val updated = if (hidden) current.hiddenHomeApps + id else current.hiddenHomeApps - id
        if (updated.toSet() == current.hiddenHomeApps.toSet()) return
        saveConfig(current.copy(hiddenHomeApps = updated.distinct()))
    }

    /**
     * Answer the offer to rebuild [packageName] at [version], leaving later
     * versions to be offered. A null [version] takes the refusal back.
     */
    suspend fun setIgnoredSupportedVersion(packageName: String, version: String?) {
        val current = loadConfig()
        val updated = if (version == null) {
            current.ignoredSupportedVersions - packageName
        } else {
            current.ignoredSupportedVersions + (packageName to version)
        }
        if (updated == current.ignoredSupportedVersions) return
        saveConfig(current.copy(ignoredSupportedVersions = updated))
    }

    /** Toggles patch source enablement, ensuring at least one source remains active. */
    suspend fun setPatchSourceEnabled(id: String, enabled: Boolean) {
        val current = loadConfig()
        val updatedSources = current.patchSource.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        val anyEnabled = updatedSources.any { it.enabled }
        val finalSources = if (!anyEnabled) {
            updatedSources.map {
                if (it.id == DEFAULT_PATCH_SOURCE.id) it.copy(enabled = true) else it
            }
        } else {
            updatedSources
        }
        saveConfig(current.copy(patchSource = finalSources))
    }

    /** Removes a patch source by ID if deletable. */
    suspend fun removePatchSource(id: String) {
        val current = loadConfig()
        val source = current.patchSource.find { it.id == id }
        if (source == null || !source.deletable) return

        val updatedSources = current.patchSource.filter { it.id != id }
        val newActiveId = if (current.activePatchSourceId == id) {
            DEFAULT_PATCH_SOURCE.id
        } else {
            current.activePatchSourceId
        }
        saveConfig(current.copy(patchSource = updatedSources, activePatchSourceId = newActiveId))
    }
}

internal fun SourceVersionPref?.followsPreRelease(): Boolean = when (this?.mode) {
    FollowMode.FOLLOW_DEV -> true
    FollowMode.PINNED -> pinnedTag.isDevTag()
    else -> false
}
