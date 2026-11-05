package com.firstpick.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** A new presentation fades in; invalidated advice disappears immediately.
 * Reading alpha in the draw layer preserves the exact card positions throughout.
 */
@Composable
internal fun rememberOverlayEntranceAlpha(visible: Boolean, appearanceKey: Any? = Unit): State<Float> {
    // Reset synchronously on visibility changes, preventing a one-frame flash on re-entry.
    val alpha = remember(visible, appearanceKey) { Animatable(0f) }
    LaunchedEffect(alpha) {
        if (visible) alpha.animateTo(1f, tween(240, easing = LinearOutSlowInEasing))
    }
    return alpha.asState()
}

@Composable
internal fun OverlayEntrance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    appearanceKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val alpha = rememberOverlayEntranceAlpha(visible, appearanceKey)
    Box(modifier.graphicsLayer { this.alpha = if (visible) alpha.value else 0f }) {
        content()
    }
}
