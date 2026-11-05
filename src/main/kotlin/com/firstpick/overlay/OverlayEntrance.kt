package com.firstpick.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** A new presentation fades in; invalidated advice disappears immediately.
 * Reading alpha in the draw layer preserves the exact card positions throughout.
 */
@Composable
internal fun OverlayEntrance(visible: Boolean, content: @Composable () -> Unit) {
    // Reset synchronously on visibility changes, preventing a one-frame flash on re-entry.
    val alpha = remember(visible) { Animatable(0f) }
    LaunchedEffect(alpha) {
        if (visible) alpha.animateTo(1f, tween(240, easing = LinearOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { this.alpha = if (visible) alpha.value else 0f }) {
        content()
    }
}
