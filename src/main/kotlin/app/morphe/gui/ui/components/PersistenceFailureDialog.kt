/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.engine.util.PersistenceFailures
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user that something they changed could not be saved. A save that fails must never
 * pass silently: without this, a settings toggle would look applied until the next launch
 * quietly reverted it.
 */
@Composable
fun PersistenceFailureDialog(failure: PersistenceFailures.Failure, onDismiss: () -> Unit) {
    val font = LocalMorpheFont.current
    val reason = failure.cause.message?.lineSequence()?.firstOrNull()?.take(200)
        ?: failure.cause::class.simpleName.orEmpty()
    MorpheAlertDialog(
        onDismiss = onDismiss,
        title = {
            Text(
                stringResource(Res.string.persistence_failure_title),
                fontFamily = font,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier) {
                Text(
                    stringResource(Res.string.persistence_failure_message, failure.subject),
                    fontFamily = font,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Res.string.persistence_failure_reason, reason),
                    fontFamily = font,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.ok), fontFamily = font)
            }
        },
    )
}
