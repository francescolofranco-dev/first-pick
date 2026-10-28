package com.firstpick.overlay

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Counts and identities come exclusively from the authorized Arena reader. */
@Composable
fun ArenaDeckBuilderOverlay(
    target: List<DeckCardCount>,
    draftPool: List<DeckCardCount>,
    sessionKey: String? = null,
    locator: WindowLocator = WindowLocator(),
    memoryReader: ArenaMemoryReader,
    onHealthChanged: (OverlayHealth) -> Unit = {},
) {
    val loc = remember { locator }
    val session = remember(sessionKey, draftPool) { MemoryDeckGuidanceSession() }
    var model by remember(session, target) { mutableStateOf(DeckGuidanceOverlayModel(DeckGuidanceStatus.READING)) }
    var health by remember { mutableStateOf(OverlayHealth(OverlayHealthState.READING, "Reading the live deck.")) }
    var contentVisible by remember { mutableStateOf(true) }
    var frontmost by remember { mutableStateOf(false) }
    val currentFrontmost by rememberUpdatedState(frontmost)
    var lastReadingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(session) {
        while (true) {
            delay(100)
            if (System.nanoTime() / 1_000_000L - lastReadingMs > 900) {
                model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING)
                health = OverlayHealth(OverlayHealthState.READING, "Waiting for a fresh live deck reading.")
            }
        }
    }
    LaunchedEffect(session, target) {
        model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING)
        while (true) {
            val read = withContext(Dispatchers.IO) { memoryReader.read() }
            val now = System.nanoTime() / 1_000_000L
            contentVisible = read !is ArenaMemoryRead.Paused || !read.outsideBuilder
            if (read is ArenaMemoryRead.PackReady) contentVisible = false
            health = memoryReadHealth(read)
            model = when (read) {
                is ArenaMemoryRead.Ready -> when (val verified = withContext(Dispatchers.IO) { session.observe(read.deck, draftPool, now) }) {
                    is MemoryDeckValidation.Ready -> memoryGuidanceModel(target, verified.deck)
                    is MemoryDeckValidation.Rejected -> {
                        health = OverlayHealth(OverlayHealthState.READING, verified.reason)
                        DeckGuidanceOverlayModel(DeckGuidanceStatus.READING, notice = verified.reason)
                    }
                }
                else -> {
                    session.invalidate()
                    val reason = when (read) {
                        is ArenaMemoryRead.Paused -> read.reason
                        is ArenaMemoryRead.Unavailable -> read.reason
                        else -> "Waiting for a fresh live deck reading."
                    }
                    DeckGuidanceOverlayModel(DeckGuidanceStatus.READING, notice = reason)
                }
            }
            lastReadingMs = System.nanoTime() / 1_000_000L
            delay(if (currentFrontmost) 150 else 500)
        }
    }
    ArenaMemoryOverlayWindow("FirstPick Deck Guidance", health, contentVisible, loc,
        onForegroundChanged = { frontmost = it }, onHealthChanged = onHealthChanged) { arena ->
        DeckGuidanceOverlay(model, arena.w.dp, arena.h.dp)
    }
}
