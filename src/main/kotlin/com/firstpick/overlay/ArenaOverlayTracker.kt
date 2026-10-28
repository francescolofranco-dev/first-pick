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
)

/** Exact IDs and card rectangles share one coherent Arena memory observation. */
@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard>,
    draftPool: List<DeckCardCount>,
    sessionKey: String?,
    memoryReader: ArenaMemoryReader,
    locator: WindowLocator = WindowLocator(),
    onHealthChanged: (OverlayHealth) -> Unit = {},
) {
    val loc = remember { locator }
    val session = remember(sessionKey, cards, draftPool) { MemoryPackGuidanceSession() }
    var verified by remember(session) { mutableStateOf<List<OverlayCard>>(emptyList()) }
    var geometry by remember(session) { mutableStateOf<ArenaCardGeometry?>(null) }
    var notice by remember { mutableStateOf("Waiting for a live draft pack." ) }
    var health by remember { mutableStateOf(OverlayHealth(OverlayHealthState.READING, notice)) }
    var visible by remember { mutableStateOf(true) }
    var frontmost by remember { mutableStateOf(false) }
    val currentFrontmost by rememberUpdatedState(frontmost)
    var lastReadMs by remember { mutableStateOf(0L) }
    LaunchedEffect(session) {
        while (true) {
            delay(100)
            if (System.nanoTime() / 1_000_000L - lastReadMs > 900) {
                verified = emptyList()
                geometry = null
                notice = "Waiting for a fresh live pack reading."
                health = OverlayHealth(OverlayHealthState.READING, notice)
            }
        }
    }
    LaunchedEffect(session) {
        while (true) {
            val read = withContext(Dispatchers.IO) { memoryReader.read() }
            val now = System.nanoTime() / 1_000_000L
            visible = read !is ArenaMemoryRead.Paused || !read.outsideBuilder
            when (read) {
                is ArenaMemoryRead.PackReady -> when (val result = withContext(Dispatchers.IO) { session.observe(read.pack, cards, draftPool, now) }) {
                    is MemoryPackValidation.Ready -> {
                        verified = result.cards
                        geometry = read.pack.geometry
                        notice = "Reading live card positions…"
                        health = if (geometry != null) OverlayHealth(OverlayHealthState.ACTIVE, "The live pack, drafted pool and card positions are synced.")
                            else OverlayHealth(OverlayHealthState.READING, notice)
                    }
                    is MemoryPackValidation.Rejected -> {
                        verified = emptyList()
                        geometry = null
                        notice = result.reason
                        health = OverlayHealth(OverlayHealthState.READING, notice)
                    }
                }
                else -> {
                    verified = emptyList()
                    geometry = null
                    session.invalidate()
                    notice = when (read) {
                        is ArenaMemoryRead.Paused -> read.reason
                        is ArenaMemoryRead.Unavailable -> read.reason
                        else -> "Open the draft's current pack to see live pick advice."
                    }
                    health = if (read is ArenaMemoryRead.Unavailable) memoryReadHealth(read)
                        else OverlayHealth(OverlayHealthState.READING, notice)
                    if (read is ArenaMemoryRead.Ready) visible = false
                }
            }
            lastReadMs = System.nanoTime() / 1_000_000L
            delay(if (currentFrontmost) 150 else 500)
        }
    }
    ArenaMemoryOverlayWindow("FirstPick Arena Tracker", health, visible, loc,
        onForegroundChanged = { frontmost = it }, onHealthChanged = onHealthChanged) { arena ->
        Box(Modifier.size(arena.w.dp, arena.h.dp)) {
            val marks = draftScoreMarks(verified, geometry?.inWindow(arena).orEmpty())
            DraftScoreBadges(marks, arena.w.dp, arena.h.dp)
            if (marks.isEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(24.dp).widthIn(max = (arena.w * .6).dp)
                .background(DeckGuidanceOverlayPalette.PillBackground, RoundedCornerShape(12.dp))
                .border(1.dp, DeckGuidanceOverlayPalette.ReadingAccent, RoundedCornerShape(12.dp)).padding(9.dp)) {
                Text(notice, color = DeckGuidanceOverlayPalette.PillText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
