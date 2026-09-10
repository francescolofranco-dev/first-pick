package com.firstpick.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.guide.GuideArchetype
import com.firstpick.guide.GuideCard
import com.firstpick.guide.GuideCombo
import com.firstpick.guide.GuidePrinciple
import com.firstpick.guide.GuideSource
import com.firstpick.guide.LimitedGuidance
import com.firstpick.guide.SetDraftGuide
import com.firstpick.model.DraftFormat

private enum class GuidePage { SET_DRAFT, SEALED }

@Composable
internal fun GuidePane(
    state: DraftUiState,
    onOpenGuideSource: (String) -> Unit = {},
) {
    var page by remember(state.setCode, state.format) {
        mutableStateOf(
            if (state.setCode == null || state.format == DraftFormat.SEALED) GuidePage.SEALED
            else GuidePage.SET_DRAFT,
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            GuidePageButton(
                label = state.setCode?.let { "$it set guide" } ?: "Set guide",
                selected = page == GuidePage.SET_DRAFT,
            ) { page = GuidePage.SET_DRAFT }
            GuidePageButton("Sealed deck building", page == GuidePage.SEALED) { page = GuidePage.SEALED }
        }
        Spacer(Modifier.height(9.dp))

        when (page) {
            GuidePage.SET_DRAFT -> SetGuideContent(state, onOpenGuideSource)
            GuidePage.SEALED -> SealedGuideContent(onOpenGuideSource)
        }
    }
}

@Composable
private fun GuidePageButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetGuideContent(state: DraftUiState, onOpenSource: (String) -> Unit) {
    val guide = state.setGuide
    when {
        state.setCode == null -> GuideEmptyState(
            title = "No set guide yet",
            body = "Start a draft in MTG Arena and the guide for that set will appear here.",
        )
        guide == null && state.guideLoading -> GuideLoadingState(state.setCode)
        guide == null -> GuideEmptyState(
            title = "No guide for ${state.setCode}",
            body = "Set-specific notes are unavailable, but the sealed deck-building guide is always available.",
        )
        else -> SetGuideReady(guide, onOpenSource)
    }
}

@Composable
private fun GuideLoadingState(setCode: String) = Centered {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Preparing the $setCode draft guide…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideEmptyState(title: String, body: String) = Centered {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(430.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetGuideReady(guide: SetDraftGuide, onOpenSource: (String) -> Unit) {
    var expandedPair by remember(guide.setCode) { mutableStateOf<String?>(null) }
    var selectedColor by remember(guide.setCode) {
        mutableStateOf(guide.topCards.firstOrNull()?.color ?: 'W')
    }
    val colorCards = guide.topCards.firstOrNull { it.color == selectedColor }
    val observedDataSources = guideSourcesFor(guide.topCards.flatMap { it.sourceIds }, guide.sources)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Panel(guide.setName.ifBlank { guide.setCode }) {
                Text(
                    buildString {
                        append("${guide.setCode} draft reference")
                        if (guide.dataFormat.isNotBlank()) append(" · ${guideFormatLabel(guide.dataFormat)} data")
                        if (guide.generated.isNotBlank()) append(" · updated ${guide.generated}")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (guide.mechanics.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        guide.mechanics.joinToString("  ·  ") { it.name },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (guide.mechanics.isNotEmpty()) {
            item { GuideSectionLabel("Set mechanics", "Rules and practical draft implications") }
            items(guide.mechanics, key = { it.name }) { mechanic ->
                GuideTextCard(
                    mechanic.name,
                    mechanic.summary,
                    guideSourcesFor(mechanic.sourceIds, guide.sources),
                    onOpenSource,
                )
            }
        }

        if (guide.archetypes.isNotEmpty()) {
            item { GuideSectionLabel("Color pairs", "Select a pair to see its plan and important cards") }
            items(guide.archetypes, key = { it.pair }) { archetype ->
                ArchetypeGuideCard(
                    archetype = archetype,
                    expanded = expandedPair == archetype.pair,
                    onToggle = {
                        expandedPair = if (expandedPair == archetype.pair) null else archetype.pair
                    },
                    sources = guideSourcesFor(archetype.sourceIds, guide.sources),
                    onOpenSource = onOpenSource,
                )
            }
        }

        if (guide.combos.isNotEmpty()) {
            item { GuideSectionLabel("Combos & interactions", "Curated card relationships used by the synergy engine") }
            items(guide.combos) { combo ->
                GuideComboCard(
                    combo,
                    guideSourcesFor(combo.sourceIds, guide.sources),
                    onOpenSource,
                )
            }
        }

        if (guide.topCards.any { it.commons.isNotEmpty() || it.nonCommons.isNotEmpty() }) {
            item {
                GuideSectionLabel("Best cards by color", "17Lands GIH win rate; reliable samples rank first")
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    guide.topCards.forEach { cards ->
                        ColorGuideButton(cards.color, selectedColor == cards.color) { selectedColor = cards.color }
                    }
                }
                if (observedDataSources.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    GuideCitations(observedDataSources, onOpenSource)
                }
            }
            item {
                Panel("Commons") {
                    GuideCardList(colorCards?.commons.orEmpty(), "No rated commons yet")
                }
            }
            item {
                Panel("Non-commons") {
                    GuideCardList(colorCards?.nonCommons.orEmpty(), "No rated non-commons yet")
                }
            }
        }

        if (guide.principles.isNotEmpty()) {
            item { GuideSectionLabel("Draft fundamentals", "The principles also used by FirstPick's engine") }
            items(guide.principles, key = { it.id }) { principle ->
                GuidePrincipleCard(
                    principle,
                    guideSourcesFor(principle.sourceIds, guide.sources),
                    onOpenSource,
                )
            }
        }

        if (guide.sources.isNotEmpty()) {
            item { GuideSectionLabel("Sources", "Open the original articles, data references, and shows") }
            items(guide.sources, key = { it.id.ifBlank { it.url } }) { source -> GuideSourceRow(source, onOpenSource) }
        }
    }
}

@Composable
private fun SealedGuideContent(onOpenSource: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Panel("Sealed deck construction") {
                Text(
                    "Compare complete color builds, then choose the one with the best balance of power, curve, interaction, and reliable mana.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Start with 40 cards · 17 lands · 23 spells",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item { GuideSectionLabel("Build checklist", "Work through these in order, then compare alternatives") }
        items(LimitedGuidance.sealedPrinciples, key = { it.id }) { principle ->
            GuidePrincipleCard(
                principle,
                guideSourcesFor(principle.sourceIds, LimitedGuidance.sealedSources),
                onOpenSource,
            )
        }
        item { GuideSectionLabel("Sources", "Authoritative Limited guidance behind this checklist") }
        items(LimitedGuidance.sealedSources, key = { it.id.ifBlank { it.url } }) { source -> GuideSourceRow(source, onOpenSource) }
    }
}

@Composable
private fun GuideSectionLabel(title: String, subtitle: String) {
    Column(Modifier.padding(top = 5.dp, start = 3.dp, end = 3.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideTextCard(
    title: String,
    body: String,
    sources: List<GuideSource> = emptyList(),
    onOpenSource: (String) -> Unit = {},
) {
    Panel(title) {
        Text(body, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        GuideCitations(sources, onOpenSource)
    }
}

@Composable
private fun ArchetypeGuideCard(
    archetype: GuideArchetype,
    expanded: Boolean,
    onToggle: () -> Unit,
    sources: List<GuideSource> = emptyList(),
    onOpenSource: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(archetype.pair.toList())
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${guildName(archetype.pair).ifBlank { archetype.pair }} · ${archetype.name}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    archetype.speed.takeIf(String::isNotBlank)?.let {
                        Text(it.replaceFirstChar(Char::uppercaseChar), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    archetype.winRate?.let {
                        Text(
                            "${it.asPct()} estimated pair WR",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(if (expanded) "︿" else "⌄", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Spacer(Modifier.height(9.dp))
            Text(archetype.plan, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            GuideNames("Signposts", archetype.signposts)
            GuideNames("Enablers", archetype.enablers)
            GuideNames("Payoffs", archetype.payoffs)
            GuideNames("Other key cards", archetype.keyCards)
            GuideCitations(sources, onOpenSource)
        }
    }
}

@Composable
private fun GuideCitations(sources: List<GuideSource>, onOpenSource: (String) -> Unit) {
    if (sources.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sources.forEach { source ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                    .heightIn(min = 44.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open source: ${source.title}"
                    }
                    .clickable { onOpenSource(source.url) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${source.kind} · ${source.title}",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text("↗", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GuideNames(label: String, names: List<String>) {
    if (names.isEmpty()) return
    Spacer(Modifier.height(7.dp))
    Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(names.joinToString(" · "), fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun GuideComboCard(
    combo: GuideCombo,
    sources: List<GuideSource>,
    onOpenSource: (String) -> Unit,
) {
    Panel(combo.cards.joinToString(" + ")) {
        if (combo.note.isNotBlank()) {
            Text(combo.note, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        GuideCitations(sources, onOpenSource)
    }
}

@Composable
private fun ColorGuideButton(color: Char, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) pipColor(color).copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface)
            .border(if (selected) 1.dp else 0.dp, pipColor(color).copy(alpha = 0.65f), CircleShape)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "${guideColorName(color)} cards"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Pip(color, 21.dp)
    }
}

@Composable
private fun GuideCardList(cards: List<GuideCard>, emptyLabel: String) {
    if (cards.isEmpty()) {
        Text(emptyLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        cards.forEachIndexed { index, card ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
            GuideCardRow(card)
        }
    }
}

@Composable
private fun GuideCardRow(card: GuideCard) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pip(card.color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            CardPreview(card.imageUrl) {
                Text(
                    card.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                card.rarity.replaceFirstChar(Char::uppercaseChar),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(card.gihWr.asPct(), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (card.games > 0) "${card.games} games" else "GIH WR",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuidePrincipleCard(
    principle: GuidePrinciple,
    sources: List<GuideSource> = emptyList(),
    onOpenSource: (String) -> Unit = {},
) {
    Panel(principle.title) {
        Text(principle.summary, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        if (principle.appliedByFirstPick.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                "FIRSTPICK · ${principle.appliedByFirstPick}",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        GuideCitations(sources, onOpenSource)
    }
}

@Composable
private fun GuideSourceRow(source: GuideSource, onOpenSource: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpenSource(source.url) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${source.kind} · ${source.publisher}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.author.isNotBlank() || source.date.isNotBlank()) {
                Text(
                    listOf(source.author, source.date).filter(String::isNotBlank).joinToString(" · "),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("↗", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun guideSourcesFor(sourceIds: List<String>, sources: List<GuideSource>): List<GuideSource> {
    if (sourceIds.isEmpty()) return emptyList()
    val byId = sources.filter { it.id.isNotBlank() }.associateBy { it.id }
    return sourceIds.mapNotNull(byId::get).distinctBy { it.id }
}

internal fun guideColorName(color: Char): String = when (color) {
    'W' -> "White"
    'U' -> "Blue"
    'B' -> "Black"
    'R' -> "Red"
    'G' -> "Green"
    else -> "Unknown"
}

internal fun guideFormatLabel(format: String): String = when (format) {
    "PremierDraft" -> "Premier"
    "QuickDraft" -> "Quick"
    "TradDraft" -> "Traditional"
    else -> format
}
