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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val CAPTURE_DEBOUNCE_MS = 350L

/** One pack card the overlay should grade: its pick [value] and a reference [imageUrl] for art matching. */
data class OverlayCard(val value: Double?, val imageUrl: String?, val name: String = "")

/** A grade to draw, in window points: either a recognized card [value] or a calibration [number]. */
private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean)

/**
 * The embedded draft overlay: a transparent, click-through window pinned to the live MTG Arena
 * window that draws a pick grade on each pack card — the Untapped-style on-card experience,
 * draft support only.
 *
 * Position/size are tracked cheaply every [POLL_MS] via [WindowLocator] (no permission). The card
 * rectangles come from [CardDetector] run on a [WindowCapture] frame, and each rect is matched to
 * an actual pack card by [CardRecognizer] (Arena draws the pack in a non-derivable order, so we
 * recognize the art rather than assume position). Detection + recognition rerun only when the
 * window size or the pack changes, never on the per-frame position poll.
 *
 * Hides itself whenever Arena isn't the frontmost app, so it doesn't float over other windows.
 * With no [cards] it renders nothing, unless [DevFlags.overlayTrack] is set, in which case it
 * draws numbered calibration boxes for aligning the detector.
 */
@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
) {
    // Stabilize the deps: a default-arg constructor is re-evaluated on every recomposition, which
    // would re-key the poll effect (restarting it) and re-extract the native helper each frame.
    val loc = remember { locator }
    val cap = remember { capturer }
    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(loc) {
        while (true) {
            bounds = withContext(Dispatchers.IO) { loc.locate() } // blocking subprocess, off the UI thread
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

    // Capture → detect → recognize. Reruns only on size or pack change (not the position poll
    // or focus toggle), since the costly capture + art match needn't repeat otherwise. The key
    // uses the card name (always present) since a no-art card's imageUrl can be blank.
    var marks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    val packKey = remember(cards) { cards.joinToString("|") { "${it.name}#${it.imageUrl}" } }
    LaunchedEffect(b.w, b.h, packKey, calibrating) {
        delay(CAPTURE_DEBOUNCE_MS)
        marks = withContext(Dispatchers.IO) { computeMarks(cap, cards, calibrating, b.w, b.h) }
    }

    // Keep the window mounted and toggle visibility, so the native peer + click-through flag
    // persist instead of being torn down/recreated on every focus toggle. Hide it when Arena
    // isn't frontmost or there's nothing to show.
    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating)

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
        LaunchedEffect(Unit) {
            repeat(15) {
                if (MacOverlay.setClickThrough(TRACKER_TITLE, true)) return@LaunchedEffect
                delay(200)
            }
        }
        Box(Modifier.fillMaxSize()) {
            for (m in marks) {
                if (m.number != null) NumberBox(m) else GradeSeal(m)
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
    val frame = capturer.capture() ?: return emptyList()
    if (cards.isNotEmpty()) {
        val grid = CardDetector.detect(frame, cards.size) ?: return emptyList()
        val rects = grid.cards(cards.size)
        val refs = cards.map { c -> c.imageUrl?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) } }
        val assign = CardRecognizer.match(frame, rects, refs)
        val best = cards.indices.maxByOrNull { cards[it].value ?: Double.NEGATIVE_INFINITY }
        val sx = winW.toFloat() / grid.imageW
        val sy = winH.toFloat() / grid.imageH
        return rects.mapNotNull { r ->
            val ci = assign[r.index] ?: return@mapNotNull null
            Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), cards[ci].value, null, ci == best)
        }
    }
    if (calibrating) {
        val grid = CardDetector.detect(frame, 15) ?: return emptyList()
        val sx = winW.toFloat() / grid.imageW
        val sy = winH.toFloat() / grid.imageH
        return grid.cards(15).map { r ->
            Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), null, r.index + 1, false)
        }
    }
    return emptyList()
}

/** Untapped-style grade seal at the card's bottom edge: letter grade + 0–100 value, tier-colored. */
@Composable
private fun GradeSeal(m: Mark) {
    val color = valueTierColor(m.value)
    val d = (m.w * 0.40f).roundToInt().coerceIn(34, 92) // seal diameter ~40% of card width
    val cx = m.x + m.w / 2 - d / 2
    val cy = m.y + m.h - (d * 0.72f).roundToInt() // sit across the bottom edge
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
