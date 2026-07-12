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
import androidx.compose.ui.graphics.Brush
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
import com.firstpick.ui.CardImageLoader
import com.firstpick.ui.DevFlags
import com.firstpick.ui.MacOverlay
import com.firstpick.ui.TierInk
import com.firstpick.ui.isBombTier
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "Overlay"
private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val BOUNDS_GRACE_MS = 2_000L
private const val CLICK_THROUGH_ATTEMPTS = 40
private const val CLICK_THROUGH_RETRY_MS = 150L
private const val CAPTURE_DEBOUNCE_MS = 350L
private const val RECOGNITION_RETRY_MS = 700L
private const val MAX_RECOGNITION_ATTEMPTS = 10
private const val DEV_DETECT_RETRY_MS = 900L
private const val MIN_CALIBRATION_CARDS = 10

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",
    /** Position of this card in Arena's pack list (the on-screen slot), row-major. */
    val originalIndex: Int = -1,
)

private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean)

/**
 * Seal POSITIONS come from calibrated window geometry (Arena's pack grid is deterministic per
 * window size), so seals appear the instant the log announces a pack — as neutral placeholders.
 * Card IDENTITY comes from capture + art recognition in the background, because the log's pack
 * order is NOT the on-screen order (live-disproven 2026-06-29): once two consecutive captures
 * agree on the assignment, the placeholders fill in with grades. A successful detection also
 * refreshes this window size's grid calibration as a by-product. Capture failures (hover
 * preview, animation, permissions) therefore delay grades but never move, hide, or mis-seat
 * the seals.
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

    // Slot -> index into cards; null while recognition is still pending for this pack. Keyed on
    // the pack so a new pack synchronously reverts to placeholders (no one-frame stale grades).
    val packKey = remember(cards) { cards.joinToString("|") { "${it.name}#${it.imageUrl}" } }
    val assignmentState = remember(packKey) { mutableStateOf<Map<Int, Int>?>(null) }

    var devMarks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    val marks = if (calibrating) devMarks else remember(cards, b.w, b.h, cal, assignmentState.value) {
        geometryMarks(cards, cal ?: PackGeometry.DEFAULT, b.w, b.h, assignmentState.value)
    }

    var clickThroughFailed by remember { mutableStateOf(false) }
    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating) && !clickThroughFailed

    // Identity pass: capture, detect, recognize — repeats until two consecutive captures agree
    // on the full assignment (Arena animates after a pack arrives; a single mid-animation frame
    // could lock in garbage). Detections also refresh the grid calibration for this window size.
    LaunchedEffect(packKey, visible, b.w, b.h) {
        if (calibrating || !visible || cards.isEmpty() || assignmentState.value != null) return@LaunchedEffect
        delay(CAPTURE_DEBOUNCE_MS)
        val refs = withContext(Dispatchers.IO) {
            cards.map { c -> c.imageUrl?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) } }
        }
        val expected = refs.count { it != null }
        if (expected == 0) {
            Log.warn(TAG, "no card images available for recognition; seals stay ungraded")
            return@LaunchedEffect
        }
        var prev: Map<Int, Int>? = null
        var lastFailure = "no frame captured — check Screen Recording permission"
        repeat(MAX_RECOGNITION_ATTEMPTS) {
            val attempt = withContext(Dispatchers.IO) {
                val frame = cap.capture() ?: return@withContext null
                val grid = CardDetector.detect(frame, cards.size)
                val freshCal = if (cards.size >= MIN_CALIBRATION_CARDS) grid?.let { PackGeometry.fromGrid(it) } else null
                // Recognize inside detected rects when available; otherwise the calibrated
                // fractions mapped into the frame still put each art window on its card.
                val rects = grid?.cards(cards.size)
                    ?: PackGeometry.rects(store.get(b.w, b.h) ?: PackGeometry.DEFAULT, frame.width, frame.height, cards.size)
                Pair(CardRecognizer.match(frame, rects, refs), freshCal)
            }
            val assign = attempt?.first
            if (assign == null) {
                lastFailure = "no frame captured — check Screen Recording permission"
            } else if (assign.size < expected) {
                lastFailure = "recognition incomplete (${assign.size}/$expected)"
            } else if (assign == prev) {
                // This frame is stable (two consecutive captures agree), so it is also the one
                // safe to calibrate from — a lone mid-animation detection must not be stored.
                attempt.second?.let {
                    store.put(b.w, b.h, it)
                    calVersion++
                }
                assignmentState.value = assign
                return@LaunchedEffect
            } else {
                prev = assign
            }
            delay(RECOGNITION_RETRY_MS)
        }
        // Last resort: the pack-list order. It is known not to match the screen in general, but
        // after this many failed captures a possibly misordered grade beats a blank seal.
        Log.warn(TAG, "$lastFailure after $MAX_RECOGNITION_ATTEMPTS attempts; falling back to pack-list order")
        val n = cards.size
        if (cards.all { it.originalIndex in 0 until n } && cards.map { it.originalIndex }.toSet().size == n) {
            assignmentState.value = cards.withIndex().associate { (i, c) -> c.originalIndex to i }
        }
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
                    Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), null, r.index + 1, false)
                }
            }
            delay(DEV_DETECT_RETRY_MS)
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
        }
    }
}

private fun geometryMarks(
    cards: List<OverlayCard>,
    cal: PackGridCalibration,
    winW: Int,
    winH: Int,
    assignment: Map<Int, Int>?,
): List<Mark> {
    if (cards.isEmpty()) return emptyList()
    val rects = PackGeometry.rects(cal, winW, winH, cards.size)
    // Until recognition lands, every slot shows a neutral placeholder (gray "—" seal).
    if (assignment == null) return rects.map { Mark(it.x, it.y, it.w, it.h, null, null, false) }
    val bestCard = assignment.values.maxByOrNull { cards[it].value ?: Double.NEGATIVE_INFINITY }
    return rects.map { r ->
        val ci = assignment[r.index]
        if (ci == null) Mark(r.x, r.y, r.w, r.h, null, null, false)
        else Mark(r.x, r.y, r.w, r.h, cards[ci].value, null, ci == bestCard)
    }
}

@Composable
private fun GradeSeal(m: Mark) {
    val color = valueTierColor(m.value)
    val bomb = isBombTier(m.value)
    val d = (m.w * 0.40f).roundToInt().coerceIn(34, 92)
    // A gold halo makes a bomb impossible to miss; the recommended pick (best in the pack) gets a
    // fainter one so it still reads when it isn't a bomb. The halo widens the box, so re-center.
    val haloAlpha = if (bomb) 0.55f else if (m.isBest) 0.34f else 0f
    val halo = if (bomb) (d * 0.44f).roundToInt() else if (m.isBest) (d * 0.30f).roundToInt() else 0
    val box = d + halo * 2
    val cx = m.x + m.w / 2 - box / 2
    val cy = m.y + m.h - (d * 0.72f).roundToInt() - halo

    Box(Modifier.offset(cx.dp, cy.dp).size(box.dp), contentAlignment = Alignment.Center) {
        if (haloAlpha > 0f) {
            Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(color.copy(alpha = haloAlpha), Color.Transparent))))
        }
        Box(
            Modifier
                .size(d.dp)
                .background(if (bomb) color else Color(0xF00E1312), CircleShape)
                .border(
                    width = if (bomb) 2.5.dp else if (m.isBest) 3.dp else 2.dp,
                    color = if (bomb) Color(0xFFFFE7A6) else color,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (bomb) TierInk else color
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(letterGrade(m.value), color = ink, fontWeight = FontWeight.Bold, fontSize = (d * 0.34f).sp)
                Text(
                    m.value?.roundToInt()?.toString() ?: "—",
                    color = ink.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = (d * 0.18f).sp,
                )
            }
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
