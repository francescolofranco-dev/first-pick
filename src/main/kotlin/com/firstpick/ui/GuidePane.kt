package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.guide.GuideArchetype
import com.firstpick.guide.GuideCard
import com.firstpick.guide.GuideCombo
import com.firstpick.guide.GuidePrinciple
import com.firstpick.guide.GuideSource
import com.firstpick.guide.SetDraftGuide

@Composable
internal fun GuidePane(
    state: DraftUiState,
    onOpenGuideSource: (String) -> Unit = {},
) {
    SetGuideContent(state, onOpenGuideSource)
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
            body = "Set-specific draft notes are unavailable for this set.",
        )
        else -> SetGuideReady(guide, onOpenSource)
    }
}

@Composable
private fun GuideLoadingState(setCode: String) = Centered {
    Row(
        modifier = Modifier.appPanel(padding = 14.dp, radius = AppTheme.radii.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.colors.accent)
        Spacer(Modifier.width(10.dp))
        Text("Preparing the $setCode draft guide…", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
    }
}

@Composable
private fun GuideEmptyState(title: String, body: String) = Centered {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(420.dp).appPanel(padding = 18.dp, radius = AppTheme.radii.lg),
    ) {
        Text(title, style = AppTheme.typography.paneTitle, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.textSecondary,
                )
                if (guide.mechanics.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        guide.mechanics.forEach { mechanic ->
                            Box(
                                Modifier
                                    .appBadge(
                                        AppTheme.colors.accent,
                                        fillAlpha = 0.11f,
                                        borderAlpha = 0.28f,
                                        shape = RoundedCornerShape(AppTheme.radii.xs),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    mechanic.name,
                                    style = AppTheme.typography.micro.copy(fontWeight = FontWeight.SemiBold),
                                    color = AppTheme.colors.accent,
                                )
                            }
                        }
                    }
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
                    Spacer(Modifier.height(6.dp))
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
            items(guide.sources, key = { it.id.ifBlank { it.url } }) { source ->
                GuideSourceRow(source, onOpenSource)
            }
        }
    }
}

@Composable
private fun GuideSectionLabel(title: String, subtitle: String) {
    Column(Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
        Text(title, style = AppTheme.typography.cardTitle, color = AppTheme.colors.textPrimary)
        Text(subtitle, style = AppTheme.typography.micro, color = AppTheme.colors.textSecondary)
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
        Text(body, style = AppTheme.typography.body, color = AppTheme.colors.textPrimary)
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
            .appCard(selected = expanded, radius = AppTheme.radii.lg)
            .clickable(onClick = onToggle)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(archetype.pair.toList())
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${guildName(archetype.pair).ifBlank { archetype.pair }} · ${archetype.name}",
                    style = AppTheme.typography.cardTitle,
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    archetype.speed.takeIf(String::isNotBlank)?.let {
                        Text(
                            it.replaceFirstChar(Char::uppercaseChar),
                            style = AppTheme.typography.micro,
                            color = AppTheme.colors.accent,
                        )
                    }
                    archetype.winRate?.let {
                        Text(
                            "${it.asPct()} estimated pair WR",
                            style = AppTheme.typography.monoMicro,
                            color = AppTheme.colors.textSecondary,
                        )
                    }
                }
            }
            Text(
                if (expanded) "︿" else "⌄",
                fontSize = 12.sp,
                color = AppTheme.colors.textSecondary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
            Spacer(Modifier.height(8.dp))
            Text(archetype.plan, style = AppTheme.typography.body, color = AppTheme.colors.textPrimary)
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
                    .appBadge(
                        AppTheme.colors.accent,
                        fillAlpha = 0.09f,
                        borderAlpha = 0.24f,
                        shape = RoundedCornerShape(AppTheme.radii.xs),
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open source: ${source.title}"
                    }
                    .clickable { onOpenSource(source.url) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${source.kind} · ${source.title}",
                    modifier = Modifier.weight(1f),
                    style = AppTheme.typography.micro,
                    color = AppTheme.colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text("↗", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.accent)
            }
        }
    }
}

@Composable
private fun GuideNames(label: String, names: List<String>) {
    if (names.isEmpty()) return
    Spacer(Modifier.height(7.dp))
    Text(
        label.uppercase(),
        style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
        color = AppTheme.colors.textSecondary,
    )
    Text(
        names.joinToString(" · "),
        style = AppTheme.typography.caption,
        color = AppTheme.colors.textPrimary,
    )
}

@Composable
private fun GuideComboCard(
    combo: GuideCombo,
    sources: List<GuideSource>,
    onOpenSource: (String) -> Unit,
) {
    Panel(combo.cards.joinToString(" + ")) {
        if (combo.note.isNotBlank()) {
            Text(combo.note, style = AppTheme.typography.body, color = AppTheme.colors.textPrimary)
        }
        GuideCitations(sources, onOpenSource)
    }
}

@Composable
private fun ColorGuideButton(color: Char, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) pipColor(color).copy(alpha = 0.20f) else AppTheme.colors.surface)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) pipColor(color).copy(alpha = 0.72f) else AppTheme.colors.borderSubtle,
                CircleShape,
            )
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "${guideColorName(color)} cards"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Pip(color, 18.dp)
    }
}

@Composable
private fun GuideCardList(cards: List<GuideCard>, emptyLabel: String) {
    if (cards.isEmpty()) {
        Text(emptyLabel, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cards.forEachIndexed { index, card ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
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
                    style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                card.rarity.replaceFirstChar(Char::uppercaseChar),
                style = AppTheme.typography.micro.copy(fontSize = 9.sp),
                color = AppTheme.colors.textSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(card.gihWr.asPct(), style = AppTheme.typography.monoValue, color = AppTheme.colors.textPrimary)
            Text(
                if (card.games > 0) "${card.games} games" else "GIH WR",
                style = AppTheme.typography.monoMicro,
                color = AppTheme.colors.textMuted,
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
        Text(principle.summary, style = AppTheme.typography.body, color = AppTheme.colors.textPrimary)
        if (principle.appliedByFirstPick.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                "FIRSTPICK · ${principle.appliedByFirstPick}",
                style = AppTheme.typography.micro.copy(fontWeight = FontWeight.SemiBold),
                color = AppTheme.colors.accent,
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
            .appRow(paddingValues = PaddingValues(horizontal = 12.dp, vertical = 9.dp))
            .clickable { onOpenSource(source.url) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.title,
                style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = AppTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${source.kind} · ${source.publisher}",
                style = AppTheme.typography.micro,
                color = AppTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.author.isNotBlank() || source.date.isNotBlank()) {
                Text(
                    listOf(source.author, source.date).filter(String::isNotBlank).joinToString(" · "),
                    style = AppTheme.typography.micro.copy(fontSize = 9.sp),
                    color = AppTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("↗", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.accent)
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
    else -> format
}
