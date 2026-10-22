/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * [revealInFileManager] must degrade quietly — every "Open folder" button shares it, and on a
 * headless machine or after the folder was deleted there is nothing to open. (The positive case
 * would launch a real file manager, so it is deliberately not exercised here.)
 */
class FileManagerUtilsTest {

    @Test
    fun `a null folder reports false instead of throwing`() = runBlocking {
        assertFalse(revealInFileManager(null))
    }

    @Test
    fun `a folder that no longer exists reports false instead of throwing`() = runBlocking {
        val gone = createTempDirectory().toFile().also { it.deleteRecursively() }
        assertFalse(revealInFileManager(File(gone, "missing")))
    }
}
