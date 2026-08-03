package com.firstpick.overlay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.core.Log
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DECK_OVERLAY_TAG = "DeckOverlay"
private const val DECK_OVERLAY_TITLE = "FirstPick Deck Guidance"
private const val WINDOW_POLL_MS = 300L
private const val WINDOW_GRACE_MS = 1_000L
private const val CLICK_THROUGH_ATTEMPTS = 40
private const val CLICK_THROUGH_RETRY_MS = 150L

/** Transparent, non-interactive guidance attached directly to Arena's window. */
@Composable
fun ArenaDeckBuilderOverlay(
    target: List<DeckCardCount>,
    draftPool: List<DeckCardCount>,
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
    ocr: VisionOcr = VisionOcr(),
) {
    val loc = remember { locator }
    val cap = remember { capturer }
    val reader = remember { ocr }
    val poolKey = remember(draftPool) { draftPool.stableCountKey() }
    val targetKey = remember(target) { target.stableCountKey() }
    val session = remember(poolKey) { DeckGuidanceSession(target, draftPool) }
    SideEffect { session.updateTarget(target) }

    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(loc) {
        var lastSeen = 0L
        while (true) {
            val located = withContext(Dispatchers.IO) { loc.locate() }
            val now = System.currentTimeMillis()
            if (located != null && located.w > 0 && located.h > 0) {
                bounds = located
                lastSeen = now
            } else if (now - lastSeen > WINDOW_GRACE_MS) {
                bounds = null
            }
            delay(WINDOW_POLL_MS)
        }
    }

    val arena = bounds ?: return
    val windowState = rememberWindowState(
        position = WindowPosition(arena.x.dp, arena.y.dp),
        size = DpSize(arena.w.dp, arena.h.dp),
    )
    LaunchedEffect(arena.x, arena.y, arena.w, arena.h) {
        windowState.position = WindowPosition(arena.x.dp, arena.y.dp)
        windowState.size = DpSize(arena.w.dp, arena.h.dp)
    }

    var presentation by remember(poolKey) { mutableStateOf(DeckOverlayPresentationState.initial()) }

    val arenaFrontmost by rememberUpdatedState(arena.frontmost)
    LaunchedEffect(poolKey, targetKey, arena.w, arena.h) {
        presentation = DeckOverlayPresentationState.initial()
        while (true) {
            // Focus changes only hide the Window; keep the trusted model so a
            // transient locator sample does not force an OCR reacquisition.
            if (!arenaFrontmost) {
                delay(WINDOW_POLL_MS)
                continue
            }

            val frameStartedNs = System.nanoTime()
            val attempt = withContext(Dispatchers.IO) {
                val image = cap.capture() ?: return@withContext DeckOverlayFrameAttempt.ReaderFailure
                val text = reader.recognize(image) ?: return@withContext DeckOverlayFrameAttempt.ReaderFailure
                val screen = DeckBuilderScreenInterpreter.interpret(text)
                when {
                    screen != null && isSupportedLimitedDeckBuilder(screen) ->
                        DeckOverlayFrameAttempt.Recognized(
                            model = session.observe(image, screen),
                            layout = screen.overlayLayoutSnapshot(),
                        )

                    DeckBuilderScreenInterpreter.detectShell(text)?.minimumDeckSize == DeckGuidance.LIMITED_DECK_SIZE ->
                        DeckOverlayFrameAttempt.SupportedBuilderShell

                    else -> DeckOverlayFrameAttempt.OutsideBuilder
                }
            }
            val update = DeckOverlayPresentationReducer.reduce(
                state = presentation,
                attempt = attempt,
                nowMs = System.nanoTime() / 1_000_000L,
            )
            presentation = update.state
            logDeckOverlayTransitions(update.transitions)

            val processingElapsedMs = ((System.nanoTime() - frameStartedNs) / 1_000_000L).coerceAtLeast(0L)
            delay(deckOverlayPollDelayMs(processingElapsedMs))
        }
    }

    var clickThroughFailed by remember { mutableStateOf(false) }
    val visible = arena.frontmost && presentation.contentVisible && !clickThroughFailed
    Window(
        onCloseRequest = {},
        visible = visible,
        state = windowState,
        title = DECK_OVERLAY_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        var clickThroughApplied by remember { mutableStateOf(false) }
        LaunchedEffect(visible) {
            if (!visible || clickThroughApplied) return@LaunchedEffect
            repeat(CLICK_THROUGH_ATTEMPTS) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }) {
                    clickThroughApplied = true
                    clickThroughFailed = false
                    return@LaunchedEffect
                }
                delay(CLICK_THROUGH_RETRY_MS)
            }
            clickThroughFailed = true
            Log.warn(DECK_OVERLAY_TAG, "click-through could not be applied; hiding deck guidance")
        }

        DeckGuidanceOverlay(
            model = presentation.model,
            viewportWidth = arena.w.dp,
            viewportHeight = arena.h.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun List<DeckCardCount>.stableCountKey(): String =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .joinToString("|") { "${it.name}:${it.count}:${it.isBasicLand}" }

internal fun isSupportedLimitedDeckBuilder(screen: DeckBuilderScreenObservation): Boolean =
    screen.minimumDeckSize == DeckGuidance.LIMITED_DECK_SIZE

private fun logDeckOverlayTransitions(transitions: List<DeckOverlayPresentationTransition>) {
    for (transition in transitions) {
        when (transition) {
            DeckOverlayPresentationTransition.Acquired ->
                Log.info(DECK_OVERLAY_TAG, "Limited deck builder acquired")

            is DeckOverlayPresentationTransition.Unreadable -> {
                val reason = when (transition.reason) {
                    DeckOverlayUnreadableReason.CAPTURE_OR_OCR -> "capture/OCR is temporarily unavailable"
                    DeckOverlayUnreadableReason.ROWS_OBSCURED -> "deck rows are obscured while the builder shell remains visible"
                    DeckOverlayUnreadableReason.GUIDANCE_UNAVAILABLE -> "visible deck counts are temporarily ambiguous"
                }
                val action = if (transition.retainingLastGood) {
                    "retaining the last trusted guidance"
                } else {
                    "waiting for a trusted deck reading"
                }
                Log.warn(DECK_OVERLAY_TAG, "$reason; $action")
            }

            is DeckOverlayPresentationTransition.GraceExpired ->
                Log.warn(DECK_OVERLAY_TAG, "deck reading remained unavailable beyond grace; clearing stale marks")

            DeckOverlayPresentationTransition.Recovered ->
                Log.info(DECK_OVERLAY_TAG, "deck reading recovered")

            DeckOverlayPresentationTransition.Lost ->
                Log.info(DECK_OVERLAY_TAG, "Limited deck builder lost; hiding guidance")
        }
    }
}
