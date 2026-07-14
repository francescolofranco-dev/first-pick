package com.firstpick.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.Confidence
import com.firstpick.advisor.ConfidenceLevel
import com.firstpick.model.DraftPhase
import com.firstpick.sim.DraftSimulator

@Composable
internal fun PackPane(state: DraftUiState, onSimulate: (String) -> Unit = {}) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (state.simulating) "Setting up the demo draft…" else "Loading 17Lands data…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.packCards.isEmpty() -> Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (state.phase == DraftPhase.IDLE) {
                    "Start a draft in MTG Arena.\nEnable Options → Account → Detailed Logs (Plugin Support)."
                } else {
                    "No cards in the current pack."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (DevFlags.demoEnabled && state.phase == DraftPhase.IDLE && !state.simulating) {
                Text("— or try a demo draft —", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftSimulator.SETS.forEach { set -> DemoSetButton(set) { onSimulate(set) } }
                }
            }
            if (state.phase == DraftPhase.IDLE && state.researchedSets.isNotEmpty()) {
                SynergyCoverage(state.researchedSets, state.groundedSets, state.modelSets)
            }
        }
    }

    else -> Column(Modifier.fillMaxSize()) {
        ConfidenceBanner(state.packCards)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(state.packCards, key = { "${it.grpId}#${it.rank}" }) { PackRow(it, state.packCards.size) }
        }
    }
}

@Composable
private fun SynergyCoverage(researched: List<String>, grounded: List<String>, modelSets: List<String> = emptyList()) {
    Column(
        Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Set coverage", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SynergyTierBadge("researched")
            Text(researched.joinToString("  "), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text("Full archetype + combo synergy on these sets.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (grounded.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SynergyTierBadge("data")
                Text(grounded.joinToString("  "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Text("Ratings-driven picks; lighter synergy signal.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (modelSets.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SynergyTierBadge("model")
                Text(modelSets.joinToString("  "), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text("Learned pick model — ranks trained on winning drafters.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DemoSetButton(set: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(set, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ConfidenceBanner(cards: List<PackCardUi>) {
    val confidence = Confidence.of(cards.mapNotNull { it.value })
    if (confidence.level == ConfidenceLevel.CLEAR) return
    val tossUp = confidence.level == ConfidenceLevel.TOSS_UP
    val accent = if (tossUp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(if (tossUp) "Toss-up" else "Lean", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (tossUp) "Top ${confidence.contenders} are nearly tied — trust your read." else "Only a slight edge to the top pick.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackRow(card: PackCardUi, packSize: Int) {
    val top = card.rank == 1


    val bombConsistent = card.isBomb && isBombTier(card.value)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (top) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            )
            .border(
                if (top) 1.dp else 0.dp,
                if (top) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${card.rank}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TooltipArea(tooltip = { BreakdownTooltip(card.breakdown, model) }) { GradeBadge(card.value) }
        } else {
            GradeBadge(card.value)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bombConsistent) Text("★ ", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                CardPreview(card.imageUrl) {
                    Text(
                        card.name,
                        fontSize = 14.sp,
                        fontWeight = if (top) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val shownReasons = if (bombConsistent) card.reasons else card.reasons.filter { it != "Bomb" }
            if (shownReasons.isNotEmpty()) {
                Text(
                    shownReasons.joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(44.dp)) {
            Text(card.gihWr.asPct(), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("GIH", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
