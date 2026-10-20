package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.core.AppVersion
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
    onSelectPickCount: (Int) -> Unit = {},
    onSimulate: (String) -> Unit = {},
    onStopSim: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onOpenGuideSource: (String) -> Unit = {},
    onRetryRatings: () -> Unit = {},
    onNewDraft: () -> Unit = {},
) {
    var currentScreen by remember(state.phase) {
        mutableStateOf(if (state.phase == DraftPhase.COMPLETE) AppScreen.DECK_BUILDER else AppScreen.DRAFT_POOL)
    }

    AppTheme {
        Surface(Modifier.fillMaxSize(), color = AppTheme.colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 680.dp
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Header(
                        state = state,
                        isOverlayOpen = isOverlayOpen,
                        overlayHealth = overlayHealth,
                        overlayRelevant = isOverlayOpen,
                        onToggleOverlay = onToggleOverlay,
                        onSelectPickCount = onSelectPickCount,
                        onStopSim = onStopSim,
                        onTogglePause = onTogglePause,
                        onRetryRatings = onRetryRatings,
                        onNewDraft = onNewDraft,
                        compact = compact,
                    )

                    val deckTabEnabled = state.deckOptions.isNotEmpty() || state.deckSoFar != null
                    val deckTabLabel = if (state.deckOptions.isNotEmpty()) "Deck builder" else "Deck so far"
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AppSegmentedTabs(
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                            tabs = listOf(
                                SegmentedTabItem(
                                    label = "Draft pool",
                                    selected = currentScreen == AppScreen.DRAFT_POOL,
                                    onClick = { currentScreen = AppScreen.DRAFT_POOL },
                                ),
                                SegmentedTabItem(
                                    label = deckTabLabel,
                                    selected = currentScreen == AppScreen.DECK_BUILDER,
                                    enabled = deckTabEnabled,
                                    onClick = { currentScreen = AppScreen.DECK_BUILDER },
                                ),
                                SegmentedTabItem(
                                    label = "Guides",
                                    selected = currentScreen == AppScreen.GUIDES,
                                    onClick = { currentScreen = AppScreen.GUIDES },
                                ),
                            ),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    val showSidebar = currentScreen == AppScreen.DRAFT_POOL &&
                        (state.poolSize > 0 || state.packCards.isNotEmpty())
                    if (compact && showSidebar) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(0.68f).fillMaxWidth()) {
                                PackPane(
                                    state = state,
                                    onSimulate = onSimulate,
                                    onOpenGuide = { currentScreen = AppScreen.GUIDES },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Sidebar(state, Modifier.fillMaxWidth().weight(0.32f))
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                when (currentScreen) {
                                    AppScreen.GUIDES -> GuidePane(state, onOpenGuideSource)
                                    AppScreen.DECK_BUILDER -> when {
                                        state.deckOptions.isNotEmpty() -> DeckBuilderPane(
                                            options = state.deckOptions,
                                            savedDeck = state.savedDeck,
                                            committedDeck = committedDeck,
                                            onUseDeck = onUseDeck,
                                            guidanceEnabled = isOverlayOpen,
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
        Modifier
            .appBadge(accent, fillAlpha = 0.14f, borderAlpha = 0.34f, shape = RoundedCornerShape(AppTheme.radii.xs))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun tierAccent(tier: String) = when (tier) {
    "researched" -> MaterialTheme.colorScheme.primary
    "model" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    overlayHealth: OverlayHealth,
    overlayRelevant: Boolean,
    onToggleOverlay: () -> Unit,
    onSelectPickCount: (Int) -> Unit,
    onStopSim: () -> Unit,
    onTogglePause: () -> Unit,
    onRetryRatings: () -> Unit,
    onNewDraft: () -> Unit,
    compact: Boolean,
) {
    val brandRow = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FirstPick",
                style = AppTheme.typography.appTitle,
                color = AppTheme.colors.textPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(AppTheme.radii.xs))
                    .background(AppTheme.colors.surfaceElevated)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "v${AppVersion.value}",
                    style = AppTheme.typography.monoMicro,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (state.laneColors.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                PipRow(state.laneColors)
            }
            state.synergyTier?.let {
                Spacer(Modifier.width(6.dp))
                SynergyTierBadge(it)
            }
        }
    }

    val actions = @Composable {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (state.simulating) {
                AppButton(
                    onClick = onTogglePause,
                    variant = AppButtonVariant.Tonal,
                    contentPadding = HeaderButtonPadding,
                ) {
                    Text(
                        text = if (state.simPaused) "▶ Resume demo" else "❚❚ Pause demo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.accent,
                    )
                }
                AppButton(
                    onClick = onStopSim,
                    variant = AppButtonVariant.Warning,
                    contentPadding = HeaderButtonPadding,
                ) {
                    Text(
                        text = "✕ Exit demo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.warning,
                    )
                }
            }
            if (!state.simulating) {
                AppButton(
                    onClick = onNewDraft,
                    variant = AppButtonVariant.Subtle,
                    contentPadding = HeaderButtonPadding,
                ) {
                    Text(
                        "New draft",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary,
                    )
                }
            }
            PickCountSelector(state.pickCountOverride, state.picksPerChoice, onSelectPickCount)
            val overlayEnabled = state.phase != DraftPhase.COMPLETE || state.deckOptions.isNotEmpty()
            AppButton(
                enabled = overlayEnabled,
                onClick = onToggleOverlay,
                variant = if (isOverlayOpen) AppButtonVariant.Tonal else AppButtonVariant.Subtle,
                active = isOverlayOpen,
                contentPadding = HeaderButtonPadding,
                modifier = Modifier.semantics {
                    stateDescription = if (isOverlayOpen) "Overlay shown" else "Overlay hidden"
                },
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isOverlayOpen) AppTheme.colors.accent else AppTheme.colors.textMuted),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (isOverlayOpen) "Hide overlay" else "Show overlay",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverlayOpen) AppTheme.colors.accent else AppTheme.colors.textPrimary,
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (compact) {
            brandRow()
            Spacer(Modifier.height(6.dp))
            actions()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.weight(1f, fill = false)) { brandRow() }
                Spacer(Modifier.width(6.dp))
                actions()
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.headline,
                style = AppTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = AppTheme.colors.accent,
            )
            RatingsHealthStatus(state, onRetryRatings)
            if (overlayRelevant) {
                OverlayHealthStatus(overlayHealth)
            }
        }
        state.dataError?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, style = AppTheme.typography.caption, color = AppTheme.colors.danger)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingsHealthStatus(state: DraftUiState, onRetry: () -> Unit) {
    if (state.ratingsDataStatus == RatingsDataStatus.IDLE) return
    val color = when (state.ratingsDataStatus) {
        RatingsDataStatus.FRESH -> AppTheme.colors.accent
        RatingsDataStatus.CACHED -> AppTheme.colors.accentSecondary
        RatingsDataStatus.LOADING -> AppTheme.colors.accentSecondary
        RatingsDataStatus.STALE_CACHE -> AppTheme.colors.warning
        RatingsDataStatus.CARD_CATALOG -> AppTheme.colors.warning
        RatingsDataStatus.ERROR -> AppTheme.colors.danger
        RatingsDataStatus.IDLE -> AppTheme.colors.textSecondary
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .appBadge(color, fillAlpha = 0.11f, borderAlpha = 0.28f, shape = RoundedCornerShape(AppTheme.radii.xs))
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = ratingsDataDescription(state)
                }
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                ratingsDataDescription(state),
                color = color,
                style = AppTheme.typography.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.canRetryRatings) {
            AppButton(
                onClick = onRetry,
                variant = AppButtonVariant.Subtle,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("Retry data", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.accent)
            }
        }
        state.ratingsDataWarning?.let {
            Text(it, color = color, style = AppTheme.typography.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun ratingsDataDescription(state: DraftUiState): String {
    if (state.ratingsDataStatus == RatingsDataStatus.CARD_CATALOG) {
        return "Scryfall · ${state.ratingsCardCount} cards · awaiting 17Lands ratings"
    }
    val status = when (state.ratingsDataStatus) {
        RatingsDataStatus.IDLE -> "Idle"
        RatingsDataStatus.LOADING -> "Loading"
        RatingsDataStatus.FRESH -> "Fresh"
        RatingsDataStatus.CACHED -> "Cached"
        RatingsDataStatus.STALE_CACHE -> "Stale cache"
        RatingsDataStatus.CARD_CATALOG -> "Card catalog"
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
        OverlayHealthState.ACTIVE -> AppTheme.colors.accent
        OverlayHealthState.READING -> AppTheme.colors.accentSecondary
        OverlayHealthState.WAITING_FOR_ARENA, OverlayHealthState.INACTIVE -> AppTheme.colors.textSecondary
        OverlayHealthState.CAPTURE_UNAVAILABLE,
        OverlayHealthState.UNSUPPORTED_LAYOUT,
        OverlayHealthState.CLICK_THROUGH_FAILED,
        -> AppTheme.colors.danger
    }
    Box(
        modifier = Modifier
            .appBadge(color, fillAlpha = 0.11f, borderAlpha = 0.28f, shape = RoundedCornerShape(AppTheme.radii.xs))
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "Overlay ${health.state.label}. ${health.detail}"
            }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Overlay · ${health.state.label}${if (health.needsAttention) " — ${health.detail}" else ""}",
            color = color,
            style = AppTheme.typography.micro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickCountSelector(current: Int, detected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppButton(
            onClick = { expanded = true },
            variant = AppButtonVariant.Subtle,
            contentPadding = HeaderButtonPadding,
            modifier = Modifier.semantics {
                stateDescription = if (current == 0) "Auto · ${DraftPickMode.label(detected)}" else DraftPickMode.label(current)
            },
        ) {
            Text(
                text = "Draft picks: ${DraftPickMode.label(current)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = 10.sp, color = AppTheme.colors.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DraftPickMode.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(DraftPickMode.label(choice), fontSize = 12.sp) },
                    onClick = {
                        onSelect(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val HeaderButtonPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
