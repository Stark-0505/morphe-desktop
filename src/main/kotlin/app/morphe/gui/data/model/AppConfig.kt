/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.model

import app.morphe.gui.ui.theme.ThemePreference
import kotlinx.serialization.Serializable

/** Application configuration stored in config.json. */

val DEFAULT_PATCH_SOURCE = PatchSource(
    id = "morphe-default",
    name = "Morphe Patches",
    type = PatchSourceType.DEFAULT,
    url = "https://github.com/MorpheApp/morphe-patches",
    deletable = false
)

/** Release tracking mode for a patch source. */
@Serializable
enum class FollowMode { FOLLOW_STABLE, FOLLOW_DEV, PINNED }

/** Version tracking preference and optional pinned release tag for a patch source. */
@Serializable
data class SourceVersionPref(
    val mode: FollowMode,
    val pinnedTag: String? = null,
)

@Serializable
data class AppConfig(
    val language: String = "system",
    val themePreference: String = ThemePreference.SYSTEM.name,
    val backgroundType: String = "CIRCLES",
    val enableParallax: Boolean = true,
    val customAccentColorArgb: Int? = null,
    val lastCliVersion: String? = null,
    /** Legacy single-source version pin kept for config migration. */
    val lastPatchesVersion: String? = null,
    /** Legacy per-source version pin map kept for config migration. */
    val lastPatchesVersionBySource: Map<String, String> = emptyMap(),
    /** Release tracking preference per patch source ID. */
    val sourceVersionPrefs: Map<String, SourceVersionPref> = emptyMap(),
    val sourceChannelFlagsSeeded: Boolean = false,
    /**
     * The universal app card color mode, as an [AppCardColorMode] name. Stored as a string, like
     * [themePreference], so an unreadable value degrades to the default instead of failing the
     * whole config load. Read it through [getAppCardColorMode].
     */
    val appCardColorMode: String = AppCardColorMode.DEFAULT.name,
    /**
     * The four app card colors the editor keeps, encoded by
     * [AppCardColorDefaults.encodeColorValues]. Kept for every mode, so switching modes and back
     * does not discard what was picked. Read it through [getAppCardColorValues].
     */
    val customAppCardColors: String = "",
    /**
     * LEGACY global card fill, from before app card colors became a semantic configuration.
     * Migrated once into [appCardColorMode] / [customAppCardColors] by
     * [ConfigRepository.migrateCardFills], then left alone. Do not read directly.
     */
    val globalCardFill: MorpheFill? = null,
    /**
     * LEGACY per-app card fills. App card colors are one universal configuration now, so these
     * have nowhere to migrate to and are dropped on the first save. Do not read.
     */
    val cardFills: Map<String, MorpheFill> = emptyMap(),
    val useSharpCorners: Boolean = false,
    val groupPatchesByCategory: Boolean = true,
    val homeAppSortMode: String = "RECOMMENDED",
    val preferredPatchChannel: String = "STABLE",
    val useSimplifiedMode: Boolean = true,
    val patchSource: List<PatchSource> = listOf(DEFAULT_PATCH_SOURCE),
    val activePatchSourceId: String = "morphe-default",
    // Persisted expand/collapse state for settings sections.
    val collapsibleSectionStates: Map<String, Boolean> = emptyMap(),
    // Latest version for which update notification was dismissed.
    val dismissedUpdateVersion: String? = null,
    val updateChannelPreference: String? = null,
    // True if the update channel was explicitly chosen in settings.
    val userDidChooseUpdateChannel: Boolean = false,
    // Dismissal flag for multi-source introduction banner.
    val multiSourceHintDismissed: Boolean = false,
    // Last viewed apps filter tab.
    val homeAppListFilter: String = "ALL",
    // Home cards the user has hidden, by HomeAppItem id (the package a build
    // installs under, so a renamed build can be hidden without hiding the app it
    // came from). Hidden cards stay in the history and are reachable through the
    // hidden-apps toggle. Stored as a list so the config stays plain JSON.
    val hiddenHomeApps: List<String> = emptyList(),
    // Supported app versions the user turned down, keyed by original package.
    // The rebuild offer stays away for that version only, so a later one brings
    // it back on its own. See HomeViewModel.ignoreSupportedVersion.
    val ignoredSupportedVersions: Map<String, String> = emptyMap(),
    // Whether a successful patch moves the pre-patch (original) APK into Morphe's
    // managed storage, where it becomes the canonical input for later repatches
    // instead of the file the user picked — see OriginalApkRepository. When OFF the
    // user's file is left where it is. Default ON, matching morphe-manager's own
    // default: the archive only grows with actual use (one entry per package,
    // replaced on a new version), and losing the ability to repatch without
    // re-locating the original file is a worse default than the disk cost.
    val saveOriginalApks: Boolean = true,
) {

    fun getUpdateChannelPreference(): UpdateChannelPreference? {
        val raw = updateChannelPreference ?: return null
        return try {
            UpdateChannelPreference.valueOf(raw)
        } catch (_: Exception) {
            null
        }
    }
    /** The stored app card color mode, or [AppCardColorMode.DEFAULT] when it does not name one. */
    fun getAppCardColorMode(): AppCardColorMode = try {
        AppCardColorMode.valueOf(appCardColorMode)
    } catch (e: IllegalArgumentException) {
        AppCardColorMode.DEFAULT
    }

    /** The stored app card colors, decoded. Blank decodes to all-empty, which means "unset". */
    fun getAppCardColorValues(): AppCardColorValues =
        AppCardColorDefaults.decodeColorValues(customAppCardColors)

    fun getThemePreference(): ThemePreference {
        if (themePreference == "PURE_BLACK") return ThemePreference.AMOLED
        return try {
            ThemePreference.valueOf(themePreference)
        } catch (_: Exception) {
            ThemePreference.SYSTEM
        }
    }
}

@Serializable
data class PatchSource (
    val id: String,
    val name: String,
    val type: PatchSourceType,
    val url: String? = null,
    val filePath: String? = null,
    val deletable: Boolean = true,
    val enabled: Boolean = true,
    val usePreRelease: Boolean = false,
    val useExperimentalVersions: Boolean = false,
)

@Serializable
enum class PatchSourceType{
    DEFAULT, GITHUB, GITLAB, LOCAL
}

/** Release channel preference for update notifications. */
enum class UpdateChannelPreference {
    STABLE,
    DEV,
    /** Disable update checking. */
    OFF,
}
