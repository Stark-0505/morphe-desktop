/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import app.morphe.engine.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Opens [folder] in the platform file manager. Returns false — never throws — when the
 * folder is missing or the platform has no OPEN support (headless Linux, minimal WMs), so
 * every "Open folder" button degrades the same way instead of each call site repeating
 * the support probe and its own try/catch.
 *
 * `Desktop.open` can block for a long time (it may spawn a file manager), so this runs on
 * the IO dispatcher and is safe to call from a UI-scoped coroutine.
 */
suspend fun revealInFileManager(folder: File?): Boolean = withContext(Dispatchers.IO) {
    if (folder == null || !folder.exists()) return@withContext false
    try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return@withContext false
        }
        Desktop.getDesktop().open(folder)
        true
    } catch (e: Exception) {
        Logger.error("Failed to open folder in file manager: ${folder.absolutePath}", e)
        false
    }
}
