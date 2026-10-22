/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.MorpheMotion
import app.morphe.gui.ui.theme.MorpheTheme
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val AvatarSize = 22.dp
private val AvatarRing = 1.5.dp
private val AvatarSlot = AvatarSize + AvatarRing * 2
private val AvatarStep = AvatarSlot - 7.dp
private const val MAX_VISIBLE_AVATARS = 5

/**
 * Header pill showing source count + overlapping source avatars + trailing "+".
 * Used in expert mode (clickable, opens [SourceManagementSheet]) and in Quick
 * Patch mode (purely informational, pass `onClick = null`).
 *
 * Disabled sources are dimmed and sources in [failedSourceIds] get an error ring.
 */
@Composable
fun SourcesCountPill(
    sources: List<PatchSource>,
    failedSourceIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shapes = MorpheTheme.shapes
    val dimens = MorpheTheme.dimens
    val interactive = onClick != null
    val (containerColor, borderColor) = rememberPillColors()
    
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val count = sources.size.coerceAtLeast(1)
    val label = pluralStringResource(Res.plurals.count_sources, count, count)
    Row(
        modifier = modifier
            .height(dimens.controlHeight)
            .clip(shapes.small)
            .background(containerColor)
            .border(1.dp, borderColor, shapes.small)
            .then(
                if (interactive) Modifier
                    .handCursor()
                    .clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MorpheTheme.typography.labelMedium,
            color = tint
        )
        // Weighted so the label and "+" are measured first and the avatars get what is left.
        SourceAvatarStack(
            sources = sources,
            failedSourceIds = failedSourceIds,
            ringColor = containerColor.compositeOver(MaterialTheme.colorScheme.background),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (interactive) {
            Icon(
                imageVector = MorpheIcons.Add,
                contentDescription = stringResource(Res.string.source_sheet_manage_description),
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun SourceAvatarStack(
    sources: List<PatchSource>,
    failedSourceIds: Set<String>,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    // Capacity comes from the width this slot is actually given, so narrow windows drop
    // avatars into the "+N" badge instead of clipping them.
    BoxWithConstraints(modifier) {
        val slots = if (maxWidth < AvatarSlot) 0 else ((maxWidth - AvatarSlot) / AvatarStep).toInt() + 1
        val visible = visibleAvatarCount(sources.size, slots)
        if (visible > 0) {
            // Decorative: the pill's count label already tells screen readers how many sources exist.
            Row(
                modifier = Modifier.clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvatarStep - AvatarSlot),
            ) {
                sources.take(visible).forEach { source ->
                    key(source.id) {
                        val ring = if (source.enabled && source.id in failedSourceIds) {
                            MaterialTheme.colorScheme.error
                        } else {
                            ringColor
                        }
                        Box(Modifier.size(AvatarSlot).background(ring, CircleShape).padding(AvatarRing)) {
                            SourceAvatar(
                                source = source,
                                size = AvatarSize,
                                modifier = Modifier.alpha(if (source.enabled) 1f else 0.4f),
                            )
                        }
                    }
                }
                val hidden = sources.size - visible
                if (hidden > 0) {
                    Box(
                        modifier = Modifier
                            .size(AvatarSlot)
                            .background(ringColor, CircleShape)
                            .padding(AvatarRing)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+$hidden",
                            style = MorpheTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How many avatars to draw for [total] sources when [slots] avatar-sized slots fit. When some
 * sources are hidden, one slot is given to the "+N" badge.
 */
internal fun visibleAvatarCount(total: Int, slots: Int): Int =
    if (total <= minOf(slots, MAX_VISIBLE_AVATARS)) total
    else (minOf(slots, MAX_VISIBLE_AVATARS + 1) - 1).coerceAtLeast(0)

/** Indicator pill displayed while patch manifests and releases are loading. */
@Composable
fun PatchesLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    val shapes = MorpheTheme.shapes
    val dimens = MorpheTheme.dimens
    val (containerColor, borderColor) = rememberPillColors()

    Row(
        modifier = modifier
            .height(dimens.controlHeight)
            .clip(shapes.small)
            .background(containerColor)
            .border(1.dp, borderColor, shapes.small)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.status_loading),
            style = MorpheTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Badge indicating offline status with a retry callback. */
@Composable
fun OfflineBadge(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = MorpheTheme.shapes
    val dimens = MorpheTheme.dimens
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
        animationSpec = MorpheMotion.fastTween(),
        label = "offlineBadgeBorder",
    )

    Row(
        modifier = modifier
            .height(dimens.controlHeight)
            .clip(shapes.small)
            .border(1.dp, borderColor, shapes.small)
            .hoverable(hoverInteraction)
            .handCursor()
            .clickable(onClick = onRetry)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(Res.string.status_offline),
            style = MorpheTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

data class PillColors(val containerColor: Color, val borderColor: Color)

@Composable
fun rememberPillColors(): PillColors {
    val isDark = isSystemInDarkTheme()
    return PillColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.35f else 0.6f),
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.4f else 0.6f)
    )
}

