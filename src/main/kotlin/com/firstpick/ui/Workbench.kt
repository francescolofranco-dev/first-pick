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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Workbench(
    state: DraftUiState, isOverlayOpen: Boolean, overlayHealth: OverlayHealth,
    liveDeckAccess: LiveDeckAccess, onGrantArenaAccess: () -> Unit, onRevokeArenaAccess: () -> Unit,
    onToggleOverlay: () -> Unit, committedDeck: DeckOptionUi?, onUseDeck: (DeckOptionUi) -> Unit,
    onSelectPickCount: (Int) -> Unit, onSimulate: (String) -> Unit, onStopSim: () -> Unit,
    onTogglePause: () -> Unit, onLoadReferenceSet: (String) -> Unit, onRetryRatings: () -> Unit,
    onNewDraft: () -> Unit, stats: PlayerStats, history: List<DraftHistoryEntry>,
    onReviewDraft: suspend (DraftState) -> DraftUiState,
) {
    var mode by remember { mutableStateOf(if (state.phase == DraftPhase.COMPLETE) WorkMode.Deckbuilder else WorkMode.Draft) }
    var draftPool by remember { mutableStateOf(false) }
    var cutsExpanded by remember { mutableStateOf(true) }
    var signals by remember { mutableStateOf(false) }
    var analyticsScope by remember { mutableStateOf(stats.courses.count{it.complete} to stats.matches.size) }
    var archiveContext by remember { mutableStateOf<List<String>?>(null) }
    var analyticsSection by remember { mutableStateOf("Overview") }
    var previewKey by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val workspaceFocus = remember { FocusRequester() }
    val options = state.deckOptions.ifEmpty { listOfNotNull(state.deckSoFar) }
    val key = resolveDeckPreviewKey(options.map { it.deckBuildKey }, previewKey, committedDeck?.deckBuildKey)
    val deck = options.firstOrNull { it.deckBuildKey == key }
    LaunchedEffect(state.phase) {
        if(state.phase==DraftPhase.IDLE)draftPool=false
        if (mode in listOf(WorkMode.Draft, WorkMode.Deckbuilder)) mode = if (state.phase == DraftPhase.COMPLETE) WorkMode.Deckbuilder else WorkMode.Draft
    }
    AppTheme {
        Surface(color = AppColors.background, contentColor = AppColors.textPrimary) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = WorkbenchLayout(maxWidth)
        CompositionLocalProvider(LocalWorkbenchLayout provides layout) {
        Column(Modifier.fillMaxSize().background(AppColors.background).onPreviewKeyEvent { event ->
            when {
                event.type == KeyEventType.KeyDown && event.key == Key.K && (event.isMetaPressed || event.isCtrlPressed) -> { searching = true; true }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape && searching -> { searching = false; query = ""; true }
                else -> false
            }
        }.focusRequester(workspaceFocus).focusable()) {
            // Request after the workspace subcomposition has attached its focus targets.
            LaunchedEffect(searching) { withFrameNanos { }; if(searching)searchFocus.requestFocus()else workspaceFocus.requestFocus() }
            BoxWithConstraints(Modifier.fillMaxWidth().height(53.dp).testTag("workbench-toolbar").background(Brush.horizontalGradient(listOf(Color(0xFF101423),Color(0xFF0D111B),Color(0xFF131520))))) {
                val toolbarWidth = maxWidth
                Row(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = if (layout.small) 12.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        WorkbenchBrand(Modifier.size(if(layout.small)26.dp else 30.dp))
                        Text("FirstPick", style = AppTypography.appTitle.copy(fontSize=if(layout.small)12.sp else 14.sp,fontWeight=FontWeight.SemiBold), color = AppColors.textPrimary)
                    }
                    WorkbenchTabs(listOf(WorkMode.Draft, WorkMode.Deckbuilder, WorkMode.Analytics).map {
                        SegmentedTabItem(it.name, selected = mode == it, onClick = { mode = it; query = "" })
                    })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        WorkbenchButton(
                            onClick = { searching = !searching },
                            modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).semantics { contentDescription = "Search" },
                            outlined = false, contentPadding = PaddingValues(6.dp),
                        ) {
                            Canvas(Modifier.size(20.dp)) {
                                val stroke = 1.8.dp.toPx()
                                drawCircle(AppColors.textSecondary, radius = size.width * .28f,
                                    center = Offset(size.width * .42f, size.height * .42f), style = Stroke(stroke))
                                drawLine(AppColors.textSecondary, Offset(size.width * .62f, size.height * .62f),
                                    Offset(size.width * .85f, size.height * .85f), strokeWidth = stroke, cap = StrokeCap.Round)
                            }
                            if (!layout.small) {
                                Spacer(Modifier.width(6.dp))
                                Text("Search   ⌘ K", color = AppColors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(7.dp))
                        WorkbenchButton(onClick = onToggleOverlay, active = isOverlayOpen) { Text(if (isOverlayOpen) "Disable overlay" else "Enable overlay", fontSize = if(layout.small)10.sp else 12.sp) }
                    }
                }
            }
            Hairline()
            if (searching) OutlinedTextField(query, { query = it }, label = { Text(if (mode == WorkMode.Analytics) "Find an expansion, event or date" else "Find a card") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(10.dp).focusRequester(searchFocus))
            if (state.dataError != null) Text(state.dataError, color = AppColors.danger, style = AppTypography.caption, modifier = Modifier.padding(8.dp))
            if (isOverlayOpen && liveDeckAccess.status != LiveDeckAccessStatus.CONNECTED) {
                Row(Modifier.fillMaxWidth().background(AppColors.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(liveDeckAccess.detail, style = AppTypography.caption, color = AppColors.textSecondary, modifier = Modifier.weight(1f))
                    if (liveDeckAccess.canGrant) WorkbenchButton(onClick = onGrantArenaAccess) { Text("Grant Arena access") }
                    if (liveDeckAccess.savedApproval) WorkbenchButton(onClick = onRevokeArenaAccess) { Text("Revoke access") }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val workbenchWidth = maxWidth
                val railWidth = layout.rail
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(railWidth).fillMaxHeight().testTag("workbench-rail").background(AppColors.chrome).drawBehind { drawLine(AppColors.borderDefault, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }.padding(horizontal = 14.dp, vertical = if(layout.small)20.dp else 26.dp)) {
                        RailLabel(when (mode) { WorkMode.Draft -> "Draft workspace"; WorkMode.Deckbuilder -> "Workspace"; WorkMode.Analytics -> "Analytics workspace"; else -> "FirstPick" })
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            when (mode) {
                                WorkMode.Draft -> {
                                    if(state.phase==DraftPhase.IDLE && state.packCards.isEmpty()) {
                                        QuietText("No active draft")
                                        Spacer(Modifier.height(10.dp))
                                        QuietText("Your pack and pool will appear when you start drafting.")
                                    } else {
                                    RailItem("Current pack", "${state.packCards.size}", !draftPool) { draftPool = false }
                                    RailItem("Drafted pool so far", "${state.poolSize}", draftPool) { draftPool = true }
                                    Spacer(Modifier.height(24.dp)); RailLabel("Current draft")
                                    QuietText(state.setReferences.firstOrNull{it.code==state.setCode}?.name ?: state.setCode ?: "Waiting for Arena")
                                    QuietText(state.format.label)
                                    if (state.pack > 0) QuietText("Pack ${state.pack} · Pick ${state.pick}")
                                    }
                                }
                                WorkMode.Deckbuilder -> {
                                    RailItem("Suggested deck", deck?.let { "${(it.spells + it.lands).sumOf { s -> s.count }}" }.orEmpty(), true) {}
                                    RailItem("Close cuts",deck?.let{closeCuts(state.draftPool,it).size.toString()}.orEmpty()){cutsExpanded=true}
                                    Spacer(Modifier.height(30.dp)); RailLabel("Suggested builds")
                                    options.forEachIndexed { i, option ->
                                        Column(Modifier.fillMaxWidth().drawBehind {if(option==deck)drawLine(AppColors.warning,androidx.compose.ui.geometry.Offset.Zero,androidx.compose.ui.geometry.Offset(0f,size.height),1.dp.toPx())}.background(if(option==deck)AppColors.accent.copy(alpha=.025f)else Color.Transparent)
                                            .clickable{previewKey=option.deckBuildKey;onUseDeck(option)}.padding(horizontal=9.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                                Text(option.title.replace(" · "," "),fontSize=if(layout.small)10.sp else 11.sp,lineHeight=16.sp,color=if(option==deck)Color(0xFFF0DFB8)else AppColors.textPrimary,modifier=Modifier.weight(1f))
                                                Column(Modifier.width(27.dp),horizontalAlignment=Alignment.End){Text("Power",fontSize=8.sp,maxLines=1,color=AppColors.textSecondary);Text(if(option.hasObservedPower)"${option.power}"else"—",style=AppTypography.monoMicro,color=if(option==deck)AppColors.warning else AppColors.textSecondary)}
                                            }
                                            QuietText("Rank ${i+1} · ${if(option==deck)"Selected"else"View suggestion"}",micro=true)
                                        }
                                        Spacer(Modifier.height(5.dp))
                                    }
                                    if (options.isEmpty()) QuietText("Suggestions appear as your pool develops.")
                                }
                                WorkMode.Analytics -> {
                                    listOf("Overview","Match history").forEach{section->RailItem(section,selected=analyticsSection==section){analyticsSection=section}}
                                    Spacer(Modifier.height(30.dp));RailLabel(if(archiveContext!=null)"Reviewing draft"else"Report scope")
                                    if(archiveContext!=null)archiveContext!!.forEach{QuietText(it)}
                                    else {QuietText("${analyticsScope.first} completed drafts");Spacer(Modifier.height(5.dp));QuietText("${analyticsScope.second} matches")}
                                }
                                WorkMode.Guides -> QuietText("Set and archetype references")
                                WorkMode.Settings -> QuietText("Application and Arena access")
                            }
                        }
                        RailItem("Format guides", selected = mode == WorkMode.Guides, icon = { FormatGuidesIcon() }) { mode = WorkMode.Guides }
                        RailItem("⚙  Settings", selected = mode == WorkMode.Settings) { mode = WorkMode.Settings }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("workbench-workspace").background(Brush.verticalGradient(listOf(Color(0xFF171A2A), AppColors.background), endY = 260f)).padding(start=layout.gutter,end=if(mode==WorkMode.Deckbuilder||mode==WorkMode.Analytics)0.dp else layout.gutter,top=if(mode==WorkMode.Draft && state.packCards.isEmpty() && !draftPool)0.dp else if(mode==WorkMode.Draft)16.dp else if(mode==WorkMode.Deckbuilder||mode==WorkMode.Analytics)0.dp else layout.top)) {
                        when (mode) {
                            WorkMode.Draft -> if (state.packCards.isEmpty() && !draftPool) PackPane(state, onSimulate) else Column(Modifier.fillMaxSize().padding(bottom=20.dp)) {
                                Row(Modifier.heightIn(min=32.dp).padding(bottom=9.dp),verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FlowRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            androidx.compose.foundation.Canvas(Modifier.size(5.dp)){drawPath(androidx.compose.ui.graphics.Path().apply{moveTo(size.width/2,0f);lineTo(size.width,size.height/2);lineTo(size.width/2,size.height);lineTo(0f,size.height/2);close()},AppColors.warning)}
                                            Text(if(draftPool)"Drafted pool so far" else "Compare your next pick",color=AppColors.textPrimary,style=AppTypography.paneTitle.copy(fontSize=if(layout.small)16.sp else 18.sp,fontWeight=FontWeight.Medium))
                                        }
                                        Text("${state.setReferences.firstOrNull{it.code==state.setCode}?.name?:state.setCode.orEmpty()}  ·  Pack ${state.pack}  ·  Pick ${state.pick}",fontSize=if(layout.small)10.sp else 11.sp,color=Color(0xFF9C9BB5))
                                    }
                                    WorkbenchButton(onClick = { signals = !signals }) { Text(if (signals) "Hide signals" else "Show signals", fontSize = 10.sp) }
                                }
                                Hairline();Spacer(Modifier.height(8.dp))
                                Row(Modifier.weight(1f)) {
                                    Box(Modifier.weight(1f)) { if (draftPool) ReadOnlyDeckList(state.draftPool, query) else RankedPackList(state, query) }
                                    if (signals) { Spacer(Modifier.width(16.dp)); WorkbenchInspector("Draft signals","Read the pack and your pool together.") { InspectorSection("Color direction") { PipRow(state.laneColors,16.dp);state.openLanes.take(3).forEach { QuietText("${manaColorName(it.color)} · ${it.score.roundToInt()}") } };InspectorSection("Pool needs") { state.deckNeeds.forEach { QuietText(it) };if(state.deckNeeds.isEmpty()) QuietText("No specific needs identified yet.") } } }
                                }
                                Hairline(); DraftPoolDock(state)
                            }
                            WorkMode.Deckbuilder -> if (deck == null) Centered { QuietText("Suggested builds will appear as you draft.") } else SuggestedDeckWorkspace(deck, options.indexOf(deck) + 1, state.draftPool, query, listOfNotNull(state.setReferences.firstOrNull { it.code==state.setCode }?.name ?: state.setCode,state.format.label).joinToString(" · "),cutsExpanded,{cutsExpanded=it})
                            WorkMode.Analytics -> WorkbenchAnalytics(stats, history, analyticsSection, onReviewDraft, query, state.setReferences.associate { it.code to it.name },onScope={drafts,matches->analyticsScope=drafts to matches},onArchiveContext={archiveContext=it})
                            WorkMode.Guides -> GuidePane(state, onLoadReferenceSet)
                            WorkMode.Settings -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Settings", style = AppTypography.paneTitle, color = AppColors.textPrimary)
                                QuietText("Pick selection mode")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DraftPickMode.choices.forEach { n -> WorkbenchButton(onClick = { onSelectPickCount(n) }, active = state.pickCountOverride == n) { Text(DraftPickMode.label(n)) } } }
                                QuietText(liveDeckAccess.detail)
                                if (liveDeckAccess.canGrant) WorkbenchButton(onClick = onGrantArenaAccess) { Text("Grant Arena access") }
                                if (liveDeckAccess.savedApproval) WorkbenchButton(onClick = onRevokeArenaAccess) { Text("Revoke Arena access") }
                                if (state.simulating) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WorkbenchButton(onClick = onTogglePause) { Text(if (state.simPaused) "Resume demo" else "Pause demo") }
                                    WorkbenchButton(onClick = onStopSim) { Text("Exit demo") }
                                }
                                WorkbenchButton(onClick = onNewDraft) { Text("Start a new draft session") }
                            }
                        }
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).background(Color(0xFF0B0E17)).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.simulating) "Demo session" else state.headline, color = AppColors.textSecondary, style = AppTypography.micro.copy(fontSize = 9.sp, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (state.canRetryRatings) WorkbenchButton(onClick = onRetryRatings) { Text("Retry data", fontSize = 10.sp) }
                else AppTooltip(title = "Data and overlay status", tooltip = ratingsDataDescription(state) + "\n" + state.ratingsDataWarning.orEmpty()) { QuietText(if (isOverlayOpen) "Overlay · ${overlayHealth.state.name.lowercase()}" else "Overlay disabled", micro = true) }
            }
        }
    }
    }
}
    }
    }

@Composable internal fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12B2B0DB)))
@Composable internal fun QuietText(text: String, micro: Boolean = false) = Text(text, color = AppColors.textSecondary, style = if (micro) AppTypography.micro.copy(fontSize=9.sp,fontWeight=FontWeight.Normal) else AppTypography.caption)
@Composable private fun RailLabel(text: String) { Text(text.uppercase(), style = AppTypography.sectionHeader, color = AppColors.textMuted); Spacer(Modifier.height(12.dp)) }
@Composable private fun RailItem(label: String, trailing: String = "", selected: Boolean = false, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) AppColors.accent.copy(alpha=.10f) else Color.Transparent, RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(horizontal=6.dp,vertical=10.dp), verticalAlignment = Alignment.CenterVertically) {
        if(icon!=null){icon();Spacer(Modifier.width(6.dp))}
        Text(label, style = AppTypography.caption.copy(fontSize=if (LocalWorkbenchLayout.current.small) 11.sp else 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) AppColors.textPrimary else AppColors.textSecondary, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) Text(trailing, style = AppTypography.monoMicro, color = AppColors.textMuted)
    }
}
@Composable internal fun HelpLabel(label: String, explanation: String, modifier: Modifier = Modifier) {
    Box(modifier) { AppTooltip(title = label, tooltip = explanation) { Text(label, color = AppColors.textSecondary, style = AppTypography.micro) } }
}

@Composable private fun RankedPackList(state: DraftUiState, query: String) {
    val highlights = packHighlights(state.packCards, state.picksPerChoice)
    val small = LocalWorkbenchLayout.current.small
    val fitWidth = if(small)82.dp else 108.dp
    val wrWidth = if(small)57.dp else 85.dp
    Column {
        Row(Modifier.fillMaxWidth().height(29.dp).padding(horizontal=if(small)6.dp else 10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Card & fit",fontSize=10.sp,color=AppColors.textMuted,modifier=Modifier.weight(1f))
            HelpLabel("Fit", "FirstPick’s contextual advisor score: card quality, colors, curve and synergy. Higher is better. It is not a win percentage. Missing evidence is shown as —.", Modifier.width(fitWidth))
            HelpLabel("GIH WR", "17Lands games in hand win rate: games won when the card was in the opening hand or drawn. This is a reference metric, not a prediction for your deck.", Modifier.width(wrWidth))
        };Hairline()
        LazyColumn {
            items(state.packCards.filter { it.name.contains(query, true) }, key = { "${it.grpId}-${it.rank}" }) { card ->
                val highlight = highlights.getOrNull(state.packCards.indexOf(card)) ?: PickHighlight.NONE
                val ink = when(highlight){PickHighlight.CLEAR->AppColors.warning;PickHighlight.CONTENDER->AppColors.accent;else->Color(0xFF9596B1)}
                CardPreview(card.imageUrl,description=card.name,modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(if(small)44.dp else 47.dp)
                        .background(if(highlight==PickHighlight.CLEAR)Brush.horizontalGradient(listOf(Color(0x1BD2AD70),Color(0x0C8F73AF),Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent,Color.Transparent)))
                        .then(if(highlight==PickHighlight.CLEAR)Modifier.drawBehind{drawLine(AppColors.warning,androidx.compose.ui.geometry.Offset.Zero,androidx.compose.ui.geometry.Offset(0f,size.height),2.dp.toPx())}else Modifier)
                        .padding(horizontal=if(small)6.dp else 10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("%02d".format(card.rank),fontSize=8.sp,color=AppColors.textMuted,modifier=Modifier.width(if(small)12.dp else 16.dp))
                                Text(card.name,color=if(highlight==PickHighlight.CLEAR)Color(0xFFF0DFB8)else if(highlight==PickHighlight.CONTENDER)Color(0xFFDCE0EE)else Color(0xFFBDC3D6),fontSize=if(small)11.sp else 12.sp,fontWeight=if(highlight==PickHighlight.CLEAR)FontWeight.SemiBold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                                if(card.manaCost!=null)ManaCost(card.manaCost,symbolSize=if(small)11.dp else 13.dp)else PipRow(card.color.filter{it in "WUBRG"}.toList(),11.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            val lead=when{card.recommendedPair->"Suggested pair · ";highlight==PickHighlight.CLEAR->"Recommended · ";highlight==PickHighlight.CONTENDER->"Close contender · ";else->""}
                            Text(lead+card.reasons.take(2).joinToString(" · "),fontSize=if(small)9.sp else 10.sp,lineHeight=13.sp,color=if(highlight==PickHighlight.CLEAR)AppColors.warning else if(highlight==PickHighlight.CONTENDER)Color(0xFFBDB2DA)else Color(0xFF858BA3),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=if(small)18.dp else 22.dp))
                        }
                        Row(Modifier.width(fitWidth),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(small)6.dp else 9.dp)) {
                            Text(card.value?.roundToInt()?.toString()?:"—",style=AppTypography.monoSmall.copy(fontSize=if(highlight==PickHighlight.CLEAR)17.sp else if(small)16.sp else 13.sp),color=ink,modifier=Modifier.width(25.dp))
                            if(card.value!=null)Bar((card.value/100).toFloat(),if(highlight==PickHighlight.CLEAR)AppColors.warning else if(highlight==PickHighlight.CONTENDER)Color(0xFFA08ABD)else Color(0xFF625C83),Modifier.width(if(small)30.dp else 44.dp).height(3.dp))
                        }
                        Text(card.gihWr?.let{"${it.asPct()}%"}?:"—",style=AppTypography.monoSmall,color=AppColors.textSecondary,modifier=Modifier.width(wrWidth),textAlign=TextAlign.End)
                    }
                };Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DC7BCE9)))
            }
        }
    }
}

@Composable private fun DraftPoolDock(state: DraftUiState) {
    val small=LocalWorkbenchLayout.current.small
    Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=if(small)0.dp else 4.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { QuietText("Drafted pool so far",true);Row(verticalAlignment=Alignment.CenterVertically){Text("${state.poolSize} cards",fontSize=14.sp,color=AppColors.textPrimary);Spacer(Modifier.width(6.dp));Text("${state.poolCreatures} creatures · ${state.poolNonCreatures} spells",fontSize=9.sp,color=AppColors.textMuted)} }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){QuietText("Mana curve",true);if(small)QuietText(state.manaCurve.joinToString(" · "){it.cmcLabel},true)else MiniCurve(state.manaCurve)}
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){QuietText("Current direction",true);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){PipRow(state.laneColors,16.dp);Text(state.laneColors.joinToString("–"){manaColorName(it).removeSuffix(" mana").lowercase()}.replaceFirstChar{it.uppercase()},fontSize=10.sp,color=AppColors.textSecondary)}}
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


@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SuggestedDeckWorkspace(deck:DeckOptionUi,rank:Int,pool:List<DeckSpellUi>,query:String="",context:String="",cutsOpen:Boolean=true,onCutsOpen:(Boolean)->Unit={}) {
    var horizontal by remember{mutableStateOf(false)}
    val cuts=remember(pool,deck){closeCuts(pool,deck)}
    val layout=LocalWorkbenchLayout.current
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(end=layout.gutter,top=layout.top)) {
            Text("SUGGESTED DECK",style=AppTypography.sectionHeader,color=Color(0xFF9599B8));Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                    WorkbenchHeading(deck.title.replace(" · "," "))
                    AppTooltip(title="Build strength",tooltip="Rank $rank among suggested builds. Power is the advisor’s deck-strength score, not a win percentage. ${deck.powerReasons.joinToString(" · ")}"){Text("Rank $rank · ${deck.powerLabel}",style=AppTypography.monoSmall.copy(fontSize=if(layout.small)11.sp else 12.sp),color=AppColors.accent)}
                }
                Spacer(Modifier.width(9.dp));QuietText("${(deck.spells+deck.lands).sumOf{it.count}} / 40 cards",true)
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically){PipRow(deck.colors.filter{it in "WUBRG"}.toList(),16.dp);QuietText(context)}
            Spacer(Modifier.height(26.dp))
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically){QuietText("${deck.spells.sumOf{it.count}} spells + ${deck.lands.sumOf{it.count}} lands");WorkbenchTabs(listOf(SegmentedTabItem("▤ Vertical",selected=!horizontal,onClick={horizontal=false}),SegmentedTabItem("▥ Horizontal",selected=horizontal,onClick={horizontal=true})),compact=true)}
            Spacer(Modifier.height(19.dp));Box(Modifier.weight(1f)){ReadOnlyDeckList(deck.spells+deck.lands,query,horizontal)}
            if(cuts.isNotEmpty()) {
                Hairline();Spacer(Modifier.height(13.dp))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Close cuts",color=AppColors.textPrimary,style=AppTypography.caption);Spacer(Modifier.width(10.dp));Text("Cards that just missed the suggested deck",style=AppTypography.micro.copy(fontSize=9.sp,fontWeight=FontWeight.Normal),color=AppColors.textMuted,modifier=Modifier.weight(1f),maxLines=2)
                    WorkbenchButton(onClick={onCutsOpen(!cutsOpen)},outlined=false,contentPadding=PaddingValues(0.dp)){Text(if(cutsOpen)"Collapse −" else "Expand +",fontSize=10.sp)}
                }
                if(cutsOpen){Spacer(Modifier.height(9.dp));Text("Near-miss alternatives to consider when choosing your final deck in Arena.",style=AppTypography.caption.copy(lineHeight=18.sp),color=AppColors.textSecondary);Spacer(Modifier.height(9.dp))
                    if(layout.small)LazyColumn(Modifier.heightIn(max=96.dp)){items(cuts){CloseCutRow(it)}}
                    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){cuts.forEach{CloseCutRow(it,Modifier.width(200.dp))}}
                }
                Spacer(Modifier.height(19.dp))
            }else Spacer(Modifier.height(20.dp))
        }
        WorkbenchInspector("Deck overview","Composition, curve and mana for the suggested build."){DeckOverview(deck)}
    }
}

@Composable private fun CloseCutRow(card:DeckSpellUi,modifier:Modifier=Modifier){CardPreview(card.imageUrl,description=card.name,modifier=modifier.fillMaxWidth()){
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
        Box(Modifier.size(27.dp,33.dp).background(Color(0xFF24283B),RoundedCornerShape(3.dp)),contentAlignment=Alignment.Center){PipRow(card.color.filter{it in "WUBRG"}.take(2).toList(),15.dp)}
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(card.name+(if(card.count>1)" ×${card.count}" else ""),fontSize=11.sp,color=AppColors.textPrimary,maxLines=1,overflow=TextOverflow.Ellipsis);Text(card.roles.firstOrNull()?:card.typeLabel.ifBlank{"Alternative"},fontSize=9.sp,color=AppColors.textMuted,maxLines=1)}
    }
}}
@Composable internal fun ReadOnlyDeckList(cards:List<DeckSpellUi>,query:String="",horizontal:Boolean=false) {
    val rows=cards.filter{it.name.contains(query,true)}.sortedWith(compareBy<DeckSpellUi>{it.isLand}.then(deckSpellOrder))
    val layout=LocalWorkbenchLayout.current
    if(horizontal) {
        val groups=(if(rows.any{!it.isLand&&it.cmc==0})listOf(0)else emptyList())+(1..7)
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            groups.forEach{mv->val group=rows.filter{if(mv==7)it.isLand else !it.isLand&&it.cmc.coerceIn(0,6)==mv}
                Column(Modifier.width(140.dp).fillMaxHeight()){
                    Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){Text(if(mv==7)"Lands" else if(mv==6)"6+" else "$mv",fontSize=20.sp,color=AppColors.textSecondary);QuietText("${group.sumOf{it.count}} cards",true)};Hairline()
                    LazyColumn{items(group){card->CardPreview(card.imageUrl,description=card.name,modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.fillMaxWidth().padding(vertical=9.dp,horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Row{Text("${card.count}",style=AppTypography.monoMicro,color=AppColors.textMuted,modifier=Modifier.width(18.dp));Text(card.name,fontSize=11.sp,lineHeight=16.sp,color=AppColors.textPrimary)}
                            Box(Modifier.padding(start=23.dp)){CardMana(card,13.dp)}
                            Text(card.gihWr?.let{"${it.asPct()}% GIH WR"}?:card.typeLabel,style=AppTypography.micro.copy(fontSize=10.sp,fontWeight=FontWeight.Normal),color=AppColors.textMuted,modifier=Modifier.padding(start=23.dp))
                        }
                    };Hairline()}}
                }
            }
        }
    }else Column {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal=if(layout.small)4.dp else 9.dp),verticalAlignment=Alignment.CenterVertically){
            Text("#",style=AppTypography.micro,color=AppColors.textMuted,modifier=Modifier.width(if(layout.small)30.dp else 26.dp));Text("Card",style=AppTypography.micro,color=AppColors.textMuted,modifier=Modifier.weight(1f));Text("Mana",style=AppTypography.micro,color=AppColors.textMuted,modifier=Modifier.weight(if(layout.small).27f else .393f));if(layout.width>1100.dp)Text("Type",style=AppTypography.micro,color=AppColors.textMuted,modifier=Modifier.weight(.56f));HelpLabel("GIH WR","17Lands games in hand win rate. A dash means data is unavailable.",Modifier.width(if(layout.small)53.dp else 67.dp))
        };Hairline()
        LazyColumn{items(rows){ReadOnlyCardRow(it)}}
    }
}
@Composable private fun CardMana(card:DeckSpellUi,size:androidx.compose.ui.unit.Dp=15.dp) {
    if(card.isLand){val colors=card.color.filter{it in "WUBRG"}.ifEmpty{when(card.name){"Plains"->"W";"Island"->"U";"Swamp"->"B";"Mountain"->"R";"Forest"->"G";else->""}};PipRow(colors.toList(),size)}else ManaCost(card.manaCost,symbolSize=size)
}
@Composable private fun ReadOnlyCardRow(card:DeckSpellUi) {
    val layout=LocalWorkbenchLayout.current
    CardPreview(card.imageUrl,description=card.name,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().heightIn(min=34.dp).padding(horizontal=if(layout.small)4.dp else 9.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${card.count}",style=AppTypography.monoMicro,color=AppColors.textMuted,modifier=Modifier.width(if(layout.small)30.dp else 26.dp))
            Text(card.name,fontSize=if(layout.small)11.sp else 12.sp,lineHeight=16.sp,color=AppColors.textPrimary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            Box(Modifier.weight(if(layout.small).27f else .393f)){CardMana(card,if(layout.small)12.dp else 15.dp)}
            if(layout.width>1100.dp)Text(card.typeLabel,fontSize=11.sp,color=AppColors.textSecondary,maxLines=1,modifier=Modifier.weight(.56f))
            Text(card.gihWr?.let{"${it.asPct()}%"}?:"—",style=AppTypography.monoSmall.copy(fontSize=if(layout.small)11.sp else 12.sp),color=AppColors.accent,modifier=Modifier.width(if(layout.small)53.dp else 67.dp),textAlign=TextAlign.End)
        }
    };Hairline()
}
@Composable private fun DeckCurve(deck:DeckOptionUi) {
    val curve=(1..6).map{mv->deck.spells.filter{it.cmc.coerceIn(1,6)==mv}.sumOf{it.count}}
    val max=curve.maxOrNull()?.coerceAtLeast(1)?:1
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom){curve.forEachIndexed{i,n->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.width(15.dp).height((52f*n/max).dp.coerceAtLeast(2.dp)).background(if(i==1)Color(0xFFB8A7D1)else Color(0xFF6F6684),RoundedCornerShape(topStart=2.dp,topEnd=2.dp)));QuietText(if(i==5)"6+"else"${i+1}",true)}}}
    val total=deck.spells.sumOf{it.count}
    if(total>0){Spacer(Modifier.height(12.dp));QuietText("Average mana value ${"%.1f".format(deck.spells.sumOf{it.count*it.cmc}.toDouble()/total)} · Spells only",true)}
}
@Composable private fun DeckOverview(deck:DeckOptionUi) {
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Deck composition",style=AppTypography.caption,color=AppColors.textPrimary);QuietText("${(deck.spells+deck.lands).sumOf{it.count}} cards",true)}
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){listOf("Spells" to deck.spells.sumOf{it.count},"Creatures" to deck.creatures,"Removal" to deck.removal,"Lands" to deck.lands.sumOf{it.count}).forEach{(label,n)->Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text("$n",style=AppTypography.monoValue.copy(fontSize=20.sp,lineHeight=24.sp),color=Color(0xFFE5DFEF));Text(label,fontSize=9.sp,color=AppColors.textMuted)}}}
        Spacer(Modifier.height(18.dp));DeckCurve(deck);Spacer(Modifier.height(22.dp))
        InspectorSection("Build analysis") {
            Text(deck.outlook,style=AppTypography.caption.copy(lineHeight=18.sp),color=AppColors.textSecondary)
            Spacer(Modifier.height(12.dp));PipRow(deck.colors.filter{it in "WUBRG"}.toList(),15.dp)
            var details by remember{mutableStateOf(false)}
            Spacer(Modifier.height(12.dp));WorkbenchButton(onClick={details=!details},outlined=false,contentPadding=PaddingValues(0.dp)){Text(if(details)"Hide build analysis" else "View build analysis ↗",fontSize=10.sp)}
            if(details){Spacer(Modifier.height(10.dp));(deck.identityReasons+deck.powerReasons).distinct().forEach{QuietText(it)}}
        }
        InspectorSection("Suggested manabase") {deck.lands.forEach{land->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){CardMana(land,15.dp);Spacer(Modifier.width(7.dp));Text(land.name,style=AppTypography.caption,color=AppColors.textSecondary,modifier=Modifier.weight(1f));Text("${land.count}",style=AppTypography.monoMicro,color=AppColors.textSecondary)}}}
        InspectorSection("Card data"){QuietText("GIH WR = games in hand win rate. Hover a card to see its full image.");Spacer(Modifier.height(12.dp));QuietText("Metrics reflect the available 17Lands dataset.",true)}
    }
}
