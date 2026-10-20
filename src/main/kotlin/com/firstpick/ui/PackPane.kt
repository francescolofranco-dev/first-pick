package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.ConfidenceLevel
import com.firstpick.model.DraftPhase
import com.firstpick.sim.DraftSimulator

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackPane(
    state: DraftUiState,
    onSimulate: (String) -> Unit = {},
    onOpenGuide: () -> Unit = {},
) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(
            modifier = Modifier.appPanel(padding = 14.dp, radius = AppTheme.radii.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.colors.accent)
            Spacer(Modifier.width(10.dp))
            Text(
                if (state.simulating) "Setting up the demo draft…" else "Loading 17Lands data…",
                style = AppTheme.typography.body,
                color = AppTheme.colors.textPrimary,
            )
        }
    }

    state.packCards.isEmpty() -> Centered {
        val idle = state.phase == DraftPhase.IDLE
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().appPanel(padding = 16.dp, radius = AppTheme.radii.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (idle) "Start a draft in MTG Arena" else "No cards in the current pack",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                if (idle) {
                    Text(
                        "Enable Options → Account → Detailed Logs (Plugin Support)",
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (DevFlags.demoEnabled && idle && !state.simulating) {
                Column(
                    modifier = Modifier.fillMaxWidth().appPanel(padding = 14.dp, radius = AppTheme.radii.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "OR TRY A DEMO DRAFT",
                        style = AppTheme.typography.sectionHeader,
                        color = AppTheme.colors.textSecondary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DraftSimulator.SETS.forEachIndexed { index, set ->
                            DemoSetButton(set, recommended = index == 0) { onSimulate(set) }
                        }
                    }
                }
            }

            if (idle && state.researchedSets.isNotEmpty()) {
                SynergyCoverage(state.researchedSets, state.groundedSets, state.modelSets)
            }
        }
    }

    else -> Column(Modifier.fillMaxSize()) {
        if (state.picksPerChoice == 2) {
            val pair = state.packCards.filter { it.recommendedPair }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .appBadge(AppTheme.colors.accent, fillAlpha = 0.11f, borderAlpha = 0.30f, shape = RoundedCornerShape(AppTheme.radii.sm))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    if (pair.size == 2) "Suggested pair: ${pair.joinToString(" + ") { it.name }}"
                    else if (state.packCards.size == 1) "Take the remaining card"
                    else "Choose two cards; pair advice unavailable",
                    style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.colors.accent,
                )
            }
        }
        if (shouldShowGuideStarter(state)) {
            SetGuideStarter(state, onOpenGuide)
            Spacer(Modifier.height(6.dp))
        }
        if (hasUnratedCards(state.packCards)) {
            Text(
                "Card power is unavailable for unrated cards. Pool fit and synergy notes update as you draft.",
                style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        if (state.picksPerChoice == 1) ConfidenceBanner(state.packCards)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.packCards, key = { "${it.grpId}#${it.rank}" }) {
                PackRow(
                    it,
                    state.packCards.size,
                    showBestPick = !hasUnratedCards(state.packCards) && state.picksPerChoice == 1,
                )
            }
        }
    }
}

internal fun shouldShowGuideStarter(state: DraftUiState): Boolean =
    state.phase == DraftPhase.DRAFTING &&
        state.pack == 1 &&
        state.pick == 1 &&
        state.setCode != null &&
        state.packCards.isNotEmpty()

@Composable
private fun SetGuideStarter(state: DraftUiState, onOpenGuide: () -> Unit) {
    val guide = state.setGuide
    val title = guide?.setName?.takeIf { it.isNotBlank() } ?: state.setCode.orEmpty()
    val detail = when {
        guide != null && guide.mechanics.isNotEmpty() -> guide.mechanics.take(3).joinToString(" · ") { it.name }
        guide != null -> "Color pairs, themes, and the set's best cards"
        state.guideLoading -> "Preparing themes, color pairs, and top cards…"
        else -> "Draft fundamentals"
    }
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.surfaceSelected)
            .border(1.dp, AppTheme.colors.borderAccent, shape)
            .semantics { role = Role.Button }
            .focusable()
            .clickable(role = Role.Button, onClick = onOpenGuide)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$title draft guide",
                style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = AppTheme.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = AppTheme.typography.micro,
                color = AppTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Open guide  ›",
            style = AppTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = AppTheme.colors.accent,
        )
    }
}

@Composable
private fun SynergyCoverage(researched: List<String>, grounded: List<String>, modelSets: List<String> = emptyList()) {
    var expandedTier by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(AppTheme.radii.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.surface)
            .border(1.dp, AppTheme.colors.borderSubtle, shape),
    ) {
        CoverageRow(
            "Deep synergy", researched, "Full archetype + combo synergy on these sets.",
            tierAccent("researched"), expandedTier == "researched",
        ) { expandedTier = if (expandedTier == "researched") null else "researched" }
        if (grounded.isNotEmpty()) {
            CoverageDivider()
            CoverageRow(
                "Data synergy", grounded, "Ratings-driven picks; lighter synergy signal.",
                tierAccent("data"), expandedTier == "data",
            ) { expandedTier = if (expandedTier == "data") null else "data" }
        }
        if (modelSets.isNotEmpty()) {
            CoverageDivider()
            CoverageRow(
                "AI picks", modelSets, "Learned pick model — ranks trained on winning drafters.",
                tierAccent("model"), expandedTier == "model",
            ) { expandedTier = if (expandedTier == "model") null else "model" }
        }
    }
}

@Composable
private fun CoverageDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
}

@Composable
private fun CoverageRow(
    label: String,
    sets: List<String>,
    description: String,
    dotColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "$label expanded" else "$label collapsed"
            }
            .focusable()
            .clickable(role = Role.Button, onClick = onToggle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                Text(label, style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = AppTheme.colors.textPrimary)
            }
            Text(
                "${sets.size} sets ${if (expanded) "︿" else "⌄"}",
                style = AppTheme.typography.monoSmall,
                color = AppTheme.colors.textSecondary,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    sets.joinToString("  "),
                    style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.colors.textPrimary,
                )
                Text(description, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun DemoSetButton(set: String, recommended: Boolean, onClick: () -> Unit) {
    AppButton(
        onClick = onClick,
        variant = if (recommended) AppButtonVariant.Primary else AppButtonVariant.Subtle,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            set,
            style = AppTheme.typography.monoValue.copy(fontWeight = FontWeight.Bold),
            color = if (recommended) AppTheme.colors.textOnAccent else AppTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun ConfidenceBanner(cards: List<PackCardUi>) {
    val confidence = packConfidence(cards) ?: return
    if (confidence.level == ConfidenceLevel.CLEAR) return
    val tossUp = confidence.level == ConfidenceLevel.TOSS_UP
    val accent = if (tossUp) AppTheme.colors.warning else AppTheme.colors.accentSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .appBadge(accent, fillAlpha = 0.11f, borderAlpha = 0.28f, shape = RoundedCornerShape(AppTheme.radii.sm))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(AppTheme.radii.xs))
                .background(accent)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                if (tossUp) "Toss-up" else "Lean",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textOnAccent,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (tossUp) "Top ${confidence.contenders} are nearly tied — trust your read." else "Only a slight edge to the top pick.",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun PackRow(card: PackCardUi, packSize: Int, showBestPick: Boolean) {
    val top = card.recommendedPair || (showBestPick && card.rank == 1 && card.value != null)
    val bombConsistent = card.isBomb && isBombTier(card.value)

    Row(
        Modifier
            .fillMaxWidth()
            .appRow(highlighted = top, paddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (card.value == null) "—" else "${card.rank}",
            style = AppTheme.typography.monoSmall,
            color = if (top) AppTheme.colors.accent else AppTheme.colors.textMuted,
            modifier = Modifier.width(20.dp),
        )
        if (card.breakdown != null) {
            val model = card.breakdown.takeIf { it.modelShift != 0.0 && card.modelRank != null }?.let { b ->
                ModelExplain(
                    rank = card.modelRank!!,
                    packSize = packSize,
                    soloValue = card.value?.minus(b.modelShift),
                    ata = card.ata,
                    alsa = card.alsa,
                )
            }
            ScoreBreakdownTrigger(card.breakdown, model) { GradeBadge(card.value) }
        } else {
            GradeBadge(card.value)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bombConsistent) {
                    Text("★ ", fontSize = 12.sp, color = AppTheme.colors.warning)
                }
                CardPreview(card.imageUrl, description = card.name) {
                    Text(
                        card.name,
                        style = AppTheme.typography.rowTitle.copy(
                            fontWeight = if (top) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = AppTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val shownReasons = if (bombConsistent) card.reasons else card.reasons.filter { it != "Bomb" }
            if (shownReasons.isNotEmpty()) {
                Text(
                    shownReasons.joinToString(" · "),
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (card.color.isNotEmpty()) {
            PipRow(card.color.toList())
            Spacer(Modifier.width(10.dp))
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(42.dp)) {
            Text(
                card.gihWr.asPct(),
                style = AppTheme.typography.monoValue,
                color = AppTheme.colors.textPrimary,
            )
            Text(
                "GIH",
                style = AppTheme.typography.monoMicro,
                color = AppTheme.colors.textMuted,
            )
        }
    }
}
