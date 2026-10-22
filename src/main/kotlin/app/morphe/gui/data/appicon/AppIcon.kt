/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject

/**
 * The app's Google Play icon, or [fallback] (the surface's own initials/placeholder) while there
 * is none. A cached icon is shown on the first frame; otherwise it is resolved in the background
 * and faded in. A package that cannot be resolved simply keeps showing [fallback].
 *
 * Content only: size, clipping and border belong to the caller's container, which this fills.
 * [packageId] is the canonical supported-app package, not the package a patched build installs under.
 */
@Composable
fun AppIcon(
    packageId: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    fallback: @Composable () -> Unit,
) {
    val repository = koinInject<AppIconRepository>()
    // Reset per package so a recycled card never shows the previous app's icon.
    var icon by remember(packageId) { mutableStateOf(repository.peek(packageId)) }
    LaunchedEffect(packageId) {
        if (icon == null) icon = repository.load(packageId)
    }

    Crossfade(targetState = icon, animationSpec = tween(200)) { bitmap ->
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
        } else {
            fallback()
        }
    }
}
