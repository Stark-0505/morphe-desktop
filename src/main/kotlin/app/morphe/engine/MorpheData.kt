/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.util.Logger
import java.io.File

/**
 * Single source of truth for where Morphe stores its runtime data on disk.
 *
 * **Primary location**: a `morphe-data/` folder created **next to the running
 * JAR**. Survives upgrades (multiple JAR versions side-by-side share one
 * folder), trivially findable for users (just `cd` to where the JAR lives),
 * portable across drives/USB sticks.
 *
 * **Fallback location**: `~/morphe/`. Used when the primary path is
 * unreachable — most commonly when running from an IDE (`./gradlew run`)
 * where the "JAR location" resolves to a `build/classes/` directory that
 * would get wiped by `./gradlew clean`. Also covers the (rare) case of a
 * read-only JAR install location.
 *
 * Layout once populated (portable install):
 * ```
 * morphe-desktop-<version>-all.jar
 * patched_apks/{appName}/                        # default output for patched APKs (user-facing)
 * morphe-data/
 *   patches/{owner}-{repo}/v1.5.0__patches.mpp   # downloaded .mpp files
 *   logs/                                        # app logs
 *   avatars/{urlHash}.avatar                     # cached patch-source owner avatars
 *   icons/{packageName}/                         # user-created custom app icons (persistent)
 *   original-apks/                               # Morphe-managed original APKs, used for repatching
 *   config.json                                  # GUI preferences + sources
 *   tmp/patching-{timestamp}/                    # per-session patcher scratch
 *   morphe.keystore                              # shared default signing key
 * ```
 *
 * A single shared keystore is intentional: Android refuses updates whose
 * signatures don't match the installed app, so per-app or per-output-APK
 * keystores would break "re-patch and reinstall over the old version." A
 * user who wants their own signing identity can point at a custom keystore
 * in Settings, which overrides this default.
 *
 * All paths are computed lazily so the JVM is fully bootstrapped (classloader,
 * security manager, etc.) before we probe for the JAR location. The
 * resolution runs **at most once per JVM** — the lazy property caches.
 */
object MorpheData {
    private val resolution: Resolution by lazy { resolveRoot() }

    /** Root data directory. */
    val root: File get() = resolution.root

    /** Containing directory of the JAR for resolving relative paths, or null in fallback mode. */
    val bundleRoot: File? get() = resolution.bundleRoot

    private data class Resolution(val root: File, val bundleRoot: File?)

    /** Directory for cached patch bundles. */
    val patchesDir: File by lazy { File(root, "patches").also { it.mkdirs() } }

    /** Directory for log files. */
    val logsDir: File by lazy { File(root, "logs").also { it.mkdirs() } }

    /** Cached patch-source owner avatars (GitHub/GitLab), keyed by a hash of their
     *  URL. Purely a re-fetchable cache, so it IS wiped by clear-cache, same as
     *  [patchesDir]. See `AvatarRepository`. */
    val avatarsDir: File by lazy { File(root, "avatars").also { it.mkdirs() } }

    /** Google Play app icons plus the resolution index, keyed by a hash of the package id
     *  (see `app.morphe.gui.data.appicon`). Re-fetchable, so clear-cache wipes it. Not to be
     *  confused with [iconsDir] (Icon Studio's user-authored output), which is never touched. */
    val appIconsDir: File by lazy { File(root, "app-icons").also { it.mkdirs() } }

    /** Directory for custom app icons. */
    val iconsDir: File by lazy { File(root, "icons").also { it.mkdirs() } }

    /** Directory for temporary patcher scratch files. */
    val tmpDir: File by lazy { File(root, "tmp").also { it.mkdirs() } }

    /**
     * Morphe-managed pre-patch (original) APKs, one per package, the canonical input
     * for a later repatch — see `app.morphe.engine.OriginalApkRepository`. Ported
     * concept from morphe-manager's `Filesystem.originalApksDir`. Deliberately
     * NOT wiped by clear-cache: after a patch this holds the only copy of the
     * user's original file, so it isn't disposable cache content.
     */
    val originalApksDir: File by lazy { File(root, "original-apks").also { it.mkdirs() } }

    /**
     * Default destination for patched APKs, used when the user hasn't configured an
     * output folder. Deliberately kept apart from [root]: these are user-facing
     * deliverables, not app data. In the portable layout it sits beside the JAR
     * (`<bundleRoot>/patched_apks`); with no bundle (fallback / IDE /
     * `MORPHE_DATA_DIR`) it is `<root>/patched_apks`.
     */
    val patchedApksDir: File by lazy { File(bundleRoot ?: root, "patched_apks").also { it.mkdirs() } }

    /** Configuration file path. */
    val configFile: File get() = File(root, "config.json")

    /** Default signing keystore file. */
    val defaultKeystoreFile: File get() = File(root, "morphe.keystore")

    /** Target file for converted imported keystores. */
    val importedKeystoreFile: File get() = File(root, "imported.keystore")

    // Reason primary JAR-adjacent storage could not be used.
    private enum class FallbackReason(val message: String) {
        NO_JAR_LOCATION("Could not determine JAR location (running from IDE / classpath?)"),
        NOT_A_JAR("Running source is not a JAR (likely IDE / `./gradlew run`)"),
        NOT_WRITABLE("JAR directory is not writable"),
        EXCEPTION("Exception while resolving JAR location"),
    }

    private fun resolveRoot(): Resolution {
        // Allow explicit override via MORPHE_DATA_DIR.
        envOverrideRoot()?.let { override ->
            Logger.info("Morphe data root: ${override.absolutePath} (MORPHE_DATA_DIR override)")
            override.mkdirs()
            return Resolution(root = override, bundleRoot = null)
        }
        val (jarAdjacent, fallbackReason) = tryJarAdjacent()
        if (jarAdjacent != null) {
            Logger.info("Morphe data root: ${jarAdjacent.absolutePath} (JAR-adjacent)")
            jarAdjacent.mkdirs()
            return Resolution(root = jarAdjacent, bundleRoot = jarAdjacent.parentFile)
        }
        val fallback = userHomeFallback()
        Logger.warn(
            "Morphe data root falling back to ${fallback.absolutePath}, " +
                "primary (JAR-adjacent) unavailable: ${fallbackReason?.message ?: "unknown"}"
        )
        fallback.mkdirs()
        return Resolution(root = fallback, bundleRoot = null)
    }

    /** Attempts to resolve the data root adjacent to the executing JAR. */
    private fun tryJarAdjacent(): Pair<File?, FallbackReason?> {
        val location = try {
            MorpheData::class.java.protectionDomain.codeSource?.location
                ?: return null to FallbackReason.NO_JAR_LOCATION
        } catch (_: Exception) {
            return null to FallbackReason.EXCEPTION
        }

        val jarFile = try {
            // Resolve symlinks to place data next to the real JAR.
            File(location.toURI()).canonicalFile
        } catch (_: Exception) {
            return null to FallbackReason.EXCEPTION
        }

        // Fall back when running unpackaged from a classes directory.
        if (jarFile.isDirectory || !jarFile.name.endsWith(".jar")) {
            return null to FallbackReason.NOT_A_JAR
        }

        val candidate = File(jarFile.parentFile, "morphe-data")
        if (!isWritable(candidate)) {
            return null to FallbackReason.NOT_WRITABLE
        }
        return candidate to null
    }

    /** Resolves data directory from MORPHE_DATA_DIR if set and writable. */
    private fun envOverrideRoot(): File? {
        val raw = System.getenv("MORPHE_DATA_DIR")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dir = File(raw)
        val usable = runCatching { dir.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)
        if (!usable) {
            Logger.warn("MORPHE_DATA_DIR is set to '$raw' but it isn't a writable directory, ignoring.")
            return null
        }
        return dir
    }

    private fun userHomeFallback(): File {
        val userHome = System.getProperty("user.home")
        val legacy = File(userHome, "morphe")
        // Prefer existing legacy directory if present.
        if (legacy.isDirectory) return legacy
        return xdgDataRoot() ?: legacy
    }

    /** Returns `$XDG_DATA_HOME/morphe` on Linux if set. */
    private fun xdgDataRoot(): File? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val isLinux = "linux" in os || "nix" in os || "nux" in os
        if (!isLinux) return null
        val base = System.getenv("XDG_DATA_HOME")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return File(base, "morphe")
    }

    private fun isWritable(dir: File): Boolean {
        if (dir.exists()) return dir.canWrite()
        val parent = dir.parentFile ?: return false
        return parent.canWrite()
    }
}
