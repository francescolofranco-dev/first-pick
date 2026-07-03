package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.CardImageLoader
import com.firstpick.ui.DevFlags
import com.firstpick.ui.MacOverlay
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import com.firstpick.ui.BreakdownTooltip
import com.firstpick.advisor.ValueBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val CAPTURE_DEBOUNCE_MS = 350L
private const val MARK_RETRY_MS = 700L
private const val MAX_MARK_ATTEMPTS = 10

data class OverlayCard(val value: Double?, val imageUrl: String?, val name: String = "", val breakdown: ValueBreakdown? = null)

private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean, val breakdown: ValueBreakdown? = null)

private const val MARK_JITTER_PT = 3

private fun List<Mark>.approxEquals(other: List<Mark>): Boolean =
    size == other.size && zip(other).all { (a, b) ->
        a.value == b.value && a.number == b.number && a.isBest == b.isBest &&
            kotlin.math.abs(a.x - b.x) <= MARK_JITTER_PT && kotlin.math.abs(a.y - b.y) <= MARK_JITTER_PT &&
            kotlin.math.abs(a.w - b.w) <= MARK_JITTER_PT && kotlin.math.abs(a.h - b.h) <= MARK_JITTER_PT
    }

@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
) {
    val loc = remember { locator }
    val cap = remember { capturer }
    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(loc) {
        while (true) {
            bounds = withContext(Dispatchers.IO) { loc.locate() }
            delay(POLL_MS)
        }
    }
    val b = bounds ?: return
    val calibrating = cards.isEmpty() && DevFlags.overlayTrack

    val wState = rememberWindowState(
        position = WindowPosition(b.x.dp, b.y.dp),
        size = DpSize(b.w.dp, b.h.dp),
    )
    LaunchedEffect(b.x, b.y, b.w, b.h) {
        wState.position = WindowPosition(b.x.dp, b.y.dp)
        wState.size = DpSize(b.w.dp, b.h.dp)
    }

    var marks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    val packKey = remember(cards) { cards.joinToString("|") { "${it.name}#${it.imageUrl}" } }
    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating)

    // Arena animates for a second or two after a pick; a single capture can land mid-animation
    // and mis-recognize every card. Keep recapturing until two consecutive frames agree.
    LaunchedEffect(b.w, b.h, packKey, calibrating, visible) {
        if (!visible) return@LaunchedEffect
        delay(CAPTURE_DEBOUNCE_MS)
        var prev: List<Mark> = emptyList()
        repeat(MAX_MARK_ATTEMPTS) {
            val next = withContext(Dispatchers.IO) { computeMarks(cap, cards, calibrating, b.w, b.h) }
            if (next.isNotEmpty()) {
                // A couple of pixels of run jitter between captures (flickering backdrop) is
                // convergence, not change — and reassigning would make the seals shimmer.
                if (next.approxEquals(prev)) {
                    if (!next.approxEquals(marks)) marks = next
                    return@LaunchedEffect
                }
                marks = next
            }
            prev = next
            delay(MARK_RETRY_MS)
        }
    }

    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(visible, b.x, b.y, marks) {
        if (!visible || marks.isEmpty()) {
            hoveredIndex = null
            return@LaunchedEffect
        }
        var lastHovered: Int? = null
        while (true) {
            val ptr = withContext(Dispatchers.IO) { java.awt.MouseInfo.getPointerInfo()?.location }
            if (ptr != null) {
                val mx = ptr.x - b.x
                val my = ptr.y - b.y

                val expand = 10

                var hIndex: Int? = null
                for ((i, m) in marks.withIndex()) {
                    val d = (m.w * 0.40f).roundToInt().coerceIn(34, 92)
                    val cx = m.x + m.w / 2 - d / 2
                    val cy = m.y + m.h - (d * 0.72f).roundToInt()

                    val p = if (i == lastHovered) expand else 0
                    if (mx >= cx - p && mx <= cx + d + p && my >= cy - p && my <= cy + d + p) {
                        hIndex = i
                        break
                    }
                }

                if (hIndex != lastHovered) {
                    hoveredIndex = hIndex
                    lastHovered = hIndex
                }
            }
            delay(50)
        }
    }

    Window(
        onCloseRequest = {},
        visible = visible,
        state = wState,
        title = TRACKER_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            repeat(15) {
                if (MacOverlay.setClickThrough(TRACKER_TITLE, true)) return@LaunchedEffect
                delay(200)
            }
            com.firstpick.core.Log.warn("Overlay", "click-through could not be applied")
        }
        Box(Modifier.fillMaxSize()) {
            for (m in marks) {
                if (m.number != null) NumberBox(m) else GradeSeal(m)
            }

            val hIndex = hoveredIndex
            if (hIndex != null && hIndex in marks.indices) {
                val m = marks[hIndex]
                val bd = m.breakdown
                if (bd != null) {
                    val d = (m.w * 0.40f).roundToInt().coerceIn(34, 92)
                    val cx = m.x + m.w / 2 - d / 2
                    val cy = m.y + m.h - (d * 0.72f).roundToInt()

                    val tooltipW = 205
                    val tooltipH = 170

                    var tooltipX = cx + d / 2 - tooltipW / 2
                    var tooltipY = cy - tooltipH - 8

                    if (tooltipX < 8) tooltipX = 8
                    if (tooltipX + tooltipW > b.w - 8) tooltipX = b.w - tooltipW - 8
                    if (tooltipY < 8) tooltipY = cy + d + 8

                    Box(Modifier.offset(tooltipX.dp, tooltipY.dp)) {
                        BreakdownTooltip(bd)
                    }
                }
            }
        }
    }
}

private suspend fun computeMarks(
    capturer: WindowCapture,
    cards: List<OverlayCard>,
    calibrating: Boolean,
    winW: Int,
    winH: Int,
): List<Mark> {
    val frame = capturer.capture()
    if (frame == null) {
        com.firstpick.core.Log.warn("Overlay", "no frame captured — check Screen Recording permission")
        return emptyList()
    }
    if (cards.isNotEmpty()) {
        val grid = CardDetector.detect(frame, cards.size)
        if (grid == null) {
            com.firstpick.core.Log.warn("Overlay", "pack grid not found (${cards.size} cards, ${frame.width}x${frame.height})")
            return emptyList()
        }
        val rects = grid.cards(cards.size)
        val refs = cards.map { c -> c.imageUrl?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) } }
        val assign = CardRecognizer.match(frame, rects, refs)
        val best = cards.indices.maxByOrNull { cards[it].value ?: Double.NEGATIVE_INFINITY }
        val sx = winW.toFloat() / grid.imageW
        val sy = winH.toFloat() / grid.imageH
        return rects.mapNotNull { r ->
            val ci = assign[r.index] ?: return@mapNotNull null
            Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), cards[ci].value, null, ci == best, cards[ci].breakdown)
        }
    }
    if (calibrating) {
        val grid = CardDetector.detect(frame, 15) ?: return emptyList()
        val sx = winW.toFloat() / grid.imageW
        val sy = winH.toFloat() / grid.imageH
        return grid.cards(15).map { r ->
            Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), null, r.index + 1, false, null)
        }
    }
    return emptyList()
}

@Composable
private fun GradeSeal(m: Mark) {
    val color = valueTierColor(m.value)
    val d = (m.w * 0.40f).roundToInt().coerceIn(34, 92)
    val cx = m.x + m.w / 2 - d / 2
    val cy = m.y + m.h - (d * 0.72f).roundToInt()
    Box(
        Modifier
            .offset(cx.dp, cy.dp)
            .size(d.dp)
            .background(Color(0xF00E1312), CircleShape)
            .border(if (m.isBest) 3.dp else 2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(letterGrade(m.value), color = color, fontWeight = FontWeight.Bold, fontSize = (d * 0.34f).sp)
            Text(
                m.value?.roundToInt()?.toString() ?: "—",
                color = color.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = (d * 0.18f).sp,
            )
        }
    }
}

@Composable
private fun NumberBox(m: Mark) {
    val color = Color(0xFFFF5C8A)
    Box(
        Modifier.offset(m.x.dp, m.y.dp).size(m.w.dp, m.h.dp).border(2.dp, color, RoundedCornerShape(6.dp))
    ) {
        Box(
            Modifier.align(Alignment.TopStart)
                .background(color, RoundedCornerShape(bottomEnd = 6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text("${m.number}", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
