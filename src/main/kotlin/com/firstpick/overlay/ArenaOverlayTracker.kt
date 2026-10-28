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
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",
    val originalIndex: Int = -1,
    val isRoom: Boolean = false,
    val recommendedPair: Boolean = false,
    val grpId: Int = 0,
)

/** Exact ID matching supplies named advice; unverified screen positions supply no badges. */
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
                        notice = "Live pick advice · Preview"
                        health = OverlayHealth(OverlayHealthState.ACTIVE, "The complete live pack and drafted pool match these recommendations.")
                    }
                    is MemoryPackValidation.Rejected -> {
                        verified = emptyList()
                        notice = result.reason
                        health = OverlayHealth(OverlayHealthState.READING, notice)
                    }
                }
                else -> {
                    verified = emptyList()
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
            Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).widthIn(max = (arena.w * .72).dp)
                .background(DeckGuidanceOverlayPalette.PillBackground, RoundedCornerShape(12.dp))
                .border(1.dp, DeckGuidanceOverlayPalette.ReadingAccent, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(notice, color = DeckGuidanceOverlayPalette.PillText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                for (card in rankedMemoryPicks(verified).take(3)) {
                    val grade = card.value?.let { "${letterGrade(it)} · ${it.roundToInt()}" } ?: "Unrated"
                    Text("${if (card.recommendedPair) "Pick · " else ""}${card.name}  $grade",
                        color = valueTierColor(card.value), fontSize = 13.sp)
                }
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
