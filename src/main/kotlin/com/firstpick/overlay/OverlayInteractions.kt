package com.firstpick.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.awt.ComposeWindow
import kotlin.math.roundToInt
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
private fun OverlayPanelWindow(
    title: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    clickThrough: Boolean = false,
    aboveOverlayControls: Boolean = false,
    content: @Composable () -> Unit,
) {
    var prepared by remember { mutableStateOf(!clickThrough) }
    SwingWindow(
        visible = prepared,
        create = {
            ComposeWindow().apply {
                // A normal non-focusable NSWindow still activates its application on click.
                // AWT POPUP creates an NSPanel with NSWindowStyleMaskNonactivatingPanel.
                isUndecorated = true
                type = java.awt.Window.Type.POPUP
                focusableWindowState = false
                isAutoRequestFocus = false
                isAlwaysOnTop = true
                isResizable = false
                isTransparent = true
                this.title = title
                setLocation(x.roundToInt(), y.roundToInt())
                setSize(width.roundToInt(), height.roundToInt())
                if (clickThrough) {
                    // Configure before showing: a full-size transient panel must never intercept
                    // the pointer and cause its own badge's hover to end.
                    addNotify()
                    prepared = MacOverlay.setClickThrough(this, true, aboveOverlayControls)
                }
            }
        },
        update = {
            it.setLocation(x.roundToInt(), y.roundToInt())
            it.setSize(width.roundToInt(), height.roundToInt())
        },
        dispose = { it.dispose() },
    ) {
        LaunchedEffect(window) {
            repeat(40) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, clickThrough, aboveOverlayControls) }) {
                    prepared = true
                    return@LaunchedEffect
                }
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
    val width = minOf(264f, arena.w * .6f)
    OverlayPanelWindow("FirstPick Arena Access", arena.x + (arena.w - width) / 2f,
        arena.y + 36f, width, 48f) {
        OverlayAccessNoticeContent(access, onGrant)
    }
}

@Composable
internal fun OverlayAccessNoticeContent(access: LiveDeckAccess, onGrant: () -> Unit) {
    // Keep the backplate opaque even when the button dims while connecting.
    Box(Modifier.fillMaxSize().background(AppTheme.colors.surface, RoundedCornerShape(AppTheme.radii.md))) {
        AppButton(onClick = onGrant, enabled = access.canGrant, modifier = Modifier.fillMaxSize(),
            variant = AppButtonVariant.Tonal, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("FirstPick · Live advice", style = AppTheme.typography.micro,
                    color = AppTheme.colors.textSecondary)
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

@Composable
internal fun DraftScoreInteractions(marks: List<DraftScoreMark>, arena: WindowBounds) {
    var hovered by remember { mutableStateOf<Int?>(null) }
    // A changed pack or reading must never retain the previous card's explanation.
    LaunchedEffect(marks.map { it.breakdown to it.model }) { hovered = null }
    val hoveredMark = hovered?.let { marks.getOrNull(it) }
    if (hoveredMark?.breakdown != null) {
        OverlayScoreDetailsWindow(hoveredMark, arena)
    }
    for ((index, mark) in marks.withIndex()) {
        if (mark.breakdown == null) continue
        val rect = scoreBadgeBounds(mark, arena.w.toFloat(), arena.h.toFloat()) ?: continue
        key(index, mark.breakdown, mark.model) {
            OverlayPanelWindow("FirstPick Score ${index + 1}", arena.x + rect.left,
                arena.y + rect.top, rect.width, rect.height) {
                OverlayScoreBadge(mark, rect.width) { inside ->
                    if (inside) hovered = index else if (hovered == index) hovered = null
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OverlayScoreBadge(mark: DraftScoreMark, size: Float, onHoverChanged: (Boolean) -> Unit) {
    var inside by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onHoverChanged)
    DisposableEffect(Unit) { onDispose { callback(false) } }
    LaunchedEffect(inside) {
        if (inside) delay(160)
        callback(inside)
    }
    Box(Modifier.onPointerEvent(PointerEventType.Enter) { inside = true }
        .onPointerEvent(PointerEventType.Exit) { inside = false }) {
        ScoreBadge(mark.value, size)
    }
}

@Composable
internal fun OverlayScoreDetailsWindow(mark: DraftScoreMark, arena: WindowBounds) {
    OverlayPanelWindow("FirstPick Score Details", arena.x.toFloat(), arena.y.toFloat(),
        arena.w.toFloat(), arena.h.toFloat(), clickThrough = true, aboveOverlayControls = true) {
        OverlayScoreDetails(mark, arena.w.toFloat(), arena.h.toFloat())
    }
}

/** Shared layout for the full-size transient details layer and rendering regression fixtures. */
@Composable
internal fun OverlayScoreDetails(mark: DraftScoreMark, width: Float, height: Float) {
    val breakdown = mark.breakdown ?: return
    val badge = scoreBadgeBounds(mark, width, height) ?: return
    Layout(content = { BreakdownTooltip(breakdown, mark.model) }) { children, _ ->
        val viewportWidth = width.dp.roundToPx()
        val viewportHeight = height.dp.roundToPx()
        val gap = 8.dp.roundToPx()
        val panel = children.single().measure(Constraints(
            maxWidth = minOf(248.dp.roundToPx(), (viewportWidth - gap * 2).coerceAtLeast(0)),
            maxHeight = (viewportHeight - gap * 2).coerceAtLeast(0),
        ))
        val left = (badge.left.dp.roundToPx() + (badge.width.dp.roundToPx() - panel.width) / 2)
            .coerceIn(gap, (viewportWidth - panel.width - gap).coerceAtLeast(gap))
        val above = badge.top.dp.roundToPx() - panel.height - gap
        val below = (badge.top + badge.height).dp.roundToPx() + gap
        val top = (if (above >= gap) above else below)
            .coerceIn(gap, (viewportHeight - panel.height - gap).coerceAtLeast(gap))
        layout(viewportWidth, viewportHeight) { panel.place(left, top) }
    }
}
