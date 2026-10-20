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
import androidx.compose.ui.graphics.Brush
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
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 600.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            var globalIndex = 0
                            for (rowOptions in options.chunked(3)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (opt in rowOptions) {
                                        val index = globalIndex++
                                        OptionCard(
                                            opt = opt,
                                            recommended = index == 0,
                                            previewed = opt.deckBuildKey == resolvedPreviewKey,
                                            active = guidanceEnabled && opt.deckBuildKey == committedKey,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onClick = {
                                                previewKey = opt.deckBuildKey
                                                onUseDeck(opt)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
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
                                    modifier = Modifier.width(214.dp).fillMaxRowHeight(),
                                    onClick = {
                                        previewKey = opt.deckBuildKey
                                        onUseDeck(opt)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                DeckBentoSummaryCard(sel, spellCount, showTitleHeader = false)
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
                DeckBentoSummaryCard(deck, spellCount, targetSpells = 23)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckBentoSummaryCard(
    deck: DeckOptionUi,
    spellCount: Int,
    targetSpells: Int? = null,
    showTitleHeader: Boolean = true,
) {
    val outlookShort = deck.outlook.substringBefore(" · ").trim()
    val outlookLower = outlookShort.lowercase()
    val outlookColor = when {
        "fragile" in outlookLower || "risky" in outlookLower || "short" in outlookLower -> AppTheme.colors.warning
        "average" in outlookLower && "above" !in outlookLower -> AppTheme.colors.accentSecondary
        "provisional" in outlookLower -> AppTheme.colors.accentSecondary
        else -> AppTheme.colors.positive
    }
    val highlights = remember(deck) {
        val redundantCountsRegex = Regex("^\\d+\\s+creatures\\s+and\\s+\\d+\\s+removal$", RegexOption.IGNORE_CASE)
        (deck.identityReasons.take(2) + deck.powerReasons.take(2))
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    !it.endsWith("confidence", ignoreCase = true) &&
                    !redundantCountsRegex.matches(it)
            }
            .distinct()
    }
    val spellsLabel = if (targetSpells != null) "$spellCount/$targetSpells spells" else "$spellCount spells"

    Column(
        Modifier
            .fillMaxWidth()
            .appHeroCard(radius = AppTheme.radii.xl)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (showTitleHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PipRow(deck.colors.toList(), size = 16.dp)
                    Text(
                        deck.title,
                        style = AppTheme.typography.cardTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        color = AppTheme.colors.textPrimary,
                    )
                }
                DeckPowerSummary(deck)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: Archetype Plan Badge + Build Outlook Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppTooltip(
                    title = "Deck Plan · ${deck.type}",
                    tooltip = buildString {
                        append("Plan confidence: ${deck.identityConfidence}.")
                        if (deck.identityReasons.isNotEmpty()) {
                            append("\n\n")
                            append(deck.identityReasons.joinToString("\n") { "• $it" })
                        }
                    },
                    width = 252.dp,
                ) {
                    Box(
                        Modifier
                            .appBadge(
                                AppTheme.colors.accent,
                                fillAlpha = 0.16f,
                                borderAlpha = 0.38f,
                                shape = RoundedCornerShape(AppTheme.radii.xs),
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            deck.type,
                            style = AppTheme.typography.micro.copy(fontWeight = FontWeight.Bold),
                            color = AppTheme.colors.accent,
                        )
                    }
                }

                if (outlookShort.isNotEmpty()) {
                    AppTooltip(
                        title = "Build Outlook",
                        tooltip = buildString {
                            append("${deck.outlook}.")
                            if (deck.powerReasons.isNotEmpty()) {
                                append("\n\n")
                                append(deck.powerReasons.joinToString("\n") { "• $it" })
                            }
                        },
                        width = 252.dp,
                    ) {
                        Box(
                            Modifier
                                .appBadge(
                                    outlookColor,
                                    fillAlpha = 0.12f,
                                    borderAlpha = 0.32f,
                                    shape = RoundedCornerShape(AppTheme.radii.xs),
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                outlookShort,
                                style = AppTheme.typography.micro.copy(fontWeight = FontWeight.SemiBold),
                                color = outlookColor,
                            )
                        }
                    }
                }
            }

            // Right: Composition Stat Pills
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DeckStatPill(spellsLabel)
                DeckStatPill("${deck.creatures} creatures")
                DeckStatPill("${deck.removal} removal")
                if (deck.landLine.isNotBlank()) {
                    AppTooltip(
                        title = "Suggested Mana Base",
                        tooltip = "${deck.landLine}\nBalanced automatically against your spells' colored mana symbol requirements.",
                    ) {
                        DeckStatPill(deck.landLine.substringBefore(" · ").substringBefore(" ("))
                    }
                }
            }
        }

        if (highlights.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (reason in highlights) {
                    ReasonChip(
                        reason = reason,
                        tooltip = "Build insight based on your 23-card curve, creature density, and card quality.",
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckStatPill(text: String) {
    val shape = RoundedCornerShape(AppTheme.radii.xs)
    Box(
        Modifier
            .clip(shape)
            .background(AppTheme.colors.background)
            .border(1.dp, AppTheme.colors.borderHairline, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = AppTheme.typography.monoMicro,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
        )
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
            modifier = Modifier.width(142.dp),
        )
        AppTooltip(
            title = "GIH Win Rate",
            tooltip = "17Lands Games in Hand win rate — how often players win games when this card is drawn or in their opening hand.",
        ) {
            Text(
                "GIH%",
                style = AppTheme.typography.sectionHeader,
                color = AppTheme.colors.textMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
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
        val landColors = if (s.isLand && s.manaCost.isNullOrBlank()) {
            basicLandSymbol(s.name)?.let(::listOf) ?: s.color.toList().filter { it in "WUBRG" }
        } else {
            emptyList()
        }
        if (landColors.isNotEmpty()) {
            Box(
                modifier = Modifier.width(DeckManaCostWidth).padding(end = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PipRow(landColors, size = 15.dp)
            }
        } else {
            ManaCost(
                s.manaCost,
                isLand = s.isLand,
                modifier = Modifier.width(DeckManaCostWidth).padding(end = 6.dp),
            )
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (s.count > 1) {
                Box(
                    Modifier
                        .appBadge(
                            AppTheme.colors.accent,
                            fillAlpha = 0.16f,
                            borderAlpha = 0.38f,
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
            Modifier.width(142.dp),
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
        WinRateMeter(s.gihWr)
    }
}

private fun basicLandSymbol(name: String): Char? = when (name) {
    "Plains" -> 'W'
    "Island" -> 'U'
    "Swamp" -> 'B'
    "Mountain" -> 'R'
    "Forest" -> 'G'
    "Wastes" -> 'C'
    else -> null
}

@Composable
private fun RoleChip(role: String) {
    val color = roleColor(role)
    AppTooltip(
        title = "Tactical Role · $role",
        tooltip = reasonChipTooltip(role),
    ) {
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
}

@Composable
private fun roleColor(role: String): Color = when (role) {
    "Removal" -> AppTheme.colors.danger
    "Fixing" -> AppTheme.colors.positive
    "Finisher" -> AppTheme.colors.warning
    else -> AppTheme.colors.accent
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
    val powerFraction = (opt.power / 100f).coerceIn(0.12f, 1f)
    Column(
        modifier
            .appCard(selected = previewed, active = active, radius = AppTheme.radii.xl)
            .semantics {
                selected = previewed
                stateDescription = buildString {
                    append(if (previewed) "Selected" else "Not selected")
                    if (active) append(", guidance active")
                }
            }
            .focusable()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            when {
                active -> Text(
                    "GUIDANCE ACTIVE",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.accent,
                    maxLines = 1,
                    softWrap = false,
                )
                recommended -> Text(
                    "RECOMMENDED",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.accent,
                    maxLines = 1,
                    softWrap = false,
                )
                else -> Text(
                    "ALTERNATIVE",
                    style = AppTheme.typography.sectionHeader.copy(fontSize = 9.sp),
                    color = AppTheme.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.width(6.dp))
            DeckPowerSummary(opt)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(opt.colors.toList(), size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                opt.title,
                style = AppTheme.typography.cardTitle,
                color = AppTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Raycast Coral-to-Amber Power Progress Bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(AppTheme.colors.background),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(powerFraction)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (previewed || recommended) {
                            Brush.horizontalGradient(
                                listOf(AppTheme.colors.accent, AppTheme.colors.accentSecondary),
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(AppTheme.colors.textMuted, AppTheme.colors.textSecondary),
                            )
                        },
                    ),
            )
        }
    }
}

@Composable
private fun DeckPowerSummary(deck: DeckOptionUi) {
    AppTooltip(
        title = "Projected Build Power",
        tooltip = "Overall deck strength (0–100) and letter tier estimated from your 23 best playables, mana curve balance, and color consistency.",
        width = 248.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            deck.displayTier?.let {
                TierBadge(it)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                deck.powerLabel,
                style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.Bold),
                color = AppTheme.colors.accent,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun TierBadge(tier: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(AppTheme.radii.xs))
            .background(AppTheme.colors.accentSecondary)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            tier,
            fontSize = 10.sp,
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
