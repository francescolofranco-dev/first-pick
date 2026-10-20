package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    isOverlayOpen: Boolean = false,
    overlayHealth: OverlayHealth = OverlayHealth(OverlayHealthState.INACTIVE),
    onToggleOverlay: () -> Unit = {},
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

    val deckTabEnabled = state.deckOptions.isNotEmpty() || state.deckSoFar != null
    val deckTabLabel = if (state.deckOptions.isNotEmpty()) "Deck builder" else "Deck so far"
    val deckTabBadge = when {
        state.deckOptions.isNotEmpty() -> "${state.deckOptions.size}"
        state.deckSoFar != null -> "${state.poolSize}"
        else -> null
    }
    val draftTabBadge = if (state.packCards.isNotEmpty()) "${state.packCards.size}" else null

    AppTheme {
        Surface(Modifier.fillMaxSize(), color = AppTheme.colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 680.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Floating Raycast Command Header
                    FloatingCommandHeader(
                        state = state,
                        currentScreen = currentScreen,
                        onSelectScreen = { currentScreen = it },
                        draftTabBadge = draftTabBadge,
                        deckTabLabel = deckTabLabel,
                        deckTabBadge = deckTabBadge,
                        deckTabEnabled = deckTabEnabled,
                        isOverlayOpen = isOverlayOpen,
                        onToggleOverlay = onToggleOverlay,
                        onSelectPickCount = onSelectPickCount,
                        onStopSim = onStopSim,
                        onTogglePause = onTogglePause,
                        onNewDraft = onNewDraft,
                        compact = compact,
                    )

                    state.dataError?.let {
                        Text(it, style = AppTheme.typography.caption, color = AppTheme.colors.danger)
                    }

                    // Bento Main Workspace
                    val showSidebar = currentScreen == AppScreen.DRAFT_POOL &&
                        (state.poolSize > 0 || state.packCards.isNotEmpty())
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (compact && showSidebar) {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.weight(0.68f).fillMaxWidth()) {
                                    PackPane(
                                        state = state,
                                        onSimulate = onSimulate,
                                        onOpenGuide = { currentScreen = AppScreen.GUIDES },
                                    )
                                }
                                Sidebar(state, Modifier.fillMaxWidth().weight(0.32f))
                            }
                        } else {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
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
                                    Sidebar(state, Modifier.width(252.dp).fillMaxHeight())
                                }
                            }
                        }
                    }

                    // Bottom Telemetry Footer
                    BottomTelemetryFooter(
                        state = state,
                        isOverlayOpen = isOverlayOpen,
                        overlayHealth = overlayHealth,
                        onRetryRatings = onRetryRatings,
                    )
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
    val activeDesc = when (tier) {
        "model" -> "Active mode: Learned neural pick model trained on winning 17Lands drafters, combined with set synergy."
        "researched" -> "Active mode: Hand-researched set archetype & combo synergy rules combined with 17Lands win rates."
        else -> "Active mode: Statistical 17Lands win rates, color pair strength, and mana curve needs."
    }
    val fullTooltip = buildString {
        append(activeDesc)
        append("\n\nAdvisor modes:\n")
        append("• ✦ Deep synergy — Full archetype + card combo rules.\n")
        append("• ◆ AI picks — Neural pick model trained on trophy drafters.\n")
        append("• • Data synergy — Statistical 17Lands ratings & curve fit.")
    }
    AppTooltip(
        title = "Advisor Engine · $label",
        tooltip = fullTooltip,
        width = 264.dp,
    ) {
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
}

@Composable
internal fun tierAccent(tier: String) = when (tier) {
    "researched" -> MaterialTheme.colorScheme.primary
    "model" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingCommandHeader(
    state: DraftUiState,
    currentScreen: AppScreen,
    onSelectScreen: (AppScreen) -> Unit,
    draftTabBadge: String?,
    deckTabLabel: String,
    deckTabBadge: String?,
    deckTabEnabled: Boolean,
    isOverlayOpen: Boolean,
    onToggleOverlay: () -> Unit,
    onSelectPickCount: (Int) -> Unit,
    onStopSim: () -> Unit,
    onTogglePause: () -> Unit,
    onNewDraft: () -> Unit,
    compact: Boolean,
) {
    val tabs = listOf(
        SegmentedTabItem(
            label = "Draft pool",
            badge = draftTabBadge,
            shortcut = "⌘1",
            selected = currentScreen == AppScreen.DRAFT_POOL,
            onClick = { onSelectScreen(AppScreen.DRAFT_POOL) },
        ),
        SegmentedTabItem(
            label = deckTabLabel,
            badge = deckTabBadge,
            shortcut = "⌘2",
            selected = currentScreen == AppScreen.DECK_BUILDER,
            enabled = deckTabEnabled,
            onClick = { onSelectScreen(AppScreen.DECK_BUILDER) },
        ),
        SegmentedTabItem(
            label = "Guides",
            badge = state.setCode,
            shortcut = "⌘3",
            selected = currentScreen == AppScreen.GUIDES,
            onClick = { onSelectScreen(AppScreen.GUIDES) },
        ),
    )

    val brandCluster = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBrandMark()
            Spacer(Modifier.width(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "FirstPick",
                        style = AppTheme.typography.appTitle,
                        color = AppTheme.colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        "v${AppVersion.value}",
                        style = AppTheme.typography.monoMicro.copy(fontSize = 9.sp),
                        color = AppTheme.colors.textMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                    state.setCode?.let { set ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppTheme.colors.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                set,
                                style = AppTheme.typography.monoMicro.copy(fontWeight = FontWeight.Bold),
                                color = AppTheme.colors.accent,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (state.laneColors.isNotEmpty()) {
                        PipRow(state.laneColors, size = 13.dp)
                    }
                    Text(
                        state.headline,
                        style = AppTheme.typography.micro,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    val actionCluster = @Composable {
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
            } else {
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

    val headerShape = RoundedCornerShape(AppTheme.radii.xl)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(headerShape)
            .background(AppTheme.colors.surface)
            .border(1.dp, AppTheme.colors.borderDefault, headerShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.weight(1f, fill = false)) { brandCluster() }
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                actionCluster()
            }
        }
        if (compact) {
            Spacer(Modifier.height(6.dp))
            actionCluster()
        }
        Spacer(Modifier.height(6.dp))
        AppSegmentedTabs(
            tabs = tabs,
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomTelemetryFooter(
    state: DraftUiState,
    isOverlayOpen: Boolean,
    overlayHealth: OverlayHealth,
    onRetryRatings: () -> Unit,
) {
    if (state.synergyTier == null && state.ratingsDataStatus == RatingsDataStatus.IDLE && !isOverlayOpen) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.synergyTier?.let {
                SynergyTierBadge(it)
            }
            if (state.poolSize > 0) {
                Text(
                    "${state.poolSize} cards in pool",
                    style = AppTheme.typography.monoMicro,
                    color = AppTheme.colors.textMuted,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            RatingsHealthStatus(state, onRetryRatings)
            if (isOverlayOpen) {
                OverlayHealthStatus(overlayHealth)
            }
        }
    }
}

@Composable
private fun RatingsHealthStatus(state: DraftUiState, onRetry: () -> Unit) {
    if (state.ratingsDataStatus == RatingsDataStatus.IDLE) return
    val color = when (state.ratingsDataStatus) {
        RatingsDataStatus.FRESH -> AppTheme.colors.positive
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
        AppTooltip(
            title = "17Lands Telemetry",
            tooltip = "Shows whether Premier Draft card win-rate data is fresh or cached locally, how many cards have reliable sample sizes, and the median games per card.",
            width = 252.dp,
        ) {
            Box(
                modifier = Modifier
                    .appBadge(color, fillAlpha = 0.12f, borderAlpha = 0.30f, shape = RoundedCornerShape(AppTheme.radii.xs))
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
        OverlayHealthState.ACTIVE -> AppTheme.colors.positive
        OverlayHealthState.READING -> AppTheme.colors.accentSecondary
        OverlayHealthState.WAITING_FOR_ARENA, OverlayHealthState.INACTIVE -> AppTheme.colors.textSecondary
        OverlayHealthState.CAPTURE_UNAVAILABLE,
        OverlayHealthState.UNSUPPORTED_LAYOUT,
        OverlayHealthState.CLICK_THROUGH_FAILED,
        -> AppTheme.colors.danger
    }
    AppTooltip(
        title = "Arena Overlay · ${health.state.label}",
        tooltip = "${health.detail.ifBlank { "Tracks the live MTG Arena window to display in-game pick and deck guidance." }}",
        width = 248.dp,
    ) {
        Box(
            modifier = Modifier
                .appBadge(color, fillAlpha = 0.12f, borderAlpha = 0.30f, shape = RoundedCornerShape(AppTheme.radii.xs))
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

private val HeaderButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
