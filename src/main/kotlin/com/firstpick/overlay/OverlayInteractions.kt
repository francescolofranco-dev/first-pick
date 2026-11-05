package com.firstpick.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    SwingWindow(
        visible = visible,
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
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, false) }) {
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
    val width = minOf(260f, arena.w * .24f)
    OverlayPanelWindow("FirstPick Arena Access", arena.x + arena.w * .55f - width / 2f,
        arena.y + 34f, width, 36f) {
        OverlayEntrance(visible = true, modifier = Modifier.fillMaxSize()) {
            OverlayAccessNoticeContent(access, onGrant)
        }
    }
}

@Composable
internal fun OverlayAccessNoticeContent(access: LiveDeckAccess, onGrant: () -> Unit) {
    val connecting = access.status == LiveDeckAccessStatus.GRANTING
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.surface, shape)
        .border(1.dp, AppTheme.colors.borderDefault, shape)) {
        val compact = maxWidth < 240.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("FirstPick", style = AppTheme.typography.caption.copy(fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold), color = AppTheme.colors.textPrimary, maxLines = 1)
                if (!compact) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(4.dp).background(AppTheme.colors.warning, CircleShape))
                    Text(if (connecting) "Awaiting macOS" else "Access required",
                        style = AppTheme.typography.micro.copy(fontSize = 9.sp),
                        color = AppTheme.colors.textSecondary, maxLines = 1)
                }
            }
            AppButton(onClick = onGrant, enabled = access.canGrant,
                modifier = Modifier.width(106.dp), variant = AppButtonVariant.Primary,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text(if (connecting) "Connecting…" else "Grant access",
                    color = if (access.canGrant) AppTheme.colors.textOnAccent else AppTheme.colors.textPrimary,
                    style = AppTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1)
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
    var hovered by remember { mutableStateOf<Pair<Int, DraftScoreMark>?>(null) }
    // Preview windows can appear/disappear independently of the hovered pack badge.
    // Only changes to that badge's identity or explanation should end its hover.
    val hoveredMark = hovered?.let { (slot, original) ->
        marks.withIndex().firstOrNull { (index, mark) -> (mark.slot ?: index) == slot }?.value
            ?.takeIf { !it.covered && it.breakdown == original.breakdown && it.model == original.model }
    }
    LaunchedEffect(hoveredMark == null) { if (hoveredMark == null) hovered = null }
    if (hoveredMark?.breakdown != null) {
        // Draw after the score badges in the existing click-through layer. A new native
        // window over the badge makes AppKit emit Exit even while the pointer stays put.
        key(hovered?.first) {
            OverlayEntrance(visible = true) {
                OverlayScoreDetails(hoveredMark, arena.w.toFloat(), arena.h.toFloat())
            }
        }
    }
    for ((index, mark) in marks.withIndex()) {
        if (mark.breakdown == null) continue
        val rect = scoreBadgeBounds(mark, arena.w.toFloat(), arena.h.toFloat()) ?: continue
        val slot = mark.slot ?: index
        key(slot) {
            OverlayPanelWindow("FirstPick Score ${index + 1}", arena.x + rect.left,
                arena.y + rect.top, rect.width, rect.height, visible = !mark.covered) {
                OverlayScoreBadge(mark, rect.width, drawBadge = false, enabled = !mark.covered) { inside ->
                    if (inside) hovered = slot to mark else if (hovered?.first == slot) hovered = null
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OverlayScoreBadge(mark: DraftScoreMark, size: Float, drawBadge: Boolean = true, enabled: Boolean = true, onHoverChanged: (Boolean) -> Unit) {
    var inside by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onHoverChanged)
    DisposableEffect(Unit) { onDispose { callback(false) } }
    LaunchedEffect(inside, enabled) {
        if (!enabled) {
            inside = false
            callback(false)
        } else {
            if (inside) delay(160)
            callback(inside)
        }
    }
    Box(Modifier.size(size.dp).onPointerEvent(PointerEventType.Enter) { inside = enabled }
        .onPointerEvent(PointerEventType.Exit) { inside = false }) {
        if (drawBadge) ScoreBadge(mark.value, size)
    }
}

/** Positions details within the existing overlay viewport. */
@Composable
internal fun OverlayScoreDetails(mark: DraftScoreMark, width: Float, height: Float) {
    val breakdown = mark.breakdown ?: return
    val badge = scoreBadgeBounds(mark, width, height) ?: return
    Layout(content = { BreakdownTooltip(breakdown, mark.model, gihWr = mark.gihWr) }) { children, _ ->
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
