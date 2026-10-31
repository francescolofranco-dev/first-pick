package com.firstpick.overlay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.AppButton
import com.firstpick.ui.AppTheme
import com.firstpick.ui.BreakdownTooltip
import com.firstpick.ui.AppButtonVariant
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Small non-activating windows receive input; the full Arena-sized layer stays click-through. */
@Composable
private fun OverlayInteractionWindow(
    title: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    content: @Composable () -> Unit,
) {
    val state = rememberWindowState(position = WindowPosition(x.dp, y.dp), size = DpSize(width.dp, height.dp))
    LaunchedEffect(x, y, width, height) {
        state.position = WindowPosition(x.dp, y.dp)
        state.size = DpSize(width.dp, height.dp)
    }
    Window(onCloseRequest = {}, state = state, title = title,
        transparent = true, undecorated = true, alwaysOnTop = true, focusable = false, resizable = false) {
        LaunchedEffect(window) {
            repeat(40) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, false) }) return@LaunchedEffect
                delay(150)
            }
        }
        AppTheme { content() }
    }
}

internal fun showOverlayAccessNotice(health: OverlayHealth, access: LiveDeckAccess): Boolean =
    health.state == OverlayHealthState.ACCESS_REQUIRED || access.status == LiveDeckAccessStatus.GRANTING

@Composable
internal fun OverlayAccessNotice(
    arena: WindowBounds,
    health: OverlayHealth,
    access: LiveDeckAccess,
    onGrant: () -> Unit,
) {
    if (!showOverlayAccessNotice(health, access)) return
    val width = minOf(330f, arena.w * .6f)
    OverlayInteractionWindow("FirstPick Arena Access", arena.x + arena.w - width - 24f,
        arena.y + 24f, width, 112f) {
        AppButton(onClick = onGrant, enabled = access.canGrant, modifier = Modifier.fillMaxSize(),
            variant = AppButtonVariant.Tonal, contentPadding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect Arena to show live advice", style = AppTheme.typography.caption,
                    color = AppTheme.colors.textPrimary)
                Text(if (access.status == LiveDeckAccessStatus.GRANTING) "Connecting…" else "Grant Arena access",
                    color = AppTheme.colors.accent, style = AppTheme.typography.caption)
            }
        }
    }
}

internal fun scoreBadgeBounds(mark: DraftScoreMark, width: Float, height: Float): GuidanceViewportRect? {
    val rect = mark.bounds.inViewport(width, height) ?: return null
    val badge = (rect.width * BADGE_SCALE).coerceIn(BADGE_MIN_SIZE, BADGE_MAX_SIZE)
    return GuidanceViewportRect(rect.left + (rect.width - badge) / 2f,
        rect.top + rect.height - BADGE_CARD_OVERLAP * badge, badge, badge)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DraftScoreInteractions(marks: List<DraftScoreMark>, arena: WindowBounds) {
    for ((index, mark) in marks.withIndex()) {
        val breakdown = mark.breakdown ?: continue
        val rect = scoreBadgeBounds(mark, arena.w.toFloat(), arena.h.toFloat()) ?: continue
        key(index, breakdown, mark.model) {
            OverlayInteractionWindow("FirstPick Score ${index + 1}", arena.x + rect.left,
                arena.y + rect.top, rect.width, rect.height) {
                TooltipArea(delayMillis = 160, tooltip = { BreakdownTooltip(breakdown, mark.model) }) {
                    ScoreBadge(mark.value, rect.width)
                }
            }
        }
    }
}
