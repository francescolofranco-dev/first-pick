package com.firstpick.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.DeckNeeds

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Sidebar(state: DraftUiState, modifier: Modifier = Modifier.width(252.dp).fillMaxHeight()) {
    val scrollState = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. Raycast Bento Card: Lane Confidence & Needs
            LaneBentoCard(state)

            // 2. Raycast Bento Card: Pool Composition (2-Col Bento Stat Tiles + Official Mana Symbols) + Vertical Mana Curve Histogram
            if (state.poolSize > 0 || state.manaCurve.isNotEmpty()) {
                PoolAndCurveBentoCard(state)
            }

            // 3. Raycast Bento Card: Archetype Pair Win Rates (with Official Mana Symbols)
            if (state.archetypes.isNotEmpty()) {
                Panel(
                    title = "Estimated pair strength",
                    trailing = "17Lands",
                    tooltip = "Sample-adjusted two-color win-rate estimates. Low-sample pairs are pulled toward the overall field average.",
                ) {
                    val max = state.archetypes.maxOfOrNull { it.winRate } ?: 0.0
                    val min = state.archetypes.minOfOrNull { it.winRate } ?: 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (a in state.archetypes.take(6)) {
                            val frac = archetypeFraction(a.winRate, min, max)
                            ArchetypeSparkRow(row = a, fraction = frac)
                        }
                    }
                }
            }

            // 4. Raycast Bento Card: Open Color Signals (with Official Mana Symbols)
            if (state.openLanes.isNotEmpty()) {
                Panel(
                    title = "Open colors",
                    trailing = "Signals",
                    tooltip = "Colors that appear underdrafted by the seats passing to you, inferred from how late good cards arrive.",
                ) {
                    val max = state.openLanes.maxOfOrNull { it.score } ?: 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (cs in state.openLanes) {
                            ColorBar(cs.color, safeScoreFraction(cs.score, max))
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = AppTheme.colors.borderDefault,
                hoverColor = AppTheme.colors.accent,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaneBentoCard(state: DraftUiState) {
    val settled = state.lanePair != null && state.laneColors.size >= 2
    Column(
        Modifier
            .fillMaxWidth()
            .appPanel(padding = 11.dp, radius = AppTheme.radii.xl),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.poolSize == 0 && state.topPairs.isNotEmpty()) {
            val title = if (state.setCode != null) "TOP PAIRS · ${state.setCode.uppercase()}" else "TOP COLOR PAIRS"
            Text(
                title,
                style = AppTheme.typography.sectionHeader,
                color = AppTheme.colors.textSecondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.topPairs.forEach { pair ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PipRow(pair.toList(), size = 14.dp)
                        val name = guildName(pair)
                        if (name.isNotBlank()) {
                            Spacer(Modifier.width(7.dp))
                            Text(
                                name,
                                style = AppTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                                color = AppTheme.colors.textPrimary,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LANE CONFIDENCE",
                    style = AppTheme.typography.sectionHeader,
                    color = AppTheme.colors.textSecondary,
                )
                val statusColor = if (settled) AppTheme.colors.positive else AppTheme.colors.accentSecondary
                val statusText = if (settled) "SETTLED" else "EXPLORING"
                Box(
                    Modifier
                        .appBadge(statusColor, fillAlpha = 0.14f, borderAlpha = 0.36f, shape = RoundedCornerShape(AppTheme.radii.xs))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        statusText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = statusColor,
                    )
                }
            }

            if (state.laneColors.isEmpty()) {
                Text(
                    "Undecided — pick on raw card quality",
                    style = AppTheme.typography.body,
                    color = AppTheme.colors.textSecondary,
                )
            } else {
                val pair = state.lanePair
                val laneArchetype = state.archetypes.firstOrNull { it.isLane }
                val confidencePct = when {
                    settled && state.poolSize >= 10 -> 0.92f
                    settled -> 0.78f
                    state.laneColors.size == 1 -> 0.52f
                    else -> 0.38f
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PipRow(state.laneColors, size = 16.dp)
                        val label = pair?.let(::guildName)?.takeIf { it.isNotBlank() }
                            ?: if (state.laneColors.size == 1) "Second color open" else "Unsettled"
                        Text(
                            label,
                            style = AppTheme.typography.cardTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            color = AppTheme.colors.textPrimary,
                        )
                    }
                    if (laneArchetype != null) {
                        Text(
                            "${laneArchetype.winRate.asPct()}% WR",
                            style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.Bold),
                            color = AppTheme.colors.accent,
                        )
                    }
                }

                // Raycast Coral-to-Amber Confidence Progress Bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(AppTheme.colors.background),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(confidencePct)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(AppTheme.colors.accent, AppTheme.colors.accentSecondary),
                                ),
                            ),
                    )
                }
            }
        }

        // Needs section integrated into the Lane Bento Card
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
        NeedsContent(state.deckNeeds, state.poolSize, state.deckNeedsAvailable)
    }
}

@Composable
private fun PoolAndCurveBentoCard(state: DraftUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .appPanel(padding = 11.dp, radius = AppTheme.radii.xl),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "POOL & MANA CURVE",
                style = AppTheme.typography.sectionHeader,
                color = AppTheme.colors.textSecondary,
            )
            Text(
                "${state.poolSize} picks",
                style = AppTheme.typography.monoMicro,
                color = AppTheme.colors.textMuted,
            )
        }

        // 2-Column Bento Stat Tiles (Raycast style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BentoMiniStatTile(
                label = "Creatures",
                value = "${state.poolCreatures} / ${state.poolSize}",
                valueColor = AppTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            BentoMiniStatTile(
                label = "Non-creatures",
                value = "${state.poolNonCreatures} spells",
                valueColor = AppTheme.colors.accentSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        // Vertical Mana Curve Histogram
        if (state.manaCurve.isNotEmpty()) {
            ManaCurveHistogram(state)
        }
    }
}

@Composable
private fun BentoMiniStatTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppTheme.radii.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(AppTheme.colors.background)
            .border(1.dp, AppTheme.colors.borderHairline, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = AppTheme.typography.micro,
            color = AppTheme.colors.textSecondary,
        )
        Text(
            value,
            style = AppTheme.typography.monoValue.copy(fontWeight = FontWeight.Bold),
            color = valueColor,
        )
    }
}

@Composable
private fun ManaCurveHistogram(state: DraftUiState) {
    val maxCount = (state.manaCurve.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(AppTheme.radii.md))
                .background(AppTheme.colors.background)
                .border(1.dp, AppTheme.colors.borderHairline, RoundedCornerShape(AppTheme.radii.md))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            for (cb in state.manaCurve) {
                val frac = safeFloatFraction(cb.count.toFloat() / maxCount.toFloat())
                val isPeak = cb.count > 0 && cb.count == maxCount
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = "${cb.count}",
                        style = AppTheme.typography.monoMicro.copy(
                            fontSize = 9.sp,
                            fontWeight = if (cb.count > 0) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (cb.count > 0) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(if (cb.count > 0) frac.coerceAtLeast(0.14f) else 0.05f)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(
                                    when {
                                        cb.count == 0 -> Brush.verticalGradient(
                                            listOf(AppTheme.colors.borderSubtle, AppTheme.colors.borderSubtle),
                                        )
                                        isPeak -> Brush.verticalGradient(
                                            listOf(AppTheme.colors.accent, AppTheme.colors.accentBright),
                                        )
                                        else -> Brush.verticalGradient(
                                            listOf(AppTheme.colors.accentSecondary, AppTheme.colors.accentSecondary.copy(alpha = 0.70f)),
                                        )
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = cb.cmcLabel,
                        style = AppTheme.typography.monoMicro.copy(fontSize = 9.sp),
                        color = AppTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        // Official MTG Mana Symbols Pool Distribution Strip
        val nonZeroColors = state.poolColorCounts.filter { it.score > 0 }
        if (nonZeroColors.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (cs in nonZeroColors) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppTheme.radii.xs))
                            .background(AppTheme.colors.background)
                            .border(1.dp, AppTheme.colors.borderHairline, RoundedCornerShape(AppTheme.radii.xs))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Pip(cs.color, size = 13.dp)
                        Text(
                            text = "${cs.score.toInt()}",
                            style = AppTheme.typography.monoMicro.copy(fontWeight = FontWeight.Bold),
                            color = AppTheme.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchetypeSparkRow(row: ArchetypeRow, fraction: Float) {
    val safeFrac = safeFloatFraction(fraction)
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    val fillBrush = if (row.isLane) {
        Brush.horizontalGradient(
            listOf(
                AppTheme.colors.accent.copy(alpha = 0.28f),
                AppTheme.colors.accentSecondary.copy(alpha = 0.14f),
            ),
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                AppTheme.colors.surfaceElevated,
                AppTheme.colors.surfaceElevated.copy(alpha = 0.65f),
            ),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(shape)
            .background(AppTheme.colors.background)
            .border(
                width = 1.dp,
                color = if (row.isLane) AppTheme.colors.accent.copy(alpha = 0.50f) else AppTheme.colors.borderHairline,
                shape = shape,
            ),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(safeFrac)
                .background(fillBrush),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (row.isLane) {
                        drawLine(
                            color = AppTheme.colors.accent,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                    }
                }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PipRow(row.pair.toList(), size = 13.dp)
                val name = guildName(row.pair).ifBlank { row.pair }
                Text(
                    text = name,
                    style = AppTheme.typography.caption.copy(
                        fontWeight = if (row.isLane) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (row.isLane) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${row.winRate.asPct()}%",
                style = AppTheme.typography.monoSmall.copy(
                    fontWeight = if (row.isLane) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (row.isLane) AppTheme.colors.accent else AppTheme.colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsContent(needs: List<String>, poolSize: Int, available: Boolean) {
    when {
        poolSize == 0 -> Text(
            "Pick on raw value early.",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textSecondary,
        )
        poolSize < DeckNeeds.DISPLAY_MIN_POOL -> Text(
            "Too early to assess gaps · $poolSize/${DeckNeeds.DISPLAY_MIN_POOL} picks.",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textSecondary,
        )
        !available -> Text(
            "Waiting for card data to assess gaps…",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textSecondary,
        )
        needs.isEmpty() -> Text(
            "● Curve and interaction balanced",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.positive,
        )
        else -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            needs.forEach { need ->
                Box(
                    Modifier
                        .appBadge(
                            AppTheme.colors.accentSecondary,
                            fillAlpha = 0.14f,
                            borderAlpha = 0.36f,
                            shape = RoundedCornerShape(AppTheme.radii.xs),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        need,
                        style = AppTheme.typography.micro.copy(fontWeight = FontWeight.SemiBold),
                        color = AppTheme.colors.accentSecondary,
                    )
                }
            }
        }
    }
}
