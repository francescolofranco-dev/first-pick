package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",
    val originalIndex: Int = -1,
    val isRoom: Boolean = false,
    val recommendedPair: Boolean = false,
    val grpId: Int = 0,
    val breakdown: com.firstpick.advisor.ValueBreakdown? = null,
    val model: com.firstpick.ui.ModelExplain? = null,
)

/** Exact IDs and card rectangles share one coherent Arena memory observation. */
@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard>,
    draftPool: List<DeckCardCount>,
    sessionKey: String?,
    memoryReader: ArenaMemoryReader,
    locator: ArenaWindowLocator = WindowLocator(),
    onHealthChanged: (OverlayHealth) -> Unit = {},
    onGrantArenaAccess: () -> Unit = {},
) {
    val access by memoryReader.access.collectAsState()
    val loc = remember { locator }
    val session = remember(sessionKey, draftPool) { MemoryPackGuidanceSession() }
    val currentCards by rememberUpdatedState(cards)
    val presentation = remember(session) { MemoryOverlayPresentation<List<OverlayCard>>() }
    val trace = remember(presentation) { OverlayTimingTrace("draft") }
    var frame by remember(presentation) { mutableStateOf(MemoryOverlayFrame<List<OverlayCard>>()) }
    var visible by remember { mutableStateOf(true) }
    var frontmost by remember { mutableStateOf(false) }
    val currentFrontmost by rememberUpdatedState(frontmost)
    LaunchedEffect(presentation) {
        while (true) {
            delay(100)
            frame = presentation.expire(System.nanoTime() / 1_000_000L)
        }
    }
    LaunchedEffect(presentation) {
        while (true) {
            val readStarted = System.nanoTime()
            val read = withContext(Dispatchers.IO) { memoryReader.read() }
            val readEnded = System.nanoTime()
            val now = System.nanoTime() / 1_000_000L
            visible = read !is ArenaMemoryRead.Paused || !read.outsideBuilder
            if (read is ArenaMemoryRead.Ready) visible = false
            frame = when (read) {
                is ArenaMemoryRead.PackReady -> {
                    val validation = withContext(Dispatchers.IO) { session.observe(read.pack, currentCards, draftPool, now) }
                    val identity = read.pack.copy(geometry = null)
                    when (validation) {
                        is MemoryPackValidation.Ready -> presentation.accepted(identity, read.pack.editorSession,
                            validation.cards, read.pack.geometry, now)
                        is MemoryPackValidation.Rejected -> if (validation.settling)
                            presentation.settling(identity, read.pack.editorSession, read.pack.geometry, now, newPack = true)
                        else presentation.clear(now, OverlayHealth(OverlayHealthState.READING, validation.reason))
                    }
                }
                is ArenaMemoryRead.Ready -> {
                    session.invalidate()
                    presentation.clear(now)
                }
                else -> {
                    if (read.resetsPresentation()) session.invalidate()
                    presentation.interrupted(read, now)
                }
            }
            trace.observed(read, frame, readStarted, readEnded)
            delay(if (currentFrontmost) 100 else 400)
        }
    }
    ArenaMemoryOverlayWindow("FirstPick Arena Tracker", frame.health, visible, loc,
        geometry = frame.geometry,
        onForegroundChanged = { frontmost = it }, onHealthChanged = onHealthChanged) { arena ->
        SideEffect { trace.composed(frame, frontmost) }
        Box(Modifier.size(arena.w.dp, arena.h.dp)) {
            val marks = draftScoreMarks(frame.content.orEmpty(), frame.geometry?.inWindow(arena).orEmpty())
            DraftScoreBadges(marks, arena.w.dp, arena.h.dp)
            DraftScoreInteractions(marks, arena)
            OverlayAccessNotice(arena, frame.health, access, onGrantArenaAccess)
            if (marks.isEmpty() && frame.showNotice && !showOverlayAccessNotice(frame.health, access)) Box(Modifier.align(Alignment.TopEnd).padding(24.dp).widthIn(max = (arena.w * .6).dp)
                .background(DeckGuidanceOverlayPalette.PillBackground, RoundedCornerShape(12.dp))
                .border(1.dp, DeckGuidanceOverlayPalette.ReadingAccent, RoundedCornerShape(12.dp)).padding(9.dp)) {
                Text(if (frame.content != null && frame.geometry != null) "Updating card positions…" else frame.health.detail,
                    color = DeckGuidanceOverlayPalette.PillText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun rankedMemoryPicks(cards: List<OverlayCard>): List<OverlayCard> = cards.sortedWith(
    compareByDescending<OverlayCard> { it.recommendedPair }.thenByDescending { it.value ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.originalIndex },
)

internal fun bestRatedOverlayCard(cards: List<OverlayCard>, assigned: Collection<Int>): Int? =
    assigned.filter { cards[it].value != null }.maxByOrNull { cards[it].value!! }
