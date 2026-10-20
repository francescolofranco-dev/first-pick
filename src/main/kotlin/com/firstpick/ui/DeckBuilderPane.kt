package com.firstpick.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DeckOptionUi.deckBuildKey: String
    get() = colors + "|" + (spells + lands).sortedBy { it.name }
        .joinToString("|") { "${it.name}:${it.count}" }

internal fun resolveDeckPreviewKey(
    optionKeys: List<String>,
    previewKey: String?,
    committedKey: String?,
): String? = previewKey?.takeIf { it in optionKeys }
    ?: committedKey?.takeIf { it in optionKeys }
    ?: optionKeys.firstOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckBuilderPane(
    options: List<DeckOptionUi>,
    savedDeck: com.firstpick.overlay.DeckSnapshot? = null,
    committedDeck: DeckOptionUi?,
    onUseDeck: (DeckOptionUi) -> Unit,
    guidanceEnabled: Boolean = false,
) {
    if (options.isEmpty()) return

    val optionKeys = options.map { it.deckBuildKey }
    val committedKey = committedDeck?.deckBuildKey
    var previewKey by remember { mutableStateOf<String?>(null) }
    val resolvedPreviewKey = resolveDeckPreviewKey(optionKeys, previewKey, committedKey)
    val sel = options.first { it.deckBuildKey == resolvedPreviewKey }

    LaunchedEffect(optionKeys, committedKey) {
        previewKey = resolveDeckPreviewKey(optionKeys, previewKey, committedKey)
    }

    val spellCount = sel.spells.sumOf { it.count }
    ScrollableDeckPane {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Draft complete — choose your build",
                    style = AppTheme.typography.paneTitle,
                    color = AppTheme.colors.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (!guidanceEnabled) {
                        "Click a build card to select it. Use Show overlay for Arena guidance."
                    } else {
                        "Arena guidance is on for ${sel.title}. Use Hide overlay to turn it off."
                    },
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.textSecondary,
                )
                savedDeck?.let { saved ->
                    val comparison = com.firstpick.overlay.DeckGuidance.evaluate(
                        com.firstpick.deckGuidanceTarget(committedDeck ?: sel),
                        com.firstpick.overlay.DeckReadState.Ready(saved),
                    )
                    Text(
                        savedDeckComparisonLabel(comparison),
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEachIndexed { index, opt ->
                        OptionCard(
                            opt = opt,
                            recommended = index == 0,
                            previewed = opt.deckBuildKey == resolvedPreviewKey,
                            active = guidanceEnabled && opt.deckBuildKey == committedKey,
                            modifier = Modifier.fillMaxRowHeight(),
                            onClick = {
                                previewKey = opt.deckBuildKey
                                onUseDeck(opt)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .appPanel(padding = 10.dp, radius = AppTheme.radii.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "${sel.title} (${sel.colors}) · $spellCount spells · ${sel.landLine}",
                        style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = AppTheme.colors.textPrimary,
                    )
                    Text(
                        (listOf(sel.identityLine) + sel.identityReasons.take(2) + sel.powerReasons.take(2)).joinToString(" · "),
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                DeckListHeader()
                Spacer(Modifier.height(4.dp))
            }
        }
        items(sel.spells) { DeckSpellRow(it) }
        if (sel.lands.isNotEmpty()) {
            item { SectionLabel("Lands") }
            items(sel.lands) { DeckSpellRow(it) }
        }
    }
}

@Composable
internal fun DeckSoFarPane(deck: DeckOptionUi, cuts: List<DeckSpellUi>) {
    val spellCount = deck.spells.sumOf { it.count }
    ScrollableDeckPane {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Deck so far",
                    style = AppTheme.typography.paneTitle,
                    color = AppTheme.colors.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Projected from your picks — updates every pick, not a final build.",
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .appPanel(padding = 12.dp, radius = AppTheme.radii.md),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PipRow(deck.colors.toList())
                        Spacer(Modifier.width(7.dp))
                        Text(
                            deck.title,
                            style = AppTheme.typography.cardTitle,
                            color = AppTheme.colors.textPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        DeckPowerSummary(deck)
                    }
                    Text(
                        "$spellCount/23 spells · ${deck.creatures} creatures · ${deck.removal} removal",
                        style = AppTheme.typography.body,
                        color = AppTheme.colors.textSecondary,
                    )
                    Text(
                        (listOf(deck.identityLine) + deck.powerReasons.take(2)).joinToString(" · "),
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                DeckListHeader()
                Spacer(Modifier.height(4.dp))
            }
        }
        item { SectionLabel("In the deck") }
        items(deck.spells) { DeckSpellRow(it) }
        if (deck.lands.isNotEmpty()) {
            item { SectionLabel("Lands") }
            items(deck.lands) { DeckSpellRow(it) }
        }
        if (cuts.isNotEmpty()) {
            item { SectionLabel("Not making the cut yet") }
            items(cuts) { DeckSpellRow(it, muted = true) }
        }
    }
}

// Keep the controls and cards in one scroll area so a short window cannot squeeze the list out.
@Composable
private fun ScrollableDeckPane(content: LazyListScope.() -> Unit) {
    val scrollState = rememberLazyListState()
    val scrolledPastHeader = scrollState.firstVisibleItemIndex > 0
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            state = scrollState,
            contentPadding = PaddingValues(bottom = if (scrolledPastHeader) 0.01.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
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

@Composable
private fun DeckListHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "COST",
            style = AppTheme.typography.sectionHeader,
            color = AppTheme.colors.textMuted,
            modifier = Modifier.width(DeckManaCostWidth),
        )
        Text(
            "CARD",
            style = AppTheme.typography.sectionHeader,
            color = AppTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            "TYPE / ROLE",
            style = AppTheme.typography.sectionHeader,
            color = AppTheme.colors.textMuted,
            modifier = Modifier.width(136.dp),
        )
        Text(
            "GIH%",
            style = AppTheme.typography.sectionHeader,
            color = AppTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = AppTheme.typography.sectionHeader,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 10.dp),
    )
}

@Composable
private fun DeckSpellRow(s: DeckSpellUi, muted: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (muted) 0.5f else 1f)
            .appRow(paddingValues = PaddingValues(horizontal = 10.dp, vertical = 5.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ManaCost(
            s.manaCost,
            isLand = s.isLand,
            modifier = Modifier.width(DeckManaCostWidth).padding(end = 6.dp),
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (s.count > 1) {
                Box(
                    Modifier
                        .appBadge(
                            AppTheme.colors.accent,
                            fillAlpha = 0.14f,
                            borderAlpha = 0.34f,
                            shape = RoundedCornerShape(AppTheme.radii.xs),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        "${s.count}×",
                        style = AppTheme.typography.monoMicro.copy(fontWeight = FontWeight.Bold),
                        color = AppTheme.colors.accent,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            CardPreview(s.imageUrl, description = s.name) {
                Text(
                    s.name,
                    style = AppTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.width(136.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                s.typeLabel,
                style = AppTheme.typography.micro,
                color = AppTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            s.role?.let {
                Spacer(Modifier.width(5.dp))
                RoleChip(it)
            }
        }
        Text(
            s.gihWr.asPct(),
            style = AppTheme.typography.monoSmall,
            color = AppTheme.colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
private fun RoleChip(role: String) {
    val color = roleColor(role)
    Box(
        Modifier
            .appBadge(
                color,
                fillAlpha = 0.14f,
                borderAlpha = 0.36f,
                shape = RoundedCornerShape(AppTheme.radii.xs),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            role,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun roleColor(role: String): Color = when (role) {
    "Removal" -> AppTheme.colors.danger
    "Fixing" -> AppTheme.colors.accent
    "Finisher" -> AppTheme.colors.warning
    else -> AppTheme.colors.accentSecondary
}

@Composable
private fun OptionCard(
    opt: DeckOptionUi,
    recommended: Boolean,
    previewed: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .width(204.dp)
            .appCard(selected = previewed, active = active, radius = AppTheme.radii.lg)
            .semantics {
                selected = previewed
                stateDescription = buildString {
                    append(if (previewed) "Selected" else "Not selected")
                    if (active) append(", guidance active")
                }
            }
            .focusable()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier.height(13.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                active -> Text(
                    "GUIDANCE ACTIVE",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.accent,
                )
                recommended -> Text(
                    "RECOMMENDED",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.accent,
                )
                else -> Text(
                    "ALTERNATIVE",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.textMuted,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(opt.colors.toList())
            Spacer(Modifier.width(6.dp))
            Text(
                opt.title,
                style = AppTheme.typography.cardTitle,
                color = AppTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DeckPowerSummary(opt)
        Text(
            opt.identityLine,
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            opt.outlook,
            style = AppTheme.typography.caption,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${opt.creatures} creatures · ${opt.removal} removal",
            style = AppTheme.typography.micro,
            color = AppTheme.colors.textMuted,
        )
    }
}

@Composable
private fun DeckPowerSummary(deck: DeckOptionUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        deck.displayTier?.let {
            TierBadge(it)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            deck.powerLabel,
            style = AppTheme.typography.monoValue.copy(fontWeight = FontWeight.SemiBold),
            color = AppTheme.colors.accent,
        )
    }
}

@Composable
private fun TierBadge(tier: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(AppTheme.radii.xs))
            .background(AppTheme.colors.warning)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            tier,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textOnAccent,
        )
    }
}

internal fun savedDeckComparisonLabel(result: com.firstpick.overlay.DeckGuidanceResult): String {
    val summary = if (result.status == com.firstpick.overlay.DeckGuidanceStatus.MATCHES) "matches this build"
    else "${result.remainingRemovals} cuts · ${result.remainingAdds} adds" +
        if (result.basicLandDelta != 0) " · adjust basic lands by ${result.basicLandDelta}" else ""
    return "Last saved deck: $summary. Unsaved edits are checked in Arena."
}
