package com.firstpick.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val NavigationMarkerGutter = 8.dp

internal data class NavigationFeedback(val modifier: Modifier, val ink: Color)

/** Shared navigation treatment: text emphasis plus a bottom or leading-edge marker. */
@Composable
internal fun navigationFeedback(
    selected: Boolean? = null,
    enabled: Boolean = true,
    vertical: Boolean = false,
    indicatorInset: Dp = 0.dp,
    role: Role = Role.Button,
    onClick: () -> Unit,
): NavigationFeedback {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val highlighted = enabled && (hovered || focused)
    val opacity = when {
        !enabled -> 0f
        selected == true -> 1f
        hovered || focused -> .35f
        else -> 0f
    }
    val modifier = Modifier.drawBehind {
        if (opacity > 0f) {
            val stroke = 2.dp.toPx()
            val inset = indicatorInset.toPx()
            val start = if (vertical) Offset(stroke / 2, inset) else Offset(inset, size.height - stroke / 2)
            val end = if (vertical) Offset(stroke / 2, size.height - inset) else Offset(size.width - inset, size.height - stroke / 2)
            drawLine(AppColors.accent.copy(alpha = opacity), start, end, stroke)
        }
    }.semantics { if (selected != null) this.selected = selected }
        .hoverable(source, enabled = enabled)
        .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
        .focusable(enabled, source)
        .then(if (vertical) Modifier.padding(start = NavigationMarkerGutter) else Modifier)
    val ink = when {
        !enabled -> AppColors.textMuted
        selected == true || highlighted -> AppColors.textPrimary
        else -> AppColors.textSecondary
    }
    return NavigationFeedback(modifier, ink)
}
