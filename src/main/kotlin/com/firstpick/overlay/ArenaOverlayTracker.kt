package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.delay

private const val TRACKER_TITLE = "FirstPick Arena Tracker"

/**
 * Overlay spike (Phase 1a): a transparent, click-through frame pinned to the live MTG
 * Arena window, proving the overlay can align to and track the client as it moves/resizes.
 * Renders nothing when Arena isn't on screen. Coordinates from [WindowLocator] are global
 * display points, the same space Compose window positions use, so they should line up
 * (minor calibration — e.g. Arena's title-bar inset — is a later refinement).
 *
 * The frame outlines Arena; the corner badge echoes the live bounds so tracking is obvious.
 */
@Composable
fun ArenaOverlayTracker(locator: WindowLocator = WindowLocator()) {
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
                Text(
                    "FirstPick overlay · ${b.w}×${b.h} @ ${b.x},${b.y}",
                    color = Color(0xFF6FD4BC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val POLL_MS = 300L
