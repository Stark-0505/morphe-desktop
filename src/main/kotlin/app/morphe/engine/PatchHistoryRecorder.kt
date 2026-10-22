/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.apk.ApkInspector
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.FileChecksum
import app.morphe.engine.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What happens once a patch has succeeded, shared by the CLI, Expert mode and Quick mode (all
 * reach it through [PatchEngine]) so they cannot diverge: hand the original APK over to Morphe
 * and record the patched app.
 *
 * Order matters, because the last step deletes the user's file:
 * 1. Archive the original ([OriginalApkRepository.saveOriginalApk]: copied, verified, registered).
 * 2. Persist the [PatchedAppRecord], pointing at the archive so it, not the user's file, is
 *    what a repatch reads.
 * 3. Only then remove the user's copy ([OriginalApkRepository.discardSource]).
 *
 * Archiving only happens when the caller supplies an [OriginalApkRepository] through
 * [PatchEngine.HistoryMetadata.originalApkRepository]. The CLI supplies none, so it never
 * moves or deletes an input file.
 *
 * A failure at any step leaves the user's original where it was. If archiving fails, the record
 * still goes in, referencing the user's own path so the app shows up in history and can still be
 * repatched from there. If the record can't be persisted, nothing is deleted.
 */
object PatchHistoryRecorder {

    /**
     * Records a finished patch of [inputApk] into [outputApk]. Runs to completion even if the
     * calling screen goes away mid-way: a half-finished archive/record sequence is the one
     * outcome to avoid. Failures are logged and returned, never thrown.
     *
     * [packageName] and [packageVersion] are what the patcher saw; the original (pre-rename)
     * package in [metadata] takes precedence when present.
     */
    suspend fun record(
        packageName: String,
        packageVersion: String,
        displayName: String?,
        inputApk: File,
        outputApk: File,
        metadata: PatchEngine.HistoryMetadata?,
        store: PatchedAppStore = PatchedAppStore.shared,
    ): Result<PatchedAppRecord> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val pkg = metadata?.originalPackageName?.takeIf { it.isNotBlank() } ?: packageName
            require(pkg.isNotBlank()) { "the patch result carries no package name" }

            val (sha, size) = FileChecksum.fingerprintOrNull(outputApk.path)
            // The output's manifest is the source for the post-rename package only. The version
            // is the ORIGINAL's, so the archive and the record describe the APK that was patched
            // (falling back to the output's, then the patcher's version).
            val outputManifest = ApkInspector.inspect(outputApk)
            val originalManifest = ApkInspector.inspect(inputApk)
            val version = originalManifest?.versionName?.takeIf { it.isNotBlank() }
                ?: outputManifest?.versionName?.takeIf { it.isNotBlank() }
                ?: packageVersion

            val archive = metadata?.originalApkRepository
            val archived = if (archive == null) {
                null
            } else {
                try {
                    archive.saveOriginalApk(pkg, version, inputApk)
                } catch (e: Exception) {
                    Logger.error("Could not archive the original APK for $pkg; keeping the user's file", e)
                    null
                }
            }

            // What the record will vouch for: the archive's registered hash, or — when nothing was
            // archived and the user's own file is what a repatch will read — the file's own hash.
            val inputSha = if (archived != null) {
                archive?.recordFor(archived)?.sha256
            } else {
                runCatching { FileChecksum.sha256(inputApk) }.getOrNull()
            }

            // The package the build installs under is its identity, so a renamed
            // build gets a record of its own instead of overwriting the app's.
            val installedPackageName = outputManifest?.packageName?.takeIf { it.isNotBlank() } ?: pkg

            val record = PatchedAppRecord(
                id = installedPackageName,
                packageName = pkg,
                currentPackageName = outputManifest?.packageName,
                displayName = displayName?.takeIf { it.isNotBlank() }
                    ?: metadata?.displayName?.takeIf { it.isNotBlank() }
                    ?: pkg,
                appIconColorHex = metadata?.appIconColorHex?.takeIf { it.isNotBlank() },
                apkVersion = version,
                apkVersionCode = originalManifest?.versionCode ?: outputManifest?.versionCode,
                inputApkPath = (archived ?: inputApk).absolutePath,
                inputApkSha256 = inputSha,
                outputApkPath = outputApk.absolutePath,
                outputApkSha256 = sha,
                outputApkSize = if (size > 0L) size else outputApk.length(),
                patchSelectionByBundle = metadata?.patchSelectionByBundle ?: emptyMap(),
                patchOptionValues = metadata?.patchOptionValues ?: emptyMap(),
                sourcesSnapshot = metadata?.sourcesSnapshot ?: emptyList(),
                patchedAt = System.currentTimeMillis(),
                patchedWithMorpheVersion = UpdateChecker.currentVersion() ?: "unknown",
            )
            store.upsert(record)

            // Everything that references the archive is durable now, so the user's copy is redundant.
            if (archive != null && archived != null) archive.discardSource(inputApk, archived)
            record
        }.onFailure { Logger.error("Failed to record patched app", it) }
    }
}
