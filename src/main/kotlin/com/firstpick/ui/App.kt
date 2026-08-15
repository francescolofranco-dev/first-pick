package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase

enum class AppScreen { DRAFT_POOL, DECK_BUILDER, GUIDES }

@Composable
fun App(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    onToggleOverlay: () -> Unit,
    committedDeck: DeckOptionUi? = null,
    onUseDeck: (DeckOptionUi) -> Unit = {},
    onStopGuidance: () -> Unit = {},
    onSelectFormat: (String) -> Unit = {},
    onSimulate: (String) -> Unit = {},
    onStopSim: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onOpenGuideSource: (String) -> Unit = {},
) {
    var currentScreen by remember(state.phase) {
        mutableStateOf(if (state.phase == DraftPhase.COMPLETE) AppScreen.DECK_BUILDER else AppScreen.DRAFT_POOL)
    }

    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Header(state, isOverlayOpen, onToggleOverlay, onSelectFormat, onStopSim, onTogglePause)

                val deckTabEnabled = state.deckOptions.isNotEmpty() || state.deckSoFar != null
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TabRow(
                        selectedTabIndex = currentScreen.ordinal,
                        modifier = Modifier.width(440.dp).clip(RoundedCornerShape(8.dp)),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Tab(
                            selected = currentScreen == AppScreen.DRAFT_POOL,
                            onClick = { currentScreen = AppScreen.DRAFT_POOL },
                            text = { Text("Draft pool", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        )
                        Tab(
                            selected = currentScreen == AppScreen.DECK_BUILDER,
                            onClick = { currentScreen = AppScreen.DECK_BUILDER },
                            enabled = deckTabEnabled,
                            text = {
                                val label = if (state.deckOptions.isNotEmpty()) "Deck builder" else "Deck so far"
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            },
                        )
                        Tab(
                            selected = currentScreen == AppScreen.GUIDES,
                            onClick = { currentScreen = AppScreen.GUIDES },
                            text = { Text("Guides", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (currentScreen) {
                            AppScreen.GUIDES -> GuidePane(state, onOpenGuideSource)
                            AppScreen.DECK_BUILDER -> when {
                                state.deckOptions.isNotEmpty() -> DeckBuilderPane(
                                    options = state.deckOptions,
                                    committedDeck = committedDeck,
                                    onUseDeck = onUseDeck,
                                    onStopGuidance = onStopGuidance,
                                )
                                state.deckSoFar != null -> DeckSoFarPane(state.deckSoFar, state.deckSoFarCuts)
                                else -> PackPane(
                                    state = state,
                                    onSimulate = onSimulate,
                                    onOpenGuide = { currentScreen = AppScreen.GUIDES },
                                )
                            }
                            AppScreen.DRAFT_POOL -> PackPane(
                                state = state,
                                onSimulate = onSimulate,
                                onOpenGuide = { currentScreen = AppScreen.GUIDES },
                            )
                        }
                    }
                    if (currentScreen == AppScreen.DRAFT_POOL && (state.poolSize > 0 || state.packCards.isNotEmpty())) {
                        Spacer(Modifier.width(12.dp))
                        Sidebar(state)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SynergyTierBadge(tier: String) {
    val accent = tierAccent(tier)
    val label = when (tier) {
        "model" -> "◆ AI picks"
        "researched" -> "✦ Deep synergy"
        else -> "• Data synergy"
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
internal fun tierAccent(tier: String) = when (tier) {
    "researched" -> MaterialTheme.colorScheme.primary
    "model" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun Header(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    onToggleOverlay: () -> Unit,
    onSelectFormat: (String) -> Unit,
    onStopSim: () -> Unit,
    onTogglePause: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FirstPick", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                if (state.laneColors.isNotEmpty()) PipRow(state.laneColors)
                state.synergyTier?.let {
                    Spacer(Modifier.width(10.dp))
                    SynergyTierBadge(it)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(state.headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            state.dataError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.simulating) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .clickable(onClick = onTogglePause)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (state.simPaused) "▶ Resume demo" else "❚❚ Pause demo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f))
                        .clickable(onClick = onStopSim)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "✕ Exit demo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            FormatSelector(state.ratingsFormatChoice, state.format, onSelectFormat)
            if (state.phase != DraftPhase.COMPLETE) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isOverlayOpen) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable(onClick = onToggleOverlay)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (isOverlayOpen) "Hide overlay" else "Show overlay",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverlayOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatSelector(current: String, detected: DraftFormat, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val lockedToEvent = detected == DraftFormat.SEALED
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !lockedToEvent) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Data: ${RatingsFormat.displayLabel(current, detected)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!lockedToEvent) {
                Spacer(Modifier.width(4.dp))
                Text("▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded && !lockedToEvent, onDismissRequest = { expanded = false }) {
            RatingsFormat.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(RatingsFormat.label(choice), fontSize = 12.sp) },
                    onClick = {
                        onSelect(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}
