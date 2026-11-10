package com.firstpick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.overlay.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(
    state: DraftUiState,
    isOverlayOpen: Boolean = false,
    overlayHealth: OverlayHealth = OverlayHealth(OverlayHealthState.INACTIVE),
    liveDeckGuidance: com.firstpick.overlay.DeckGuidanceOverlayModel? = null,
    liveDeckAccess: LiveDeckAccess = LiveDeckAccess(),
    onGrantArenaAccess: () -> Unit = {},
    onRevokeArenaAccess: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    committedDeck: DeckOptionUi? = null,
    onUseDeck: (DeckOptionUi) -> Unit = {},
    onSelectPickCount: (Int) -> Unit = {},
    onSimulate: (String) -> Unit = {},
    onStopSim: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onLoadReferenceSet: (String) -> Unit = {},
    onRetryRatings: () -> Unit = {},
    onNewDraft: () -> Unit = {},
    stats: com.firstpick.stats.PlayerStats = com.firstpick.stats.PlayerStats(),
    history: List<com.firstpick.draft.DraftHistoryEntry> = emptyList(),
    onReviewDraft: suspend (com.firstpick.model.DraftState) -> DraftUiState = { DraftUiState() },
    onGuideArchivedDraft: (DraftUiState) -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    Workbench(state, isOverlayOpen, overlayHealth, liveDeckAccess, onGrantArenaAccess, onRevokeArenaAccess,
        onToggleOverlay, committedDeck, onUseDeck, onSelectPickCount, onSimulate, onStopSim,
        onTogglePause, onLoadReferenceSet, onRetryRatings, onNewDraft, stats, history, onReviewDraft, onOpenAbout)
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
