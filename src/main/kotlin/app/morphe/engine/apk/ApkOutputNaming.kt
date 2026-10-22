/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.apk

import app.morphe.engine.MorpheData
import app.morphe.engine.patches.PatchBundleLoader
import java.io.File

/** Output path and filename helpers for patched APKs. */
object ApkOutputNaming {

    private val patchesVersionRegex = Regex("""(\d+\.\d+\.\d+(?:-dev\.\d+)?)""")

    /** Extracts version from an APKMirror-style filename (e.g. `pkg_1.2.3-4.apk` -> `1.2.3`). */
    fun extractApkVersionFromFilename(fileName: String): String? = try {
        val withoutExt = stripKnownExtension(fileName)
        val afterPackage = withoutExt.substringAfter("_")
        afterPackage.substringBefore("-").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Extracts package and version from an APKMirror-style filename, with regex fallbacks. */
    fun extractPackageAndVersionFromFilename(fileName: String): Pair<String?, String?> = try {
        val withoutExt = stripKnownExtension(fileName)
        val splitOnUnderscore = withoutExt.split('_', limit = 2)

        val packageCandidate = splitOnUnderscore.getOrNull(0)
        val afterUnderscore = splitOnUnderscore.getOrNull(1)

        val looksLikePackage = packageCandidate != null &&
            packageCandidate.contains('.') &&
            packageCandidate.split('.').all { segment ->
                segment.isNotEmpty() && segment.all { c -> c.isLowerCase() || c.isDigit() || c == '_' }
            }

        val packageName = if (looksLikePackage) packageCandidate else null

        val versionAfterUnderscore = afterUnderscore?.substringBefore('-')?.takeIf { it.isNotBlank() }
        val version = versionAfterUnderscore
            ?: Regex("""\d+\.\d+\.\d+(?:-dev\.\d+)?""").find(withoutExt)?.value
            ?: Regex("""\d+\.\d+(?:\.\d+)?""").find(withoutExt)?.value

        packageName to version
    } catch (_: Exception) {
        null to null
    }

    private fun stripKnownExtension(fileName: String): String {
        for (ext in BundleFormats.SUPPORTED_EXTENSIONS) {
            if (fileName.endsWith(".$ext", ignoreCase = true)) {
                return fileName.dropLast(ext.length + 1)
            }
        }
        return fileName.substringBeforeLast('.')
    }

    /** Extracts version from a `.mpp` filename (e.g. `morphe-patches-1.13.0.mpp` -> `1.13.0`). */
    fun extractPatchesVersion(patchesFileName: String): String? =
        patchesVersionRegex.find(patchesFileName)?.groupValues?.get(1)

    /**
     * Reads literal app label from the APK manifest.
     * Returns null if missing or defined as a resource reference (`@string/...`).
     */
    fun resolveAppDisplayName(apkFile: File): String? =
        ApkInspector.inspect(apkFile)?.applicationLabel?.takeIf { it.isNotBlank() }

    /** Reads `versionName` from the APK or bundle manifest. */
    fun resolveAppVersion(apkFile: File): String? =
        ApkInspector.inspect(apkFile)?.versionName?.takeIf { it.isNotBlank() }

    /**
     * Compute the unified output APK path. Layout:
     * `<base>/<appName>/<appName>-{apkVer}-patches-{patchesVer}.apk`
     *
     * - Per-app subfolder prevents collisions when patching different APK
     *   versions of the same package
     * - Both versions encoded in the filename so the output is self-describing
     * - `patchesFile` is optional; if null, no `-patches-{ver}` suffix is added
     *
     * @param inputApk       the APK being patched. Only used to derive the app
     *                       folder name and version when they aren't supplied. Its
     *                       location never decides where the output goes.
     * @param patchesFile    primary `.mpp` file. Used only for the suffix —
     *                       in multi-source mode pass any one of the bundles.
     * @param baseOutputDir  an explicitly configured output directory (e.g. the
     *                       GUI's default output directory). When null, Morphe's own
     *                       [MorpheData.patchedApksDir] is used, never the input
     *                       APK's folder.
     * @param appDisplayName Pre-resolved app label (e.g. "Youtube"). If null,
     *                       falls back to the input APK's filename without
     *                       extension. GUI callers pass the value from their
     *                       apkInfo; the CLI can call [resolveAppDisplayName]
     *                       to populate this.
     */
    fun outputApkPath(
        inputApk: File,
        patchesFile: File? = null,
        baseOutputDir: File? = null,
        appDisplayName: String? = null,
        appVersion: String? = null,
    ): File {
        val appFolderName = (appDisplayName ?: inputApk.nameWithoutExtension)
            .replace(" ", "-")
        val base = baseOutputDir ?: MorpheData.patchedApksDir
        val outputDir = File(base, appFolderName).also { it.mkdirs() }
        // Prefer caller-provided version, then manifest, then filename fallback.
        val version = sanitizeForFilename(
            appVersion?.takeIf { it.isUsableVersion() }
                ?: resolveAppVersion(inputApk)
                ?: extractApkVersionFromFilename(inputApk.name)
                ?: "patched"
        )
        val patchesVersion = patchesFile?.let { PatchBundleLoader.extractVersion(it) }
            ?: patchesFile?.name?.let { extractPatchesVersion(it) }
        val patchesSuffix = if (patchesVersion != null) "-patches-$patchesVersion" else ""
        return File(outputDir, "${appFolderName}-${version}${patchesSuffix}.apk")
    }

    private fun String.isUsableVersion(): Boolean =
        isNotBlank() && !equals("unknown", ignoreCase = true)

    private fun sanitizeForFilename(v: String): String =
        v.trim().replace(Regex("""[^A-Za-z0-9.\-_]"""), "-").ifBlank { "patched" }
}
