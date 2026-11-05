package com.firstpick.overlay

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Counts, identities and highlight bounds share an authorized Arena observation. */
@Composable
fun ArenaDeckBuilderOverlay(
    target: List<DeckCardCount>,
    draftPool: List<DeckCardCount>,
    sessionKey: String? = null,
    locator: ArenaWindowLocator = WindowLocator(),
    memoryReader: ArenaMemoryReader,
    onHealthChanged: (OverlayHealth) -> Unit = {},
    onGrantArenaAccess: () -> Unit = {},
    onGuidanceChanged: (DeckGuidanceOverlayModel?) -> Unit = {},
) {
    val access by memoryReader.access.collectAsState()
    val loc = remember { locator }
    val session = remember(sessionKey, draftPool) { MemoryDeckGuidanceSession() }
    val presentation = remember(session, target) { MemoryOverlayPresentation<VerifiedDeckOverlay>() }
    val trace = remember(presentation) { OverlayTimingTrace("deck") }
    var frame by remember(presentation) { mutableStateOf(MemoryOverlayFrame<VerifiedDeckOverlay>()) }
    val screenVisibility = remember(presentation) { OverlayScreenVisibility(OverlayScreen.DECK) }
    var contentVisible by remember(presentation) { mutableStateOf(screenVisibility.visible) }
    var frontmost by remember { mutableStateOf(false) }
    val currentFrontmost by rememberUpdatedState(frontmost)
    val guidanceCallback by rememberUpdatedState(onGuidanceChanged)
    LaunchedEffect(frame.content?.model) { guidanceCallback(frame.content?.model) }
    DisposableEffect(presentation) { onDispose { guidanceCallback(null) } }
    LaunchedEffect(presentation) {
        while (true) {
            delay(100)
            frame = presentation.expire(System.nanoTime() / 1_000_000L)
        }
    }
    LaunchedEffect(presentation) {
        runOverlayReadLoop({ currentFrontmost }) {
            val readStarted = System.nanoTime()
            val read = withContext(Dispatchers.IO) { memoryReader.read() }
            val readEnded = System.nanoTime()
            val now = System.nanoTime() / 1_000_000L
            contentVisible = screenVisibility.observe(read)
            frame = when (read) {
                is ArenaMemoryRead.Ready -> {
                    val validation = withContext(Dispatchers.IO) { session.observe(read.deck, draftPool, now) }
                    val identity = read.deck.copy(geometry = null)
                    when (validation) {
                        is MemoryDeckValidation.Ready -> presentation.accepted(identity, read.deck.editorSession,
                            VerifiedDeckOverlay(memoryGuidanceModel(target, validation.deck), validation.names), read.deck.geometry, now)
                        is MemoryDeckValidation.Rejected -> if (validation.settling)
                            presentation.settling(identity, read.deck.editorSession, read.deck.geometry, now)
                        else presentation.clear(now, OverlayHealth(OverlayHealthState.READING, validation.reason))
                    }
                }
                is ArenaMemoryRead.PackReady -> {
                    session.invalidate()
                    presentation.clear(now)
                }
                else -> {
                    if (read.resetsPresentation()) session.invalidate()
                    presentation.interrupted(read, now)
                }
            }
            trace.observed(read, frame, readStarted, readEnded)
        }
    }
    ArenaMemoryOverlayWindow("FirstPick Deck Guidance", frame.health, contentVisible, loc,
        accessNoticeVisible = showOverlayAccessNotice(frame.health, access),
        geometry = frame.geometry,
        onForegroundChanged = { frontmost = it }, onHealthChanged = onHealthChanged) { arena ->
        SideEffect { trace.composed(frame, frontmost) }
        OverlayAccessNotice(arena, frame.health, access, onGrantArenaAccess)
        if (!contentVisible) return@ArenaMemoryOverlayWindow
        val verified = frame.content
        val model = if (verified != null) {
            verified.model.withMemoryMarks(frame.geometry?.inWindow(arena).orEmpty(), verified.names)
        } else if (frame.showNotice && !showOverlayAccessNotice(frame.health, access)) {
            DeckGuidanceOverlayModel(DeckGuidanceStatus.READING, notice = frame.health.detail)
        } else null
        if (model != null) DeckGuidanceOverlay(model, arena.w.dp, arena.h.dp)
    }
}
