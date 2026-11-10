package com.firstpick.overlay

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firstpick.model.DraftPhase
import com.firstpick.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Requires an observed live draft, so importing a completed log never opens a modal. */
internal class DeckRecommendationTrigger {
    private var draftingSession: String? = null
    private var hasDraft = false
    private var offered = false

    fun observe(phase: DraftPhase, session: String?, ready: Boolean, enabled: Boolean, simulating: Boolean): Boolean {
        if (phase == DraftPhase.IDLE || simulating) {
            hasDraft = false
            offered = false
            return false
        }
        if (phase == DraftPhase.DRAFTING) {
            offered = false
            draftingSession = session
            hasDraft = true
            return false
        }
        if (!hasDraft || draftingSession != session || offered || !ready) return false
        offered = true
        return enabled
    }
}

@Composable
internal fun DeckRecommendationsOverlay(
    options: List<DeckOptionUi>,
    automatic: Boolean,
    onAutomaticChanged: (Boolean) -> Unit,
    onUseDeck: (DeckOptionUi) -> Unit,
    onClose: () -> Unit,
    locator: ArenaWindowLocator = remember { WindowLocator() },
) {
    var arena by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(locator) {
        while (true) {
            arena = withContext(Dispatchers.IO) { locator.locate() }
            delay(200)
        }
    }
    val bounds = arena?.takeIf { it.frontmost && it.w > 0 && it.h > 0 } ?: return
    val width = minOf(1220f, bounds.w * .94f)
    val height = minOf(880f, bounds.h * .9f)
    OverlayPanelWindow("FirstPick Deck Recommendations", bounds.x + (bounds.w - width) / 2,
        bounds.y + (bounds.h - height) / 2, width, height) {
        DeckRecommendationsContent(options, automatic, onAutomaticChanged, onUseDeck, onClose)
    }
}

@Composable
internal fun DeckRecommendationsContent(
    options: List<DeckOptionUi>,
    automatic: Boolean,
    onAutomaticChanged: (Boolean) -> Unit,
    onUseDeck: (DeckOptionUi) -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize().background(AppTheme.colors.background, shape)
        .border(1.dp, AppTheme.colors.borderDefault, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Deck recommendations", style = AppTheme.typography.paneTitle, color = AppTheme.colors.textPrimary)
                Text("Draft complete · Choose a build for guidance in Arena", style = AppTheme.typography.caption,
                    color = AppTheme.colors.textSecondary)
            }
            AppButton(onClick = onClose, variant = AppButtonVariant.Subtle) {
                Text("Close", color = AppTheme.colors.textPrimary)
            }
        }
        // Horizontal scrolling keeps each build legible on smaller Arena windows.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardWidth = ((maxWidth - 28.dp) / options.size.coerceIn(1, 3)).coerceAtLeast(250.dp)
            val horizontalScroll = rememberLazyListState()
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxSize().padding(bottom = 12.dp),
                state = horizontalScroll, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(options) { index, deck ->
                    RecommendationColumn(deck, index == 0, Modifier.width(cardWidth).fillParentMaxHeight(), onUseDeck)
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(horizontalScroll),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = automatic, onCheckedChange = onAutomaticChanged)
            Text("Show automatically after the last pick", style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun RecommendationColumn(deck: DeckOptionUi, recommended: Boolean, modifier: Modifier, onUseDeck: (DeckOptionUi) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.background(AppTheme.colors.surface, shape).border(1.dp,
        if (recommended) AppTheme.colors.accent else AppTheme.colors.borderDefault, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipRow(deck.colors.toList(), size = 18.dp)
            Spacer(Modifier.weight(1f))
            Text(deck.powerLabel, style = AppTheme.typography.caption, color = AppTheme.colors.accent)
        }
        Text(deck.title, style = AppTheme.typography.cardTitle, color = AppTheme.colors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (recommended) "RECOMMENDED" else "ALTERNATIVE", style = AppTheme.typography.sectionHeader,
            color = if (recommended) AppTheme.colors.accent else AppTheme.colors.textMuted)
        Text("${(deck.spells + deck.lands).sumOf { it.count }} cards · ${deck.creatures} creatures · ${deck.removal} removal",
            style = AppTheme.typography.micro, color = AppTheme.colors.textSecondary)
        val scroll = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = scroll) {
                itemsIndexed(deck.spells + deck.lands) { index, spell ->
                    if (index == deck.spells.size && deck.lands.isNotEmpty()) {
                        Text("LANDS", style = AppTheme.typography.sectionHeader, color = AppTheme.colors.textMuted,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    RecommendationCardRow(spell)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        CopyCardsButton(deck.spells + deck.lands, "${deck.title} cards")
        AppButton(onClick = { onUseDeck(deck) }, modifier = Modifier.fillMaxWidth(), variant = AppButtonVariant.Primary) {
            Text("Use this deck", color = AppTheme.colors.textOnAccent)
        }
    }
}

@Composable
private fun RecommendationCardRow(card: DeckSpellUi) {
    var image by remember(card.imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(card.imageUrl) { image = card.imageUrl?.let { CardImageLoader.load(it) } }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(34.dp, 42.dp).clip(RoundedCornerShape(3.dp)).background(AppTheme.colors.background)) {
            image?.let { Image(it, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            Text(card.name, style = AppTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                color = AppTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ManaCost(card.manaCost, isLand = card.isLand)
        }
        Text("${card.count}", style = AppTheme.typography.monoSmall, color = AppTheme.colors.textPrimary)
    }
}
