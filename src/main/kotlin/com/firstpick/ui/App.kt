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
import com.firstpick.model.DraftPhase

enum class AppScreen { DRAFT_POOL, DECK_BUILDER }

@Composable
fun App(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    onToggleOverlay: () -> Unit,
    onSelectFormat: (String) -> Unit = {},
    onSimulate: (String) -> Unit = {},
    onStopSim: () -> Unit = {},
    onTogglePause: () -> Unit = {},
) {
    var currentScreen by remember(state.phase) {
        mutableStateOf(if (state.phase == DraftPhase.COMPLETE) AppScreen.DECK_BUILDER else AppScreen.DRAFT_POOL)
    }

    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Header(state, isOverlayOpen, onToggleOverlay, onSelectFormat, onStopSim, onTogglePause)

                if (state.deckOptions.isNotEmpty() || state.poolSize > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TabRow(
                            selectedTabIndex = if (currentScreen == AppScreen.DRAFT_POOL) 0 else 1,
                            modifier = Modifier.width(300.dp).clip(RoundedCornerShape(8.dp)),
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
                                enabled = state.deckOptions.isNotEmpty(),
                                text = { Text("Deck builder", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (currentScreen == AppScreen.DECK_BUILDER && state.deckOptions.isNotEmpty()) {
                            DeckBuilderPane(state.deckOptions)
                        } else {
                            PackPane(state, onSimulate)
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
    val researched = tier == "researched"
    val accent = when {
        researched || tier == "model" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val label = when {
        tier == "model" -> "◆ AI picks"
        researched -> "✦ Deep synergy"
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
            FormatSelector(state.ratingsFormatChoice, onSelectFormat)
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

@Composable
private fun FormatSelector(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Data: ${RatingsFormat.label(current)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
