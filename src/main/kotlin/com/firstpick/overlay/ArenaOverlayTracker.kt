package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val CAPTURE_DEBOUNCE_MS = 350L

private val DETECTED_COLOR = Color(0xFF38E08B) // green: rectangles found by CV
private val FALLBACK_COLOR = Color(0xFFFF5C8A) // pink: geometric guess (no capture)

/**
 * Overlay spike: a transparent, click-through frame pinned to the live MTG Arena window that
 * marks where each draft-pack card is, so grade badges can sit on the cards.
 *
 * Position/size are tracked cheaply every [POLL_MS] via [WindowLocator] (no permission). The
 * card rectangles come from [CardDetector] run on a [WindowCapture] frame — but only when the
 * window *size* changes (the grid is fixed for a given size), so the costly capture never runs
 * on the per-frame position poll. If capture is unavailable (no Screen Recording permission,
 * off macOS), it falls back to [PackLayout]'s geometric guess so something still shows.
 */
@Composable
fun ArenaOverlayTracker(
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
        Box(Modifier.fillMaxSize().border(3.dp, Color(0xFF6FD4BC))) {
            Box(
                Modifier.align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0xCC19211F))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val mode = if (grid != null) "CV" else "geom"
                Text(
                    "FirstPick overlay · ${b.w}×${b.h} · $mode",
                    color = Color(0xFF6FD4BC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            val g = grid
            if (g != null) {
                // CV-detected rectangles, scaled from capture pixels to window points.
                val sx = b.w.toFloat() / g.imageW
                val sy = b.h.toFloat() / g.imageH
                val count = System.getProperty("firstpick.packCards")?.toIntOrNull()
                    ?: (g.cols.size * g.rows.size)
                for (card in g.cards(count)) {
                    CardMarker(
                        x = (card.x * sx).toInt(), y = (card.y * sy).toInt(),
                        w = (card.w * sx).toInt(), h = (card.h * sy).toInt(),
                        label = card.index + 1, color = DETECTED_COLOR,
                    )
                }
            } else {
                // Fallback: geometric guess (drifts across window sizes) when capture is off.
                val count = System.getProperty("firstpick.packCards")?.toIntOrNull() ?: 15
                for (slot in PackLayout.slots(b.w, b.h, count)) {
                    CardMarker(slot.x, slot.y, slot.w, slot.h, slot.index + 1, FALLBACK_COLOR)
                }
            }
        }
    }
}

@Composable
private fun CardMarker(x: Int, y: Int, w: Int, h: Int, label: Int, color: Color) {
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .size(w.dp, h.dp)
            .border(2.dp, color, RoundedCornerShape(6.dp))
    ) {
        Box(
            Modifier.align(Alignment.TopStart)
                .background(color, RoundedCornerShape(bottomEnd = 6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                "$label",
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
