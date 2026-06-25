package com.firstpick.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.Confidence
import com.firstpick.advisor.ConfidenceLevel
import com.firstpick.model.DraftPhase

@Composable
internal fun PackPane(state: DraftUiState) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Loading 17Lands data…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.packCards.isEmpty() -> Centered {
        Text(
            if (state.phase == DraftPhase.IDLE) {
                "Start a draft in MTG Arena.\nEnable Options → Account → Detailed Logs (Plugin Support)."
            } else {
                "No cards in the current pack."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    else -> Column(Modifier.fillMaxSize()) {
        ConfidenceBanner(state.packCards)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Composite key: grpId can repeat in cube/special packs; rank keeps it unique.
            items(state.packCards, key = { "${it.grpId}#${it.rank}" }) { PackRow(it) }
        }
    }
}

/** Flags when the top pick isn't clearly ahead — "use your judgment". */
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
private fun PackRow(card: PackCardUi) {
    val top = card.rank == 1
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
            TooltipArea(tooltip = { BreakdownTooltip(card.breakdown) }) { GradeBadge(card.value) }
        } else {
            GradeBadge(card.value)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.isBomb) Text("★ ", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
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
            if (card.reasons.isNotEmpty()) {
                Text(
                    card.reasons.joinToString(" · "),
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
