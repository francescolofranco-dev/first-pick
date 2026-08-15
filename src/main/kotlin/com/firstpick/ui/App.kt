package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase
import com.firstpick.overlay.OverlayHealth
import com.firstpick.overlay.OverlayHealthState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AppScreen { DRAFT_POOL, DECK_BUILDER, GUIDES }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    overlayHealth: OverlayHealth = OverlayHealth(OverlayHealthState.INACTIVE),
    onToggleOverlay: () -> Unit,
    committedDeck: DeckOptionUi? = null,
    onUseDeck: (DeckOptionUi) -> Unit = {},
    onStopGuidance: () -> Unit = {},
    onSelectFormat: (String) -> Unit = {},
    onSimulate: (String) -> Unit = {},
    onStopSim: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onOpenGuideSource: (String) -> Unit = {},
    onRetryRatings: () -> Unit = {},
) {
    var currentScreen by remember(state.phase) {
        mutableStateOf(if (state.phase == DraftPhase.COMPLETE) AppScreen.DECK_BUILDER else AppScreen.DRAFT_POOL)
    }

    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 680.dp
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Header(
                        state,
                        isOverlayOpen,
                        overlayHealth,
                        isOverlayOpen || committedDeck != null,
                        onToggleOverlay,
                        onSelectFormat,
                        onStopSim,
                        onTogglePause,
                        onRetryRatings,
                        compact,
                    )

                    val deckTabEnabled = state.deckOptions.isNotEmpty() || state.deckSoFar != null
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TabRow(
                            selectedTabIndex = currentScreen.ordinal,
                            modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp).clip(RoundedCornerShape(8.dp)),
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
                    val showSidebar = currentScreen == AppScreen.DRAFT_POOL &&
                        (state.poolSize > 0 || state.packCards.isNotEmpty())
                    if (compact && showSidebar) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(0.62f).fillMaxWidth()) {
                                PackPane(
                                    state = state,
                                    onSimulate = onSimulate,
                                    onOpenGuide = { currentScreen = AppScreen.GUIDES },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Sidebar(state, Modifier.fillMaxWidth().weight(0.38f))
                        }
                    } else {
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
                            if (showSidebar) {
                                Spacer(Modifier.width(12.dp))
                                Sidebar(state)
                            }
                        }
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
    overlayHealth: OverlayHealth,
    overlayRelevant: Boolean,
    onToggleOverlay: () -> Unit,
    onSelectFormat: (String) -> Unit,
    onStopSim: () -> Unit,
    onTogglePause: () -> Unit,
    onRetryRatings: () -> Unit,
    compact: Boolean,
) {
    val brand = @Composable {
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
            RatingsHealthStatus(state, onRetryRatings)
            if (overlayRelevant) {
                Spacer(Modifier.height(4.dp))
                OverlayHealthStatus(overlayHealth)
            }
        }
    }

    val actions = @Composable {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.simulating) {
                FilledTonalButton(
                    onClick = onTogglePause,
                    contentPadding = HeaderButtonPadding,
                ) {
                    Text(
                        text = if (state.simPaused) "▶ Resume demo" else "❚❚ Pause demo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onStopSim,
                    contentPadding = HeaderButtonPadding,
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
                FilledTonalButton(
                    onClick = onToggleOverlay,
                    contentPadding = HeaderButtonPadding,
                    modifier = Modifier.semantics {
                        stateDescription = if (isOverlayOpen) "Overlay shown" else "Overlay hidden"
                    },
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

    if (compact) {
        Column(Modifier.fillMaxWidth()) {
            brand()
            Spacer(Modifier.height(8.dp))
            actions()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { brand() }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.widthIn(max = 380.dp)) { actions() }
        }
    }
}

@Composable
private fun RatingsHealthStatus(state: DraftUiState, onRetry: () -> Unit) {
    if (state.ratingsDataStatus == RatingsDataStatus.IDLE) return
    val color = when (state.ratingsDataStatus) {
        RatingsDataStatus.FRESH -> MaterialTheme.colorScheme.primary
        RatingsDataStatus.CACHED -> MaterialTheme.colorScheme.secondary
        RatingsDataStatus.LOADING -> MaterialTheme.colorScheme.secondary
        RatingsDataStatus.STALE_CACHE -> MaterialTheme.colorScheme.tertiary
        RatingsDataStatus.ERROR -> MaterialTheme.colorScheme.error
        RatingsDataStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = color.copy(alpha = 0.13f),
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = ratingsDataDescription(state)
                },
            ) {
                Text(
                    ratingsDataDescription(state),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (state.canRetryRatings) {
                TextButton(onClick = onRetry) {
                    Text("Retry data", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        state.ratingsDataWarning?.let {
            Text(it, color = color, fontSize = 10.sp)
        }
    }
}

internal fun ratingsDataDescription(state: DraftUiState): String {
    val status = when (state.ratingsDataStatus) {
        RatingsDataStatus.IDLE -> "Idle"
        RatingsDataStatus.LOADING -> "Loading"
        RatingsDataStatus.FRESH -> "Fresh"
        RatingsDataStatus.CACHED -> "Cached"
        RatingsDataStatus.STALE_CACHE -> "Stale cache"
        RatingsDataStatus.ERROR -> "Unavailable"
    }
    val evidence = if (state.ratingsCardCount > 0) {
        " · ${state.ratingsReliableCardCount}/${state.ratingsCardCount} reliable" +
            if (state.ratingsMedianGamesPerCard > 0) " · median ${state.ratingsMedianGamesPerCard} games" else ""
    } else {
        ""
    }
    val updated = state.ratingsLastUpdated?.let { " · ${RatingsTimeFormatter.format(it)}" }.orEmpty()
    return "17Lands · $status$evidence$updated"
}

private val RatingsTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun OverlayHealthStatus(health: OverlayHealth) {
    val color = when (health.state) {
        OverlayHealthState.ACTIVE -> MaterialTheme.colorScheme.primary
        OverlayHealthState.READING -> MaterialTheme.colorScheme.secondary
        OverlayHealthState.WAITING_FOR_ARENA, OverlayHealthState.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
        OverlayHealthState.CAPTURE_UNAVAILABLE,
        OverlayHealthState.UNSUPPORTED_LAYOUT,
        OverlayHealthState.CLICK_THROUGH_FAILED,
        -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = "Overlay ${health.state.label}. ${health.detail}"
        },
    ) {
        Text(
            text = "Overlay · ${health.state.label}${if (health.needsAttention) " — ${health.detail}" else ""}",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FormatSelector(current: String, detected: DraftFormat, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val lockedToEvent = detected == DraftFormat.SEALED
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !lockedToEvent,
            contentPadding = HeaderButtonPadding,
            modifier = Modifier.semantics { stateDescription = RatingsFormat.label(current) },
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

private val HeaderButtonPadding = ButtonDefaults.ContentPadding
