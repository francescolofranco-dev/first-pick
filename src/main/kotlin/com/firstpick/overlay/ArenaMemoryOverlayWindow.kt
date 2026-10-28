package com.firstpick.overlay

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** OS window metadata anchors the overlay; card data never comes from pixels. */
@Composable
internal fun ArenaMemoryOverlayWindow(
    title: String,
    work: OverlayHealth,
    contentVisible: Boolean = true,
    locator: WindowLocator,
    onForegroundChanged: (Boolean) -> Unit,
    onHealthChanged: (OverlayHealth) -> Unit,
    content: @Composable (WindowBounds) -> Unit,
) {
    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    var clickThroughApplied by remember { mutableStateOf(false) }
    var clickThroughFailed by remember { mutableStateOf(false) }
    LaunchedEffect(locator) {
        var lastSeen = 0L
        while (true) {
            val found = withContext(Dispatchers.IO) { locator.locate() }
            val now = System.nanoTime() / 1_000_000L
            if (found != null && found.w > 0 && found.h > 0) {
                bounds = found; lastSeen = now
            } else if (now - lastSeen > 1_000) bounds = null
            delay(200)
        }
    }
    SideEffect { onForegroundChanged(bounds?.frontmost == true) }
    val effective = if (work.state == OverlayHealthState.ACTIVE && !clickThroughApplied)
        OverlayHealth(OverlayHealthState.READING, "Preparing the live overlay.") else work
    OverlayHealthEffect(resolveOverlayHealth(bounds != null, bounds?.frontmost == true,
        clickThroughFailed, effective), onHealthChanged)
    val arena = bounds ?: return
    val state = rememberWindowState(position = WindowPosition(arena.x.dp, arena.y.dp),
        size = DpSize(arena.w.dp, arena.h.dp))
    LaunchedEffect(arena.x, arena.y, arena.w, arena.h) {
        state.position = WindowPosition(arena.x.dp, arena.y.dp)
        state.size = DpSize(arena.w.dp, arena.h.dp)
    }
    val visible = arena.frontmost && contentVisible && !clickThroughFailed
    Window(onCloseRequest = {}, visible = visible, state = state, title = title,
        transparent = true, undecorated = true, alwaysOnTop = true, focusable = false, resizable = false) {
        DisposableEffect(window) {
            clickThroughApplied = false
            clickThroughFailed = false
            onDispose { clickThroughApplied = false }
        }
        LaunchedEffect(window, visible) {
            if (!visible || clickThroughApplied) return@LaunchedEffect
            repeat(40) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }) {
                    clickThroughApplied = true; clickThroughFailed = false
                    return@LaunchedEffect
                }
                delay(150)
            }
            clickThroughFailed = true
        }
        content(arena)
    }
}

internal fun memoryReadHealth(read: ArenaMemoryRead): OverlayHealth = when (read) {
    is ArenaMemoryRead.Ready -> OverlayHealth(OverlayHealthState.ACTIVE, "Complete live deck is synced to Arena.")
    is ArenaMemoryRead.PackReady -> OverlayHealth(OverlayHealthState.READING, "Verifying the live draft pack.")
    is ArenaMemoryRead.Paused -> OverlayHealth(if (read.outsideBuilder) OverlayHealthState.INACTIVE else OverlayHealthState.READING, read.reason)
    is ArenaMemoryRead.Unavailable -> OverlayHealth(when (read.kind) {
        MemoryUnavailableKind.ACCESS_REQUIRED -> OverlayHealthState.ACCESS_REQUIRED
        MemoryUnavailableKind.UNSUPPORTED -> OverlayHealthState.UNSUPPORTED_LAYOUT
        MemoryUnavailableKind.WAITING -> OverlayHealthState.READING
    }, read.reason)
}
