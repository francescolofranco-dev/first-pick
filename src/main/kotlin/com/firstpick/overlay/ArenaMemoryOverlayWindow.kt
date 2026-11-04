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
    locator: ArenaWindowLocator,
    accessNoticeVisible: Boolean = false,
    geometry: ArenaCardGeometry? = null,
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
            } else {
                // Retain geometry briefly, but never retain permission to show over another app.
                bounds = bounds?.copy(frontmost = false)
                if (now - lastSeen > 1_000) bounds = null
            }
            delay(200)
        }
    }
    SideEffect { onForegroundChanged(bounds?.frontmost == true) }
    val effective = when {
        work.state == OverlayHealthState.ACTIVE && !clickThroughApplied ->
            OverlayHealth(OverlayHealthState.READING, "Preparing the live overlay.")
        work.state == OverlayHealthState.ACTIVE && geometry != null && geometry.cards.isNotEmpty() &&
            bounds?.let { geometry.inWindow(it).isEmpty() } == true ->
            OverlayHealth(OverlayHealthState.READING, "Updating positions for Arena's window size…")
        else -> work
    }
    OverlayHealthEffect(resolveOverlayHealth(bounds != null, bounds?.frontmost == true,
        clickThroughFailed, effective), onHealthChanged)
    val arena = bounds ?: return
    val state = rememberWindowState(position = WindowPosition(arena.x.dp, arena.y.dp),
        size = DpSize(arena.w.dp, arena.h.dp))
    LaunchedEffect(arena.x, arena.y, arena.w, arena.h) {
        state.position = WindowPosition(arena.x.dp, arena.y.dp)
        state.size = DpSize(arena.w.dp, arena.h.dp)
    }
    // Authorization must be reachable before memory access can verify a screen.
    val visible = arena.frontmost && (contentVisible || accessNoticeVisible) && !clickThroughFailed
    // Compose suspends a hidden window's content, so its independent input windows
    // would otherwise remain visible. Dispose the whole window tree on focus loss.
    if (!visible) return
    Window(onCloseRequest = {}, state = state, title = title,
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
        if (visible && clickThroughApplied) content(arena)
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
