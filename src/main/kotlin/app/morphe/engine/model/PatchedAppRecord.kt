/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.model

import kotlinx.serialization.Serializable

/**
 * A record of one app the user has patched, the "recall" data behind the
 * patched-app history (see `patched-app-recall-plan.md`).
 *
 * Written by both the CLI and the GUI on a successful patch (the store lives in
 * the shared engine layer so the two pipelines feed one history), and read back
 * to surface "you've patched this before / an update is available" UX.
 *
 * Identified by [id], not by [packageName]. One app can be patched into several
 * installs of its own — a rename patch gives a build a package name it does not
 * share with the original — and all of them key their bundle data off the
 * original [packageName]. Keying the history off the package would let the
 * second build overwrite the first.
 */
@Serializable
data class PatchedAppRecord(
    /**
     * Stable identity of this record, which is the package the build installs
     * under. Blank only in records written before the field existed;
     * [trackingKey] is what every reader should use, and
     * `PatchedAppStore` fills this in on migration.
     */
    val id: String = "",
    /** The original (pre-patch) package name, which is what bundle data is keyed by. */
    val packageName: String,
    /** Package name after patching if renamed by a patch. Null if unchanged. */
    val currentPackageName: String? = null,
    val displayName: String,
    /**
     * The `appIconColor` the patch bundle declared for this app at patch time,
     * retained so a card keeps its color once the source that declared it is
     * gone. Null when the bundle declared none.
     */
    val appIconColorHex: String? = null,
    val apkVersion: String,
    val apkVersionCode: Int? = null,

    /**
     * The original (pre-patch) APK a repatch reads. For records saved since Morphe took over
     * the original, this is the Morphe-managed copy under `morphe-data/original-apks/`.
     * Older records, and patches made with original-APK retention off or archiving failed,
     * hold the user's own path, which may no longer exist. Resolve through
     * `OriginalApkRepository.resolveInputApk` rather than trusting this directly.
     */
    val inputApkPath: String,
    /**
     * SHA-256 of the exact original APK this record was patched from, so a repatch can prove the
     * file it is about to read is that APK and not a different build that merely shares its
     * package and version name. Null on records written before it existed; those fall back to
     * version-based resolution (see `OriginalApkRepository.resolveInputApk`).
     */
    val inputApkSha256: String? = null,
    val outputApkPath: String,

    /** SHA-256 hash of the output APK to detect external changes. */
    val outputApkSha256: String? = null,
    val outputApkSize: Long = 0,

    /** Source ID to set of enabled patch IDs. */
    val patchSelectionByBundle: Map<String, Set<String>> = emptyMap(),
    /** Map of `"patchName.optionKey"` to option value string. */
    val patchOptionValues: Map<String, String> = emptyMap(),

    /**
     * Which sources + versions were enabled at patch time. "Update available"
     * detection compares current source versions against this snapshot, and the
     * app information dialog names the source from it once the source itself is
     * gone.
     */
    val sourcesSnapshot: List<PatchedSourceSnapshot> = emptyList(),

    val patchedAt: Long,
    val patchedWithMorpheVersion: String,
) {
    val installedPackageName: String get() = currentPackageName?.takeIf { it.isNotBlank() } ?: packageName

    /**
     * What this record is filed under. Falls back to the installed package so a
     * record written before [id] existed still resolves to the same key the
     * migration assigns it.
     */
    val trackingKey: String get() = id.takeIf { it.isNotBlank() } ?: installedPackageName

    /** Whether a patch renamed the package. */
    val isRenamed: Boolean get() = installedPackageName != packageName

    /** Total patches applied across every bundle that contributed to this build. */
    val appliedPatchCount: Int get() = patchSelectionByBundle.values.sumOf { it.size }

    @Serializable
    data class PatchedSourceSnapshot(
        val sourceId: String,
        val sourceName: String,
        val version: String,
    )
}
