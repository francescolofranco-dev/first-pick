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
import com.firstpick.core.Log
import com.firstpick.ui.BreakdownTooltip
import com.firstpick.ui.DevFlags
import com.firstpick.ui.MacOverlay
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import com.firstpick.advisor.ValueBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "Overlay"
private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val BOUNDS_GRACE_MS = 2_000L
private const val CLICK_THROUGH_ATTEMPTS = 40
private const val CLICK_THROUGH_RETRY_MS = 150L
private const val CALIBRATION_DEBOUNCE_MS = 400L
private const val CALIBRATION_RETRY_MS = 900L
private const val MAX_CALIBRATION_ATTEMPTS = 12
private const val MIN_CALIBRATION_CARDS = 10

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",
    val breakdown: ValueBreakdown? = null,
    /** Position of this card in Arena's pack list (the on-screen slot), row-major. */
    val originalIndex: Int = -1,
)

private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean, val breakdown: ValueBreakdown? = null)

/**
 * Seals are placed from the draft log plus calibrated window geometry: Arena renders the pack in
 * log order on a fixed grid, so once the grid fractions for this window size are known, placement
 * needs no screen capture at all. Capture (with the CV detector) runs only in the background to
 * calibrate a window size the store hasn't seen yet — until then the fractions measured from real
 * frames serve as the default.
 */
@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
    calibrationStore: PackGridCalibrationStore = PackGridCalibrationStore(),
) {
    val loc = remember { locator }
    val cap = remember { capturer }
    val store = remember { calibrationStore }

    // Keep the last known bounds through transient locate failures: tearing the window down and
    // recreating it made the overlay flicker and dropped its click-through state.
    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(loc) {
        var lastSeen = 0L
        while (true) {
            val b = withContext(Dispatchers.IO) { loc.locate() }
            val now = System.currentTimeMillis()
            if (b != null) {
                bounds = b
                lastSeen = now
            } else if (now - lastSeen > BOUNDS_GRACE_MS) {
                bounds = null
            }
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

    var calVersion by remember { mutableStateOf(0) }
    val cal = remember(b.w, b.h, calVersion) { store.get(b.w, b.h) }

    var devMarks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    val marks = if (calibrating) devMarks else remember(cards, b.w, b.h, cal) {
        geometryMarks(cards, cal ?: PackGeometry.DEFAULT, b.w, b.h)
    }

    var clickThroughFailed by remember { mutableStateOf(false) }
    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating) && !clickThroughFailed

    // Calibrate window sizes the store hasn't seen, from a rich pack (early picks have enough
    // cards for a trustworthy grid). Runs behind geometry placement; seals never wait on it.
    val wantCalibration = !calibrating && visible && cards.size >= MIN_CALIBRATION_CARDS && !store.has(b.w, b.h)
    LaunchedEffect(wantCalibration, cards.size, b.w, b.h) {
        if (!wantCalibration) return@LaunchedEffect
        delay(CALIBRATION_DEBOUNCE_MS)
        var prev: PackGridCalibration? = null
        repeat(MAX_CALIBRATION_ATTEMPTS) {
            val next = withContext(Dispatchers.IO) {
                cap.capture()?.let { CardDetector.detect(it, cards.size) }?.let { PackGeometry.fromGrid(it) }
            }
            // Arena animates for a moment after a pack arrives; require two consecutive captures
            // to agree so a mid-animation frame can't poison the stored calibration.
            if (next != null) {
                if (prev != null && next.approx(prev!!)) {
                    store.put(b.w, b.h, next)
                    calVersion++
                    Log.info(TAG, "calibrated pack grid for ${b.w}x${b.h}")
                    return@LaunchedEffect
                }
                prev = next
            }
            delay(CALIBRATION_RETRY_MS)
        }
        Log.warn(TAG, "grid calibration did not converge for ${b.w}x${b.h}; using default fractions")
    }

    // Dev calibration mode: numbered boxes driven by live detection of a full 15-slot grid.
    LaunchedEffect(calibrating, b.w, b.h) {
        if (!calibrating) return@LaunchedEffect
        while (true) {
            val grid = withContext(Dispatchers.IO) { cap.capture()?.let { CardDetector.detect(it, 15) } }
            if (grid != null) {
                val sx = b.w.toFloat() / grid.imageW
                val sy = b.h.toFloat() / grid.imageH
                devMarks = grid.cards(15).map { r ->
                    Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), null, r.index + 1, false, null)
                }
            }
            delay(CALIBRATION_RETRY_MS)
        }
    }

    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(visible, b.x, b.y, marks) {
        // The marks this index pointed into are gone on every restart; without the reset a
        // tooltip could stay stuck on screen when the pointer is no longer over any seal.
        hoveredIndex = null
        if (!visible || marks.isEmpty()) return@LaunchedEffect
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
        // Apply click-through as soon as the native window exists — an overlay covering Arena
        // without it steals every click (and activating the app raises the main window over the
        // game). If it verifiably cannot be applied, hide the overlay rather than block Arena.
        var applied by remember { mutableStateOf(false) }
        LaunchedEffect(visible) {
            if (applied) return@LaunchedEffect
            repeat(CLICK_THROUGH_ATTEMPTS) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }) {
                    applied = true
                    // A transient failure may have latched the fail-safe; a verified apply
                    // makes the overlay safe to show again.
                    clickThroughFailed = false
                    return@LaunchedEffect
                }
                delay(CLICK_THROUGH_RETRY_MS)
            }
            if (visible) {
                clickThroughFailed = true
                Log.warn(TAG, "click-through could not be applied — hiding overlay so clicks reach Arena")
            }
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

private fun PackGridCalibration.approx(other: PackGridCalibration, eps: Float = 0.004f): Boolean =
    abs(colX0 - other.colX0) < eps && abs(colPitch - other.colPitch) < eps &&
        abs(colW - other.colW) < eps && abs(row0Y - other.row0Y) < eps &&
        abs(rowPitch - other.rowPitch) < eps && abs(cardH - other.cardH) < eps

private fun geometryMarks(cards: List<OverlayCard>, cal: PackGridCalibration, winW: Int, winH: Int): List<Mark> {
    if (cards.isEmpty()) return emptyList()
    val rects = PackGeometry.rects(cal, winW, winH, cards.size)
    // Cards arrive ranked; originalIndex is the on-screen slot. Fall back to list order if the
    // indices don't form a clean permutation.
    val byOriginal = cards.all { it.originalIndex in rects.indices } &&
        cards.map { it.originalIndex }.toSet().size == cards.size
    val best = cards.indices.maxByOrNull { cards[it].value ?: Double.NEGATIVE_INFINITY }
    return cards.mapIndexed { i, c ->
        val r = rects[if (byOriginal) c.originalIndex else i]
        Mark(r.x, r.y, r.w, r.h, c.value, null, i == best, c.breakdown)
    }
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
