package com.firstpick.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.ConfidenceLevel
import com.firstpick.advisor.PickConfidence
import com.firstpick.model.DraftPhase
import com.firstpick.sim.DraftSimulator
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackPane(
    state: DraftUiState,
    onSimulate: (String) -> Unit = {},
) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(
            modifier = Modifier.appPanel(padding = 14.dp, radius = AppTheme.radii.xl),
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
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Raycast Hero Connection Status Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .appHeroCard(radius = AppTheme.radii.xl)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.positive),
                        )
                        Text(
                            "ARENA LOG WATCHER ACTIVE",
                            style = AppTheme.typography.sectionHeader,
                            color = AppTheme.colors.positive,
                        )
                    }
                    PipRow(listOf('W', 'U', 'B', 'R', 'G'), size = 14.dp)
                }
                Text(
                    if (idle) "Start a draft in MTG Arena" else "No cards in the current pack",
                    style = AppTheme.typography.paneTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = AppTheme.colors.textPrimary,
                )
                if (idle) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            "Enable",
                            style = AppTheme.typography.caption,
                            color = AppTheme.colors.textSecondary,
                        )
                        for ((idx, step) in listOf("Options", "Account", "Detailed Logs (Plugin Support)").withIndex()) {
                            if (idx > 0) {
                                Text("→", style = AppTheme.typography.micro, color = AppTheme.colors.textMuted)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(AppTheme.radii.xs))
                                    .background(AppTheme.colors.surfaceElevated)
                                    .border(1.dp, AppTheme.colors.borderDefault, RoundedCornerShape(AppTheme.radii.xs))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    step,
                                    style = AppTheme.typography.monoMicro,
                                    color = AppTheme.colors.textPrimary,
                                )
                            }
                        }
                    }
                }
            }

            if (DevFlags.demoEnabled && idle && !state.simulating) {
                Column(
                    modifier = Modifier.fillMaxWidth().appPanel(padding = 14.dp, radius = AppTheme.radii.xl),
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

    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
        val useTwoColumns = maxWidth >= 340.dp
        val unrated = hasUnratedCards(state.packCards)
        val showBestPick = !unrated && state.picksPerChoice == 1
        val conf = remember(state.packCards) { packConfidence(state.packCards) }
        val packSize = state.packCards.size
        val heroCard = state.packCards.first()
        val restCards = state.packCards.drop(1)
        val listState = rememberLazyListState()

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                state = listState,
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.picksPerChoice == 2) {
                    item(key = "pair-banner") {
                        val pair = state.packCards.filter { it.recommendedPair }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .appBadge(
                                    AppTheme.colors.accent,
                                    fillAlpha = 0.14f,
                                    borderAlpha = 0.36f,
                                    shape = RoundedCornerShape(AppTheme.radii.md),
                                )
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
                }

                if (unrated) {
                    item(key = "unrated-note") {
                        Text(
                            "Card power is unavailable for unrated cards. Pool fit and synergy notes update as you draft.",
                            style = AppTheme.typography.caption,
                            color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                // Hero #1 Recommended Pick Spotlight Card
                item(key = "hero-${heroCard.grpId}-${heroCard.rank}") {
                    HeroPickCard(
                        card = heroCard,
                        packSize = packSize,
                        confidence = if (state.picksPerChoice == 1) conf else null,
                        showBestPick = showBestPick,
                    )
                }

                // Remaining Pack Cards in a 2-Column Bento Grid (or 1-Column when narrow)
                if (restCards.isNotEmpty()) {
                    if (useTwoColumns) {
                        val pairs = restCards.chunked(2)
                        items(pairs, key = { pair -> "pair-${pair.first().grpId}-${pair.first().rank}" }) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val first = pair[0]
                                PackBentoCard(
                                    card = first,
                                    packSize = packSize,
                                    highlight = first.recommendedPair,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                if (pair.size > 1) {
                                    val second = pair[1]
                                    PackBentoCard(
                                        card = second,
                                        packSize = packSize,
                                        highlight = second.recommendedPair,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        items(restCards, key = { "${it.grpId}#${it.rank}" }) { card ->
                            PackBentoCard(
                                card = card,
                                packSize = packSize,
                                highlight = card.recommendedPair,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = LocalScrollbarStyle.current.copy(
                    unhoverColor = AppTheme.colors.borderDefault,
                    hoverColor = AppTheme.colors.accent,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroPickCard(
    card: PackCardUi,
    packSize: Int,
    confidence: PickConfidence?,
    showBestPick: Boolean,
) {
    val bombConsistent = card.isBomb && isBombTier(card.value)
    val tierColor = valueTierColor(card.value)
    val modelExplain = card.breakdown?.takeIf { it.modelShift != 0.0 && card.modelRank != null }?.let { b ->
        ModelExplain(
            rank = card.modelRank!!,
            packSize = packSize,
            soloValue = card.value?.minus(b.modelShift),
            ata = card.ata,
            alsa = card.alsa,
        )
    }
    val bestPickLabel = when {
        !showBestPick -> "#1 IN PACK"
        confidence == null -> "BEST PICK"
        confidence.level == ConfidenceLevel.TOSS_UP -> "TOSS-UP · ±${"%.1f".format(confidence.gap)} PTS"
        confidence.level != ConfidenceLevel.CLEAR -> "CLOSE CALL · +${"%.1f".format(confidence.gap)} PTS"
        else -> "BEST PICK · +${"%.1f".format(confidence.gap)} PTS"
    }
    val bannerColor = if (confidence != null && confidence.level != ConfidenceLevel.CLEAR) {
        AppTheme.colors.warning
    } else {
        AppTheme.colors.positive
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appHeroCard(accentColor = bannerColor, radius = AppTheme.radii.xl)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Top Pill Bar: Best Pick Pill + Tier Pill (with Breakdown Popover) + Official MTG Mana Symbols
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                AppTooltip(
                    title = "Pick Confidence",
                    tooltip = buildString {
                        append("Score gap between #1 and #2 in this pack.\n\n")
                        append("• BEST PICK (+3.0+ pts) — Clear standout card.\n")
                        append("• CLOSE CALL (1.5–3.0 pts) — Slight edge to #1.\n")
                        append("• TOSS-UP (<1.5 pts) — Top contenders are nearly tied; consider open color signals.")
                    },
                    width = 264.dp,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(AppTheme.radii.pill))
                            .background(bannerColor)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            bestPickLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = AppTheme.colors.textOnAccent,
                        )
                    }
                }

                val tierBadgeContent: @Composable () -> Unit = {
                    Row(
                        modifier = Modifier
                            .appBadge(
                                tierColor,
                                fillAlpha = 0.20f,
                                borderAlpha = 0.48f,
                                shape = RoundedCornerShape(AppTheme.radii.xs),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Tier ${letterGrade(card.value)}",
                            style = AppTheme.typography.monoMicro.copy(fontWeight = FontWeight.Bold),
                            color = tierColor,
                        )
                        if (card.value != null) {
                            Text(
                                "· ${card.value.roundToInt()}",
                                style = AppTheme.typography.monoMicro,
                                color = AppTheme.colors.textPrimary,
                            )
                        }
                    }
                }
                if (card.breakdown != null) {
                    ScoreBreakdownTrigger(card.breakdown, modelExplain) { tierBadgeContent() }
                } else {
                    tierBadgeContent()
                }

                // Official MTG Mana Symbols
                PipRow(card.color.ifEmpty { "C" }.toList(), size = 16.dp)
            }

            // Hero Card Name
            CardPreview(card.imageUrl, description = card.name) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (bombConsistent) {
                        Text(
                            "★",
                            color = AppTheme.colors.warning,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        card.name,
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Reasons & Confidence Context
            val shownReasons = if (bombConsistent) card.reasons else card.reasons.filter { it != "Bomb" }
            if (shownReasons.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (reason in shownReasons.take(5)) {
                        ReasonChip(reason)
                    }
                }
            }
            if (confidence != null && confidence.level != ConfidenceLevel.CLEAR) {
                Text(
                    if (confidence.level == ConfidenceLevel.TOSS_UP) {
                        "Top ${confidence.contenders} are nearly tied — trust your read."
                    } else {
                        "Only a slight edge to the top pick."
                    },
                    style = AppTheme.typography.micro,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Right GIH Win Rate Spotlight Column
        AppTooltip(
            title = "17Lands Card Telemetry",
            tooltip = "• GIH Win%: Games in Hand win rate — how often players win games when this card is drawn or in their opening hand.\n• ALSA: Average Last Seen At — average pick number where drafters last see this card (lower = taken earlier).",
            width = 264.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (card.gihWr != null) "${card.gihWr.asPct()}%" else "—",
                    fontFamily = AppTheme.typography.monoValue.fontFamily,
                    fontSize = 20.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = winRateColor(card.gihWr),
                )
                Text(
                    text = "GIH Win%",
                    style = AppTheme.typography.micro,
                    color = AppTheme.colors.textSecondary,
                )
                if (card.alsa != null) {
                    Text(
                        text = "ALSA ${card.alsa.as1dp()}",
                        style = AppTheme.typography.monoMicro,
                        color = AppTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackBentoCard(
    card: PackCardUi,
    packSize: Int,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val bombConsistent = card.isBomb && isBombTier(card.value)
    val tierColor = valueTierColor(card.value)
    val modelExplain = card.breakdown?.takeIf { it.modelShift != 0.0 && card.modelRank != null }?.let { b ->
        ModelExplain(
            rank = card.modelRank!!,
            packSize = packSize,
            soloValue = card.value?.minus(b.modelShift),
            ata = card.ata,
            alsa = card.alsa,
        )
    }

    Row(
        modifier = modifier
            .alpha(if (card.guideConstrained) 0.52f else 1f)
            .appCard(selected = highlight, radius = AppTheme.radii.xl)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Rank + Official MTG Mana Symbols + Card Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (card.value == null) "—" else "#${card.rank}",
                    style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (highlight) AppTheme.colors.accent else AppTheme.colors.textSecondary,
                )
                PipRow(card.color.ifEmpty { "C" }.toList(), size = 14.dp)
                CardPreview(card.imageUrl, description = card.name, modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (bombConsistent) {
                            Text("★", color = AppTheme.colors.warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            card.name,
                            style = AppTheme.typography.cardTitle,
                            color = AppTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Reason Chips
            val shownReasons = if (bombConsistent) card.reasons else card.reasons.filter { it != "Bomb" }
            if (shownReasons.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (reason in shownReasons.take(3)) {
                        ReasonChip(reason)
                    }
                }
            } else {
                Text(
                    valueTierLabel(card.value),
                    style = AppTheme.typography.micro,
                    color = AppTheme.colors.textMuted,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Right Column: Tier Grade (with ScoreBreakdownTrigger) + GIH%
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val gradePill: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        letterGrade(card.value),
                        style = AppTheme.typography.monoValue.copy(fontWeight = FontWeight.ExtraBold),
                        color = tierColor,
                    )
                    if (card.value != null) {
                        Text(
                            "(${card.value.roundToInt()})",
                            style = AppTheme.typography.monoMicro,
                            color = AppTheme.colors.textMuted,
                        )
                    }
                }
            }
            if (card.breakdown != null) {
                ScoreBreakdownTrigger(card.breakdown, modelExplain) { gradePill() }
            } else {
                gradePill()
            }
            AppTooltip(
                title = "GIH Win Rate",
                tooltip = "17Lands Games in Hand win rate — how often players win games when this card is drawn or in their opening hand.",
            ) {
                Text(
                    text = if (card.gihWr != null) "${card.gihWr.asPct()}%" else "—",
                    style = AppTheme.typography.monoSmall,
                    color = winRateColor(card.gihWr),
                )
            }
        }
    }
}

@Composable
private fun SynergyCoverage(researched: List<String>, grounded: List<String>, modelSets: List<String> = emptyList()) {
    var expandedTier by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .appTable(radius = AppTheme.radii.xl),
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
