package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Sidebar(state: DraftUiState) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.poolSize == 0 && state.topPairs.isNotEmpty()) {
            val title = if (state.setCode != null) "Top color pairs for ${state.setCode.uppercase()}" else "Top color pairs"
            Panel(title) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.topPairs.forEach { pair ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PipRow(pair.toList())
                            val name = guildName(pair)
                            if (name.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        } else {
            Panel("Your lane") {
                if (state.laneColors.isEmpty()) {
                    Text("Undecided", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PipRow(state.laneColors)
                        state.lanePair?.let { pair ->
                            val name = guildName(pair)
                            if (name.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        if (state.archetypes.isNotEmpty()) {
            Panel("Color pair win rate") {
                val max = state.archetypes.maxOf { it.winRate }
                val min = state.archetypes.minOf { it.winRate }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.archetypes.take(6).forEach { a ->
                        val frac = if (max > min) 0.15 + 0.85 * (a.winRate - min) / (max - min) else 1.0
                        val color = if (a.isLane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.pair,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (a.isLane) FontWeight.Bold else FontWeight.Normal,
                                color = if (a.isLane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(26.dp),
                            )
                            Bar(frac.toFloat(), color, Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "%.1f".format(a.winRate * 100),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.width(30.dp),
                            )
                        }
                    }
                }
            }
        }
        Panel("Deck needs") { NeedsContent(state.deckNeeds, state.poolSize) }
        Panel(
            title = "Open lanes",
            tooltip = "Which color pairs are open, based on the quality of cards being passed to you. A high bar means a strong lane to move into.",
        ) {
            if (state.openLanes.isEmpty()) {
                Text("Reading signals…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val max = state.openLanes.maxOf { it.score }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.openLanes.forEach { ColorBar(it.color, it.score / max) }
                }
            }
        }
        Panel("Mana curve") {
            val max = (state.manaCurve.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.manaCurve.forEach { bar ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bar.cmcLabel, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(24.dp))
                        Bar(bar.count.toFloat() / max, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${bar.count}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(22.dp),
                        )
                    }
                }
            }
        }
        Panel("Pool · ${state.poolSize}") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (state.poolColorCounts.isEmpty()) {
                    Text("No picks yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val max = state.poolColorCounts.maxOf { it.score }
                    state.poolColorCounts.forEach { ColorBar(it.color, it.score / max, "${it.score.toInt()}") }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${state.poolCreatures} creatures · ${state.poolNonCreatures} spells",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsContent(needs: List<String>, poolSize: Int) {
    when {
        poolSize == 0 -> Text(
            "Pick on raw value early.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        needs.isEmpty() -> Text(
            "Backbone looks solid ✓",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            needs.forEach { need ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(need, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
