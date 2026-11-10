package com.firstpick.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.core.AppVersion
import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.DraftPhase
import com.firstpick.model.DraftState
import com.firstpick.overlay.*
import com.firstpick.stats.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

internal enum class WorkMode { Draft, Deckbuilder, Analytics, Guides, Settings }

@Composable
internal fun Workbench(
    state: DraftUiState, isOverlayOpen: Boolean, overlayHealth: OverlayHealth,
    liveDeckAccess: LiveDeckAccess, onGrantArenaAccess: () -> Unit, onRevokeArenaAccess: () -> Unit,
    onToggleOverlay: () -> Unit, committedDeck: DeckOptionUi?, onUseDeck: (DeckOptionUi) -> Unit,
    onSelectPickCount: (Int) -> Unit, onSimulate: (String) -> Unit, onStopSim: () -> Unit,
    onTogglePause: () -> Unit, onLoadReferenceSet: (String) -> Unit, onRetryRatings: () -> Unit,
    onNewDraft: () -> Unit, stats: PlayerStats, history: List<DraftHistoryEntry>,
    onReviewDraft: suspend (DraftState) -> DraftUiState, onOpenAbout: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (state.phase == DraftPhase.COMPLETE) WorkMode.Deckbuilder else WorkMode.Draft) }
    var draftPool by remember { mutableStateOf(false) }
    var signals by remember { mutableStateOf(false) }
    var analyticsSection by remember { mutableStateOf("Overview") }
    var previewKey by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val workspaceFocus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() else workspaceFocus.requestFocus() }
    val options = state.deckOptions.ifEmpty { listOfNotNull(state.deckSoFar) }
    val key = resolveDeckPreviewKey(options.map { it.deckBuildKey }, previewKey, committedDeck?.deckBuildKey)
    val deck = options.firstOrNull { it.deckBuildKey == key }
    LaunchedEffect(state.phase) {
        if (mode in listOf(WorkMode.Draft, WorkMode.Deckbuilder)) mode = if (state.phase == DraftPhase.COMPLETE) WorkMode.Deckbuilder else WorkMode.Draft
    }
    AppTheme {
        Surface(color = AppColors.background, contentColor = AppColors.textPrimary) {
        Column(Modifier.fillMaxSize().background(AppColors.background).onPreviewKeyEvent { event ->
            when {
                event.type == KeyEventType.KeyDown && event.key == Key.K && (event.isMetaPressed || event.isCtrlPressed) -> { searching = true; true }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape && searching -> { searching = false; query = ""; true }
                else -> false
            }
        }.focusRequester(workspaceFocus).focusable()) {
            BoxWithConstraints(Modifier.fillMaxWidth().background(AppColors.chrome)) {
                val toolbarWidth = maxWidth
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("✧", color = AppColors.warning, fontSize = 23.sp)
                        if (toolbarWidth > 650.dp) Text("FirstPick", style = AppTypography.appTitle, color = AppColors.textPrimary)
                    }
                    AppSegmentedTabs(listOf(WorkMode.Draft, WorkMode.Deckbuilder, WorkMode.Analytics).map {
                        SegmentedTabItem(it.name, selected = mode == it, onClick = { mode = it; query = "" })
                    })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        AppButton(onClick = { searching = !searching }, contentPadding = PaddingValues(7.dp)) { Text("⌕", color = AppColors.textSecondary) }
                        Spacer(Modifier.width(7.dp))
                        AppButton(onClick = onToggleOverlay, active = isOverlayOpen) { Text(if (isOverlayOpen) "Disable overlay" else "Enable overlay", fontSize = 11.sp) }
                    }
                }
            }
            Hairline()
            if (searching) OutlinedTextField(query, { query = it }, label = { Text(if (mode == WorkMode.Analytics) "Find an expansion, event or date" else "Find a card") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(10.dp).focusRequester(searchFocus))
            if (state.dataError != null) Text(state.dataError, color = AppColors.danger, style = AppTypography.caption, modifier = Modifier.padding(8.dp))
            if (isOverlayOpen && liveDeckAccess.status != LiveDeckAccessStatus.CONNECTED) {
                Row(Modifier.fillMaxWidth().background(AppColors.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(liveDeckAccess.detail, style = AppTypography.caption, color = AppColors.textSecondary, modifier = Modifier.weight(1f))
                    if (liveDeckAccess.canGrant) AppButton(onClick = onGrantArenaAccess) { Text("Grant Arena access") }
                    if (liveDeckAccess.savedApproval) AppButton(onClick = onRevokeArenaAccess) { Text("Revoke access") }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val workbenchWidth = maxWidth
                val railWidth = if (maxWidth < 900.dp) 142.dp else 176.dp
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(railWidth).fillMaxHeight().background(AppColors.chrome).padding(horizontal = 10.dp, vertical = 20.dp)) {
                        RailLabel(when (mode) { WorkMode.Draft -> "Draft workspace"; WorkMode.Deckbuilder -> "Workspace"; WorkMode.Analytics -> "Analytics"; else -> "FirstPick" })
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            when (mode) {
                                WorkMode.Draft -> {
                                    RailItem("Current pack", "${state.packCards.size}", !draftPool) { draftPool = false }
                                    RailItem("Drafted pool so far", "${state.poolSize}", draftPool) { draftPool = true }
                                    Spacer(Modifier.height(24.dp)); RailLabel("Current draft")
                                    QuietText(state.setCode ?: "Waiting for Arena")
                                    QuietText(state.format.label)
                                    if (state.pack > 0) QuietText("Pack ${state.pack} · Pick ${state.pick}")
                                }
                                WorkMode.Deckbuilder -> {
                                    RailItem("Suggested deck", deck?.let { "${(it.spells + it.lands).sumOf { s -> s.count }}" }.orEmpty(), true) {}
                                    Spacer(Modifier.height(20.dp)); RailLabel("Suggested builds")
                                    options.forEachIndexed { i, option ->
                                        Column(Modifier.fillMaxWidth().background(if (option == deck) AppColors.surfaceSelected else Color.Transparent, RoundedCornerShape(5.dp))
                                            .clickable { previewKey = option.deckBuildKey; onUseDeck(option) }.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(option.title, style = AppTypography.caption, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                                                Text(if (option.hasObservedPower) "${option.power}" else "—", style = AppTypography.monoSmall, color = AppColors.warning)
                                            }
                                            QuietText("Rank ${i + 1} · ${option.powerLabel}", micro = true)
                                        }
                                        Spacer(Modifier.height(5.dp))
                                    }
                                    if (options.isEmpty()) QuietText("Suggestions appear as your pool develops.")
                                }
                                WorkMode.Analytics -> listOf("Overview", "Match history").forEach { section -> RailItem(section, selected = analyticsSection == section) { analyticsSection = section } }
                                WorkMode.Guides -> QuietText("Set and archetype references")
                                WorkMode.Settings -> QuietText("Application and Arena access")
                            }
                        }
                        RailItem("Draft history") { mode = WorkMode.Analytics; analyticsSection = "Match history" }
                        RailItem("Format guides", selected = mode == WorkMode.Guides) { mode = WorkMode.Guides }
                        RailItem("About") { onOpenAbout() }
                        RailItem("Settings", selected = mode == WorkMode.Settings) { mode = WorkMode.Settings }
                        Spacer(Modifier.height(12.dp)); QuietText("FirstPick ${AppVersion.value}", micro = true)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(AppColors.borderSubtle))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Brush.verticalGradient(listOf(Color(0xFF191C2C), AppColors.background), endY = 280f)).padding(16.dp)) {
                        when (mode) {
                            WorkMode.Draft -> if (state.packCards.isEmpty() && !draftPool) PackPane(state, onSimulate) else Column(Modifier.fillMaxSize()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (draftPool) "Drafted pool so far" else "Compare your next pick", color = AppColors.textPrimary, style = AppTypography.paneTitle, modifier = Modifier.weight(1f))
                                    if (workbenchWidth > 820.dp) QuietText("${state.setCode.orEmpty()} · P${state.pack} · Pick ${state.pick}")
                                    AppButton(onClick = { signals = !signals }) { Text(if (signals) "Hide signals" else "Show signals", fontSize = 10.sp) }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.weight(1f)) {
                                    Box(Modifier.weight(1f)) { if (draftPool) ReadOnlyDeckList(state.draftPool, query) else RankedPackList(state, query) }
                                    if (signals) { Spacer(Modifier.width(12.dp)); Sidebar(state, Modifier.width(if (workbenchWidth < 950.dp) 190.dp else 240.dp).fillMaxHeight()) }
                                }
                                Hairline(); DraftPoolDock(state)
                            }
                            WorkMode.Deckbuilder -> if (deck == null) Centered { QuietText("Suggested builds will appear as you draft.") } else SuggestedDeckWorkspace(deck, options.indexOf(deck) + 1, state.draftPool, query)
                            WorkMode.Analytics -> WorkbenchAnalytics(stats, history, analyticsSection, onReviewDraft, query)
                            WorkMode.Guides -> GuidePane(state, onLoadReferenceSet)
                            WorkMode.Settings -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Settings", style = AppTypography.paneTitle, color = AppColors.textPrimary)
                                QuietText("Pick selection mode")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DraftPickMode.choices.forEach { n -> AppButton(onClick = { onSelectPickCount(n) }, active = state.pickCountOverride == n) { Text(DraftPickMode.label(n)) } } }
                                QuietText(liveDeckAccess.detail)
                                if (liveDeckAccess.canGrant) AppButton(onClick = onGrantArenaAccess) { Text("Grant Arena access") }
                                if (liveDeckAccess.savedApproval) AppButton(onClick = onRevokeArenaAccess) { Text("Revoke Arena access") }
                                if (state.simulating) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppButton(onClick = onTogglePause) { Text(if (state.simPaused) "Resume demo" else "Pause demo") }
                                    AppButton(onClick = onStopSim) { Text("Exit demo") }
                                }
                                AppButton(onClick = onNewDraft) { Text("Start a new draft session") }
                            }
                        }
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().background(AppColors.chrome).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                QuietText(if (state.simulating) "Demo session" else state.headline, micro = true)
                if (state.canRetryRatings) AppButton(onClick = onRetryRatings) { Text("Retry data", fontSize = 10.sp) }
                else AppTooltip(title = "Data and overlay status", tooltip = ratingsDataDescription(state) + "\n" + state.ratingsDataWarning.orEmpty()) { QuietText(if (isOverlayOpen) "Overlay · ${overlayHealth.state.name.lowercase()}" else "Overlay disabled", micro = true) }
            }
        }
    }
    }
}

@Composable internal fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.borderSubtle))
@Composable internal fun QuietText(text: String, micro: Boolean = false) = Text(text, color = AppColors.textSecondary, style = if (micro) AppTypography.micro else AppTypography.caption)
@Composable private fun RailLabel(text: String) { Text(text.uppercase(), style = AppTypography.sectionHeader, color = AppColors.textMuted); Spacer(Modifier.height(12.dp)) }
@Composable private fun RailItem(label: String, trailing: String = "", selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) AppColors.surfaceSelected else Color.Transparent, RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.caption, color = if (selected) AppColors.textPrimary else AppColors.textSecondary, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) Text(trailing, style = AppTypography.monoMicro, color = AppColors.textMuted)
    }
}
@Composable internal fun HelpLabel(label: String, explanation: String, modifier: Modifier = Modifier) {
    Box(modifier) { AppTooltip(title = label, tooltip = explanation) { Text(label, color = AppColors.textSecondary, style = AppTypography.micro) } }
}

@Composable private fun RankedPackList(state: DraftUiState, query: String) {
    val highlights = packHighlights(state.packCards, state.picksPerChoice)
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            QuietText("Card & fit")
            Spacer(Modifier.weight(1f))
            HelpLabel("Fit", "FirstPick’s contextual advisor score: card quality, colors, curve and synergy. Higher is better. It is not a win percentage. Missing evidence is shown as —.", Modifier.width(67.dp))
            HelpLabel("GIH WR", "17Lands games in hand win rate: games won when the card was in the opening hand or drawn. This is a reference metric, not a prediction for your deck.", Modifier.width(58.dp))
        }
        Hairline()
        LazyColumn {
            items(state.packCards.filter { it.name.contains(query, true) }, key = { "${it.grpId}-${it.rank}" }) { card ->
                val highlight = highlights.getOrNull(state.packCards.indexOf(card)) ?: PickHighlight.NONE
                val ink = if (highlight == PickHighlight.CLEAR) AppColors.warning else AppColors.accent
                CardPreview(card.imageUrl, description = card.name, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(if (highlight != PickHighlight.NONE) ink.copy(alpha = .07f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(card.rank), style = AppTypography.monoMicro, color = AppColors.textMuted, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(card.name, color = if (highlight == PickHighlight.CLEAR) AppColors.warning else AppColors.textPrimary, style = AppTypography.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (card.manaCost != null) ManaCost(card.manaCost, modifier = Modifier.widthIn(max = 90.dp)) else PipRow(card.color.filter { it in "WUBRG" }.toList(), 13.dp)
                            }
                            val lead = when { card.recommendedPair -> "Suggested pair · "; highlight == PickHighlight.CLEAR -> "Recommended · "; highlight == PickHighlight.CONTENDER -> "Close contender · "; else -> "" }
                            Text(lead + card.reasons.take(2).joinToString(" · "), style = AppTypography.micro, color = if (highlight != PickHighlight.NONE) ink else AppColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        Row(Modifier.width(67.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(card.value?.roundToInt()?.toString() ?: "—", style = AppTypography.monoSmall, color = ink)
                            if (card.value != null) Bar((card.value / 100).toFloat(), ink, Modifier.width(22.dp).height(3.dp))
                        }
                        Text(card.gihWr?.let { "${it.asPct()}%" } ?: "—", style = AppTypography.monoSmall, color = AppColors.textSecondary, modifier = Modifier.width(58.dp))
                    }
                }
                Hairline()
            }
        }
    }
}

@Composable private fun DraftPoolDock(state: DraftUiState) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { QuietText("Drafted pool so far", true); Text("${state.poolSize} cards", color = AppColors.textPrimary, style = AppTypography.body); QuietText("${state.poolCreatures} creatures · ${state.poolNonCreatures} other", true) }
        MiniCurve(state.manaCurve)
        Column { QuietText("Current direction", true); PipRow(state.laneColors, 16.dp) }
    }
}

@Composable internal fun MiniCurve(curve: List<CurveBar>) {
    val max = curve.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        curve.forEach { bar -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(13.dp).height((32f * bar.count / max).dp.coerceAtLeast(1.dp)).background(AppColors.accent.copy(alpha = .6f)))
            Text(bar.cmcLabel, style = AppTypography.micro, color = AppColors.textMuted)
        } }
    }
}

@Composable internal fun SuggestedDeckWorkspace(deck: DeckOptionUi, rank: Int, pool: List<DeckSpellUi>, query: String = "") {
    var horizontal by remember { mutableStateOf(false) }
    var cutsOpen by remember { mutableStateOf(true) }
    val cuts = remember(pool, deck) { closeCuts(pool, deck) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showInspector = maxWidth >= 650.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(deck.title, style = AppTypography.paneTitle.copy(fontSize = 21.sp), color = AppColors.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PipRow(deck.colors.filter { it in "WUBRG" }.toList(), 14.dp)
                            AppTooltip(title = "Build strength", tooltip = "Rank $rank among suggested builds. Power is the advisor’s deck-strength score, not a win percentage. ${deck.powerReasons.joinToString(" · ")}") { Text("Rank $rank · ${deck.powerLabel}", style = AppTypography.monoSmall, color = AppColors.warning) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    QuietText("${deck.spells.sumOf { it.count }} spells + ${deck.lands.sumOf { it.count }} lands")
                    AppSegmentedTabs(listOf(SegmentedTabItem("Vertical", selected = !horizontal, onClick = { horizontal = false }), SegmentedTabItem("Horizontal", selected = horizontal, onClick = { horizontal = true })))
                }
                Box(Modifier.weight(1f)) { ReadOnlyDeckList(deck.spells + deck.lands, query, horizontal) }
                if (cuts.isNotEmpty()) {
                    Hairline()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        HelpLabel("Close cuts · ${cuts.sumOf { it.count }} copies", "Cards from your pool that narrowly missed this build, ordered by available GIH WR. These are alternatives to consider in Arena, not a sideboard.")
                        AppButton(onClick = { cutsOpen = !cutsOpen }) { Text(if (cutsOpen) "Collapse" else "Expand", fontSize = 10.sp) }
                    }
                    if (cutsOpen) Box(Modifier.heightIn(max = 112.dp)) { ReadOnlyDeckList(cuts, query) }
                }
                if (!showInspector) { Hairline(); DeckOverview(deck, compact = true) }
            }
            if (showInspector) Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).background(AppColors.chrome).padding(14.dp)) { DeckOverview(deck) }
        }
    }
}

@Composable internal fun ReadOnlyDeckList(cards: List<DeckSpellUi>, query: String = "", horizontal: Boolean = false) {
    val rows = cards.filter { it.name.contains(query, true) }.sortedWith(compareBy<DeckSpellUi> { it.isLand }.then(deckSpellOrder))
    if (horizontal) {
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (0..7).forEach { mv ->
                val group = rows.filter { if (mv == 7) it.isLand else !it.isLand && it.cmc.coerceIn(0, 6) == mv }
                Column(Modifier.width(175.dp).fillMaxHeight()) {
                    QuietText(if (mv == 7) "Lands" else if (mv == 6) "6+" else "$mv"); Spacer(Modifier.height(8.dp)); Hairline()
                    LazyColumn { items(group) { ReadOnlyCardRow(it, compact = true) } }
                }
            }
        }
    } else Column {
        Row(Modifier.fillMaxWidth().padding(7.dp)) {
            QuietText("#"); Spacer(Modifier.width(20.dp)); QuietText("Card"); Spacer(Modifier.weight(1f)); HelpLabel("GIH WR", "17Lands games in hand win rate. — means unavailable.")
        }
        Hairline()
        LazyColumn { items(rows) { ReadOnlyCardRow(it) } }
    }
}

@Composable private fun ReadOnlyCardRow(card: DeckSpellUi, compact: Boolean = false) {
    CardPreview(card.imageUrl, description = card.name, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${card.count}", style = AppTypography.monoMicro, color = AppColors.textMuted, modifier = Modifier.width(25.dp))
            Text(card.name, style = AppTypography.caption, color = AppColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                if (card.isLand) PipRow(card.color.filter { it in "WUBRG" }.toList(), 14.dp) else ManaCost(card.manaCost, modifier = Modifier.widthIn(max = 85.dp))
                Spacer(Modifier.width(10.dp)); Text(card.gihWr?.let { "${it.asPct()}%" } ?: "—", style = AppTypography.monoMicro, color = AppColors.textSecondary, modifier = Modifier.width(48.dp))
            }
        }
    }
    Hairline()
}

@Composable private fun DeckOverview(deck: DeckOptionUi, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Deck overview", style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold), color = AppColors.textPrimary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Spells" to deck.spells.sumOf { it.count }, "Creatures" to deck.creatures, "Removal" to deck.removal, "Lands" to deck.lands.sumOf { it.count }).forEach { (label, count) -> Column { Text("$count", style = AppTypography.monoValue.copy(fontSize = 20.sp), color = AppColors.textPrimary); QuietText(label, true) } }
        }
        MiniCurve((1..6).map { cmc -> CurveBar(if (cmc == 6) "6+" else "$cmc", deck.spells.filter { it.cmc.coerceIn(1, 6) == cmc }.sumOf { it.count }) })
        if (!compact) {
            Hairline(); QuietText(deck.outlook)
            var details by remember { mutableStateOf(false) }
            AppButton(onClick = { details = !details }) { Text(if (details) "Hide build analysis" else "View build analysis", fontSize = 10.sp) }
            if (details) (deck.identityReasons + deck.powerReasons).distinct().forEach { QuietText(it) }
            Hairline(); Text("Suggested manabase", style = AppTypography.body, color = AppColors.textPrimary)
            deck.lands.forEach { land -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PipRow(land.color.filter { it in "WUBRG" }.toList(), 14.dp); Spacer(Modifier.width(5.dp)); Text(land.name, style = AppTypography.caption, color = AppColors.textSecondary, modifier = Modifier.weight(1f)); QuietText("${land.count}")
            } }
        }
    }
}
