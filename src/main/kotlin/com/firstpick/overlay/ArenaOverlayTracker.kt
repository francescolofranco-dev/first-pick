package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.firstpick.ui.DevFlags
import com.firstpick.ui.MacOverlay
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val CAPTURE_DEBOUNCE_MS = 350L

/** One pack card's grade for the overlay: [index] is its slot in the pack (visual order). */
data class CardGrade(val index: Int, val value: Double?)

private data class Rect(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * The embedded draft overlay: a transparent, click-through window pinned to the live MTG Arena
 * window that draws a pick grade on each pack card — the on-card experience the user asked for
 * (Untapped/Draftism style), draft support only.
 *
 * Position/size are tracked cheaply every [POLL_MS] via [WindowLocator] (no permission). The card
 * rectangles come from [CardDetector] run on a [WindowCapture] frame, recomputed only when the
 * window *size* changes (the grid is fixed for a given size), so the costly capture never runs on
 * the per-frame position poll. If capture is unavailable (no Screen Recording permission, off
 * macOS) it falls back to [PackLayout]'s geometric guess so grades still show, just less precisely.
 *
 * [grades] is the current pack keyed by visual index (from the live log via the view model). When
 * empty, nothing is drawn unless [DevFlags.overlayTrack] is set, in which case numbered calibration
 * boxes are drawn instead — the dev aid used to validate alignment.
 */
@Composable
fun ArenaOverlayTracker(
    grades: List<CardGrade> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
) {
    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(locator) {
        while (true) {
            bounds = locator.locate()
            delay(POLL_MS)
        }
    }
    val b = bounds ?: return

    val wState = rememberWindowState(
        position = WindowPosition(b.x.dp, b.y.dp),
        size = DpSize(b.w.dp, b.h.dp),
    )
    LaunchedEffect(b) {
        wState.position = WindowPosition(b.x.dp, b.y.dp)
        wState.size = DpSize(b.w.dp, b.h.dp)
    }

    // Detect the card grid whenever the window size changes (debounced so a drag-resize doesn't
    // spawn a capture per frame). Null when capture/detection isn't available.
    var grid by remember { mutableStateOf<CardDetector.Grid?>(null) }
    LaunchedEffect(b.w, b.h) {
        delay(CAPTURE_DEBOUNCE_MS)
        grid = withContext(Dispatchers.IO) { capturer.capture()?.let { CardDetector.detect(it) } }
    }

    val hasGrades = grades.isNotEmpty()
    val calibrating = !hasGrades && DevFlags.overlayTrack
    if (!hasGrades && !calibrating) {
        // Nothing to draw (overlay idle between packs) — keep tracking but render no window chrome.
        return
    }

    Window(
        onCloseRequest = {},
        state = wState,
        title = TRACKER_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        // Pass clicks through to Arena (the NSWindow may not exist the instant we open).
        LaunchedEffect(Unit) {
            repeat(15) {
                if (MacOverlay.setClickThrough(TRACKER_TITLE, true)) return@LaunchedEffect
                delay(200)
            }
        }
        Box(Modifier.fillMaxSize()) {
            StatusChip(detected = grid != null)

            val count = if (hasGrades) grades.size
            else System.getProperty("firstpick.packCards")?.toIntOrNull()
                ?: grid?.let { it.cols.size * it.rows.size } ?: 15
            val rects = resolveRects(b, grid, count)
            val bestIndex = grades.maxByOrNull { it.value ?: Double.NEGATIVE_INFINITY }?.index

            for (r in rects) {
                if (hasGrades) {
                    val value = grades.firstOrNull { it.index == r.index }?.value
                    GradeMarker(r, value, isBest = r.index == bestIndex)
                } else {
                    NumberMarker(r) // calibration aid (dev only)
                }
            }
        }
    }
}

/** Card rectangles in window points: CV-detected (scaled from capture pixels) or geometric. */
private fun resolveRects(b: WindowBounds, grid: CardDetector.Grid?, count: Int): List<Rect> =
    if (grid != null) {
        val sx = b.w.toFloat() / grid.imageW
        val sy = b.h.toFloat() / grid.imageH
        grid.cards(count).map { Rect(it.index, (it.x * sx).roundToInt(), (it.y * sy).roundToInt(), (it.w * sx).roundToInt(), (it.h * sy).roundToInt()) }
    } else {
        PackLayout.slots(b.w, b.h, count).map { Rect(it.index, it.x, it.y, it.w, it.h) }
    }

@Composable
private fun GradeMarker(r: Rect, value: Double?, isBest: Boolean) {
    val color = valueTierColor(value)
    Box(
        Modifier
            .offset(r.x.dp, r.y.dp)
            .size(r.w.dp, r.h.dp)
            .border(if (isBest) 3.dp else 2.dp, color, RoundedCornerShape(6.dp))
    ) {
        // Grade pill in the card's top-left corner: letter grade + 0–100 value, tier-colored.
        Column(
            Modifier.align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color(0xE60E1312), RoundedCornerShape(7.dp))
                .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(letterGrade(value), color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                value?.roundToInt()?.toString() ?: "—",
                color = color.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun NumberMarker(r: Rect) {
    val color = Color(0xFFFF5C8A)
    Box(
        Modifier
            .offset(r.x.dp, r.y.dp)
            .size(r.w.dp, r.h.dp)
            .border(2.dp, color, RoundedCornerShape(6.dp))
    ) {
        Box(
            Modifier.align(Alignment.TopStart)
                .background(color, RoundedCornerShape(bottomEnd = 6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text("${r.index + 1}", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusChip(detected: Boolean) {
    Box(
        Modifier.padding(8.dp)
            .background(Color(0xCC0E1312), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            if (detected) "FirstPick ●" else "FirstPick ○",
            color = Color(0xFF6FD4BC),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
