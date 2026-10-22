/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.patches.components.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.morphe.gui.ui.components.MorpheChoiceChip
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.screens.patches.SelectionMode
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Quick-action chip row above the patch list. Each chip is a one-click preset that
 * sets the current selection. The chip whose state matches the current selection
 * gets highlighted (accent border + tint) so the user can see at a glance what
 * preset they're on. The last chip is not a preset but opens the copy-selection picker
 * ([onCopyFrom]).
 */
@Composable
internal fun SelectionModeChips(
    hasSavedSelection: Boolean,
    activeMode: SelectionMode,
    onApplySaved: () -> Unit,
    onApplyDefaults: () -> Unit,
    onApplyAll: () -> Unit,
    onApplyNone: () -> Unit,
    onCopyFrom: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Highlights SAVED preset when activeMode is explicitly set to SAVED.
        MorpheChoiceChip(
            text = stringResource(Res.string.patch_selection_mode_your_defaults),
            icon = MorpheIcons.Bookmark,
            active = activeMode == SelectionMode.SAVED,
            enabled = hasSavedSelection,
            onClick = onApplySaved,
            modifier = Modifier.weight(1f)
        )
        MorpheChoiceChip(
            text = stringResource(Res.string.patch_selection_mode_patch_defaults),
            icon = MorpheIcons.AutoAwesome,
            active = activeMode == SelectionMode.DEFAULTS,
            onClick = onApplyDefaults,
            modifier = Modifier.weight(1f)
        )
        MorpheChoiceChip(
            text = stringResource(Res.string.patch_selection_mode_all),
            icon = MorpheIcons.DoneAll,
            active = activeMode == SelectionMode.ALL,
            onClick = onApplyAll,
            modifier = Modifier.weight(1f)
        )
        MorpheChoiceChip(
            text = stringResource(Res.string.none),
            icon = MorpheIcons.RemoveDone,
            active = activeMode == SelectionMode.NONE,
            onClick = onApplyNone,
            modifier = Modifier.weight(1f)
        )
        // An action rather than a preset: it opens the copy-selection picker, so it is
        // never "active" (the selection it produces is just a CUSTOM one) and never disabled
        // (the picker itself explains when there is nothing to copy from).
        MorpheChoiceChip(
            text = stringResource(Res.string.patch_selection_mode_copy_from),
            icon = MorpheIcons.ContentCopy,
            active = false,
            onClick = onCopyFrom,
            modifier = Modifier.weight(1f)
        )
    }
}
