package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
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
internal fun PackPane(
    state: DraftUiState,
    onSimulate: (String) -> Unit = {},
    onOpenGuide: () -> Unit = {},
) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (state.simulating) "Setting up the demo draft…" else "Loading 17Lands data…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.packCards.isEmpty() -> Centered {
        val idle = state.phase == DraftPhase.IDLE
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (idle) "Start a draft in MTG Arena" else "No cards in the current pack",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (idle) {
                    Text(
                        "Enable Options → Account → Detailed Logs (Plugin Support)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (DevFlags.demoEnabled && idle && !state.simulating) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "OR TRY A DEMO DRAFT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.7.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if (shouldShowGuideStarter(state)) {
            SetGuideStarter(state, onOpenGuide)
            Spacer(Modifier.height(7.dp))
        }
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
        else -> "Draft and sealed fundamentals"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), RoundedCornerShape(9.dp))
            .semantics { role = Role.Button }
            .focusable()
            .clickable(role = Role.Button, onClick = onOpenGuide)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$title draft guide",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Open guide  ›", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SynergyCoverage(researched: List<String>, grounded: List<String>, modelSets: List<String> = emptyList()) {
    var expandedTier by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(vertical = 8.dp),
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
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
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "${sets.size} sets ${if (expanded) "︿" else "⌄"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(sets.joinToString("  "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DemoSetButton(set: String, recommended: Boolean, onClick: () -> Unit) {
    if (recommended) {
        Button(onClick = onClick) {
            Text(set, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(set, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        }
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
            ScoreBreakdownTrigger(card.breakdown, model) { GradeBadge(card.value) }
        } else {
            GradeBadge(card.value)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bombConsistent) Text("★ ", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                CardPreview(card.imageUrl, description = card.name) {
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
