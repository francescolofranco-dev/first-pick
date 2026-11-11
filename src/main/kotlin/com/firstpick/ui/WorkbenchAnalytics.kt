package com.firstpick.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.DraftState
import com.firstpick.stats.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

private data class ArchiveRow(val key: String, val history: DraftHistoryEntry?, val course: CourseRecord?)


@Composable
internal fun WorkbenchAnalytics(stats: PlayerStats, history: List<DraftHistoryEntry>, section: String, review: suspend (DraftState) -> DraftUiState, query: String = "", setNames: Map<String,String> = emptyMap(), onScope:(Int,Int)->Unit={_,_->}, onArchiveContext:(List<String>?)->Unit={}) {
    var selection by remember { mutableStateOf(AnalyticsSelection()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var inspectedMatch by remember { mutableStateOf<MatchRecord?>(null) }
    LaunchedEffect(section) { selectedKey = null; inspectedMatch=null }
    val records = remember(stats) { stats.draftOnly() }
    val courses = records.courses.filter { selection.includes(it.at,it.event) && "${it.event} ${it.at}".contains(query,true) }
    val matches = records.matches.filter { selection.includes(it.at,it.event) && "${it.event} ${it.at} ${it.outcome}".contains(query,true) }
    val drafts = history.filter { selection.includes(it.completedAt,it.state.eventName.orEmpty(),it.state.setCode) && "${it.state.setCode} ${it.state.eventName} ${it.completedAt}".contains(query,true) }
    val allRows = history.map { ArchiveRow("history:${it.id}",it,courseForDraft(it,records.courses)) } + records.courses.filter{c->history.none{courseForDraft(it,listOf(c))!=null}}.map{ArchiveRow("course:${it.id}",null,it)}
    val selected=allRows.firstOrNull{it.key==selectedKey}
    SideEffect {
        onScope(courses.count{it.complete},matches.size)
        onArchiveContext(selected?.let{r->listOfNotNull(r.history?.state?.setCode?:r.course?.let{setOfEvent(it.event)},(r.history?.completedAt?:r.course?.at)?.take(10),r.course?.let{c->if(c.wins!=null&&c.losses!=null)"${c.wins}–${c.losses} · Completed"else"Result unavailable"})})
    }
    if(selected!=null){ArchiveReview(selected,selected.course?.id?.let{id->records.matches.filter{it.courseId==id}}.orEmpty(),review,onBack={selectedKey=null},setNames=setNames);return}
    val layout=LocalWorkbenchLayout.current
    val sets=(history.mapNotNull{it.state.setCode}+records.courses.mapNotNull{setOfEvent(it.event)}).distinct().sorted()
    val formats=records.courses.map{eventType(it.event)}.distinct().sorted()
    val months=(records.courses.map{it.at}+history.map{it.completedAt}).mapNotNull{at->runCatching{YearMonth.from(Instant.parse(at).atZone(ZoneId.systemDefault()))}.getOrNull()}.distinct().sortedDescending()
    val monthLabels=months.associate{it.toString() to it.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale.ENGLISH))}
    val periodLabel=if(selection.last30Days)"Last 30 days" else selection.month?.let{monthLabels[it.toString()]?:it.toString()}?:"All time"
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(end=layout.gutter,bottom=20.dp,top=layout.top)) {
            Text("TELEMETRY",style=AppTypography.sectionHeader,color=Color(0xFF9599B8))
            Spacer(Modifier.height(9.dp));WorkbenchHeading(if(section=="Overview")"Your draft performance" else "Match history")
            Spacer(Modifier.height(if(layout.small)22.dp else 28.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(layout.small)8.dp else 12.dp),verticalAlignment=Alignment.Bottom) {
                FilterField("Expansion",selection.set?.let{setNames[it]?:it}?:"All expansions",listOf("All expansions")+sets.map{setNames[it]?:it},Modifier.weight(1f)) { v->selection=selection.copy(set=sets.firstOrNull{(setNames[it]?:it)==v}) }
                FilterField("Format",selection.format?:"All formats",listOf("All formats")+formats,Modifier.weight(1f)){selection=selection.copy(format=it.takeUnless{v->v=="All formats"})}
                FilterField("Period",periodLabel,listOf("All time","Last 30 days")+monthLabels.values,Modifier.weight(1f)){v->selection=selection.copy(last30Days=v=="Last 30 days",month=monthLabels.entries.firstOrNull{it.value==v}?.key?.let(YearMonth::parse))}
            }
            if(selection!=AnalyticsSelection()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){WorkbenchButton(onClick={selection=AnalyticsSelection()},outlined=false,contentPadding=PaddingValues(top=5.dp)){Text("Reset",fontSize=10.sp)}}
            Spacer(Modifier.height(14.dp));Hairline();Spacer(Modifier.height(if(layout.small)16.dp else 22.dp))
            stats.error?.let{Text(it,color=AppColors.warning,style=AppTypography.micro)}
            if(stats.importing)QuietText("Recovering local records…",true)
            val openMatch:(MatchRecord)->Unit={m-> val row=m.courseId?.let{id->allRows.firstOrNull{it.course?.id==id}};if(row!=null)selectedKey=row.key else inspectedMatch=m }
            if(section=="Overview") {
                val results=courses.filter{it.wins!=null&&it.losses!=null}.courseResults()
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical=if(layout.small)15.dp else 22.dp)) {
                            if(layout.small) {
                                listOf("Match win rate" to (results.winRate?.let{"${it.asPct()}%"}?:"—"),"Completed drafts" to "${courses.count{it.complete}}","Trophy runs" to "${courses.count{it.isTrophy()}}" ).forEachIndexed{i,(label,value)->
                                    if(i>0)Spacer(Modifier.height(10.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){QuietText(label);Text(value,style=AppTypography.monoValue.copy(fontSize=24.sp,lineHeight=29.sp),color=AppColors.textPrimary)}
                                }
                            } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                                AnalyticsMetric("Match win rate",results.winRate?.let{"${it.asPct()}%"}?:"—","${results.wins} wins · ${results.losses} losses",Modifier.weight(1f))
                                AnalyticsMetric("Completed drafts","${courses.count{it.complete}}","Matching all selected filters",Modifier.weight(1f))
                                AnalyticsMetric("Trophy runs","${courses.count{it.isTrophy()}}","Confirmed finishes",Modifier.weight(1f))
                            }
                        };Hairline()
                    }
                    item {Spacer(Modifier.height(22.dp));FinishChart(courses)}
                    item {Spacer(Modifier.height(24.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Match results",color=AppColors.textPrimary,fontSize=13.sp,fontWeight=FontWeight.Medium);QuietText("${matches.size} matching matches",true)};Spacer(Modifier.height(12.dp));MatchTableHeader()}
                    if(matches.isEmpty())item{QuietText("No matches in this selection.")}
                    items(matches.take(40),key={it.id}){m->MatchRecordRow(m){openMatch(m)}}
                }
            } else {
                val rows=allRows.filter{it.history?.let{e->e in drafts}?: (it.course in courses)}.sortedByDescending{it.history?.completedAt?:it.course?.at}
                Text("Completed drafts",color=AppColors.textPrimary,fontSize=13.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(6.dp));QuietText("Open a draft to review its deck, picks and matches.");Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp)){Text("Completed",modifier=Modifier.width(if(layout.small)76.dp else 100.dp),style=AppTypography.micro,color=AppColors.textMuted);Text("Draft",modifier=Modifier.weight(1f),style=AppTypography.micro,color=AppColors.textMuted);if(!layout.small)Text("Format",modifier=Modifier.width(115.dp),style=AppTypography.micro,color=AppColors.textMuted);QuietText("Record",true)};Hairline()
                LazyColumn(Modifier.weight(1f)) {
                    items(rows,key={it.key}){row->
                        Row(Modifier.fillMaxWidth().heightIn(min=57.dp).clickable{selectedKey=row.key}.padding(horizontal=8.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
                            Text((row.history?.completedAt?:row.course?.at).orEmpty().take(10),style=AppTypography.micro.copy(fontWeight=FontWeight.Normal),color=AppColors.textSecondary,modifier=Modifier.width(if(layout.small)76.dp else 100.dp))
                            val code=row.history?.state?.setCode?:row.course?.let{setOfEvent(it.event)}?:"Unknown set"
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(setNames[code]?:code,color=AppColors.textPrimary,fontSize=if(layout.small)11.sp else 12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);QuietText("$code · Saved draft",true)}
                            if(!layout.small)Text((row.course?.event?:row.history?.state?.eventName)?.let(::eventType).orEmpty(),style=AppTypography.caption,color=AppColors.textSecondary,modifier=Modifier.width(115.dp))
                            Text(row.course?.let{if(it.wins!=null&&it.losses!=null)"${it.wins}–${it.losses}" else "—"}?:"—",style=AppTypography.monoSmall,color=if(row.course?.isTrophy()==true)AppColors.warning else AppColors.textSecondary);Spacer(Modifier.width(8.dp));QuietText("↗",true)
                        };Hairline()
                    }
                    if(rows.isEmpty())item{QuietText("No draft records match your filters.")}
                }
            }
        }
        WorkbenchInspector("Report context","Filters combine across this report and stay applied when you change sections.") {
            InspectorSection("Active filters") {InspectorLine("Expansion",selection.set?.let{setNames[it]?:it}?:"All expansions");InspectorLine("Format",selection.format?:"All formats");InspectorLine("Period",periodLabel)}
            InspectorSection("Arena seasons") {QuietText("Arena ranked seasons are monthly and separate from expansions. Older records do not contain exact season assignments; date filters use calendar months.")}
            InspectorSection("Match details") {if(inspectedMatch==null)QuietText("Select a match row for its context.") else {val m=inspectedMatch!!;QuietText("${m.outcome} · ${m.at.take(10)}");QuietText(eventType(m.event));QuietText(when(m.onPlay){true->"On the play";false->"On the draw";null->"Starting player unavailable"})}}
            QuietText("Saved on this Mac. In-progress event totals may be partial; only completed results appear in the finish chart.",true)
        }
    }
}

@Composable private fun FilterField(label:String,value:String,choices:List<String>,modifier:Modifier,onChoose:(String)->Unit) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,style=AppTypography.micro.copy(fontWeight=FontWeight.Normal),color=AppColors.textSecondary);WorkbenchChoice(value,choices,onChoose,Modifier.fillMaxWidth())}
}
@Composable internal fun WorkbenchChoice(value:String,choices:List<String>,onChoose:(String)->Unit,modifier:Modifier=Modifier) {
    var open by remember{mutableStateOf(false)}
    Box(modifier){WorkbenchButton(onClick={open=true},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=8.dp,vertical=if(LocalWorkbenchLayout.current.small)7.dp else 8.dp)){Text(value,modifier=Modifier.weight(1f),fontSize=if(LocalWorkbenchLayout.current.small)10.sp else 11.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("⌄",fontSize=10.sp)};DropdownMenu(open,onDismissRequest={open=false}){choices.forEach{v->DropdownMenuItem(text={Text(v,fontSize=12.sp)},onClick={open=false;onChoose(v)})}}}
}
@Composable private fun AnalyticsMetric(label:String,value:String,detail:String,modifier:Modifier=Modifier){Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){QuietText(label);Text(value,style=AppTypography.monoValue.copy(fontSize=30.sp,lineHeight=36.sp),color=AppColors.textPrimary);QuietText(detail)}}

@Composable internal fun FinishChart(courses:List<CourseRecord>) {
    val counts=finishDistribution(courses);val max=counts.maxOrNull()?.coerceAtLeast(1)?:1
    val step=((max+3)/4).coerceAtLeast(1);val ceiling=((max+step-1)/step)*step
    val layout=LocalWorkbenchLayout.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Final wins per draft",fontSize=13.sp,fontWeight=FontWeight.Medium,color=AppColors.textPrimary);QuietText("${counts.sum()} completed drafts",true)}
    Spacer(Modifier.height(12.dp));QuietText("How many drafts ended at each win total.");Spacer(Modifier.height(23.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Color(0x00171A29),Color(0x66171A29)))).border(1.dp,Color(0x13C9B2E2),RoundedCornerShape(9.dp)).padding(horizontal=if(layout.small)12.dp else 18.dp,vertical=if(layout.small)14.dp else 18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Number of drafts",fontSize=if(layout.small)9.sp else 10.sp,color=Color(0xFF9097AF));Text("7 wins · Trophy finish",fontSize=if(layout.small)9.sp else 10.sp,color=Color(0xFFCBB487))}
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            for(tick in 0..ceiling step step){val y=22f+(1f-tick.toFloat()/ceiling)*128f
                Text("$tick",style=AppTypography.monoMicro.copy(fontSize=10.sp),color=Color(0xFF757D98),modifier=Modifier.offset(y=(y-7).dp))
                Box(Modifier.padding(start=25.dp).offset(y=y.dp).fillMaxWidth().height(1.dp).background(Color(if(tick==0)0x30C4BAD8 else 0x16BBB1D4)))
            }
            Row(Modifier.fillMaxSize().padding(start=25.dp,top=22.dp,bottom=10.dp)) {
                counts.forEachIndexed { wins,count->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AppTooltip(title="$wins final wins",tooltip="$count ${if(count==1)"draft" else "drafts"} finished with $wins wins.${if(counts.sum()>0)" ${(100.0*count/counts.sum()).roundToInt()}% of this selection." else ""}") {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val h=maxHeight;val bar=(h.value*count/ceiling).dp
                                Box(Modifier.fillMaxWidth(.56f).widthIn(max=64.dp).height(bar.coerceAtLeast(1.dp)).align(Alignment.BottomCenter).clip(RoundedCornerShape(topStart=3.dp,topEnd=3.dp)).background(Brush.verticalGradient(if(wins==7)listOf(Color(0xFFE5C990),Color(0xFFA07B43))else listOf(Color(0xFFA18BB9),Color(0xFF696082)))))
                                Text(if(count>0)"$count" else "—",style=AppTypography.monoSmall.copy(fontSize=if(layout.small)11.sp else 12.sp),color=if(count==0)Color(0xFF505A73) else if(wins==7)Color(0xFFE2C58E)else Color(0xFFC6BDD8),textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth().offset(y=h-bar-23.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=25.dp)){counts.forEachIndexed{i,_->Text("$i",modifier=Modifier.weight(1f),style=AppTypography.monoSmall.copy(fontSize=11.sp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=if(i==7)Color(0xFFDDC28D) else Color(0xFF9A9FB7))}}
        Spacer(Modifier.height(15.dp));Hairline();Spacer(Modifier.height(12.dp))
        val average=if(counts.sum()>0)counts.mapIndexed{i,n->i*n}.sum().toDouble()/counts.sum() else null
        Spacer(Modifier.height(if(layout.small)8.dp else 0.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){QuietText("Final wins",true);QuietText(if(average==null)"No completed results" else "${"%.1f".format(average)} average wins  ·  ${counts[7]} seven-win finishes",true)}
    }
}
@Composable private fun MatchTableHeader(){Row(Modifier.fillMaxWidth().padding(vertical=12.dp,horizontal=5.dp)){listOf("Played" to 1.1f,"Expansion" to .75f,"Format" to 1.2f,"Result" to .7f,"Position" to 1.1f).forEach{(s,w)->Text(s,style=AppTypography.micro.copy(fontSize=10.sp,fontWeight=FontWeight.Normal),color=AppColors.textMuted,modifier=Modifier.weight(w))}};Hairline()}
@Composable private fun MatchRecordRow(match:MatchRecord,onClick:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=38.dp).clickable(onClick=onClick).padding(horizontal=5.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){val size=if(LocalWorkbenchLayout.current.small)10.sp else 12.sp;listOf(match.at.take(10) to 1.1f,setOfEvent(match.event).orEmpty() to .75f,eventType(match.event) to 1.2f,match.outcome to .7f,when(match.onPlay){true->"On the play";false->"On the draw";null->"Unavailable"} to 1.1f).forEachIndexed{i,(label,w)->Text(label,fontSize=size,color=if(i==3&&match.outcome=="Win")AppColors.accent else AppColors.textSecondary,modifier=Modifier.weight(w),maxLines=1,overflow=TextOverflow.Ellipsis)}};Hairline()}

@Composable private fun ArchiveReview(row: ArchiveRow, matches: List<MatchRecord>, onReview: suspend (DraftState) -> DraftUiState, onBack: () -> Unit, setNames:Map<String,String> = emptyMap()) {
    var tab by remember(row.key) { mutableStateOf("Deck") }
    var selectedPack by remember(row.key) { mutableStateOf<Pair<Int, Int>?>(null) }
    val review by produceState<DraftUiState?>(null, row.key) {
        row.history?.let { entry -> value = try { onReview(entry.state) } catch (e: CancellationException) { throw e } catch (_: Exception) { DraftUiState(dataError = "Could not load card data for this draft.") } }
    }
    val packReview by produceState<DraftUiState?>(null, row.key, selectedPack) {
        val pack = selectedPack
        if (pack != null && row.history != null) {
            val state = row.history.state
            // Historical pool order and pick attribution are not guaranteed. Show the stored pack,
            // but do not invent the pick taken or recompute a historical contextual rank from the final pool.
            value = try { onReview(state.copy(pack = pack.first, pick = pack.second, packCards = state.seen[pack].orEmpty())) } catch (e: CancellationException) { throw e } catch (_: Exception) { DraftUiState(dataError = "Card data unavailable.") }
        }
    }
    val layout=LocalWorkbenchLayout.current
    val code=row.history?.state?.setCode?:row.course?.let{setOfEvent(it.event)}?:"Archived draft"
    val format=(row.course?.event?:row.history?.state?.eventName)?.let(::eventType).orEmpty()
    val date=(row.history?.completedAt?:row.course?.at).orEmpty().take(10)
    val record=row.course?.let{if(it.wins!=null&&it.losses!=null)"${it.wins}–${it.losses}" else "—"}?:"—"
    Row(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxHeight().padding(end=layout.gutter,top=layout.top,bottom=20.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            WorkbenchButton(onClick=onBack,outlined=false,contentPadding=PaddingValues(0.dp)){Text("← Back to match history",fontSize=11.sp)}
            QuietText("Completed draft",true)
        }
        Spacer(Modifier.height(18.dp))
        Text("${setNames[code]?:code} · $format".uppercase(),style=AppTypography.sectionHeader,color=AppColors.textMuted)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){WorkbenchHeading(setNames[code]?:code);QuietText(date)}
            Column(horizontalAlignment=Alignment.End){Text(record,style=AppTypography.monoValue.copy(fontSize=26.sp,lineHeight=31.sp),color=AppColors.textPrimary);QuietText("Final record",true)}
        }
        Spacer(Modifier.height(17.dp))
        WorkbenchTabs(listOf("Deck","Pick log","Matches").map{label->SegmentedTabItem(label,selected=tab==label,onClick={tab=label;selectedPack=null})},underlined=true)
        Hairline();Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f)) { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when (tab) {
            "Deck" -> {
                val data = review
                val saved = data?.savedDeck
                when {
                    row.history == null -> QuietText("No card snapshot was recorded for this imported event.")
                    data == null -> QuietText("Loading archived cards…")
                    data.dataError != null -> QuietText(data.dataError)
                    saved != null -> {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Final deck snapshot",fontSize=13.sp,color=AppColors.textPrimary);QuietText("${saved.totalCards} cards",true)}
                        QuietText("Recorded saved deck. Unsaved Arena edits are not included.")
                        val known = data.draftPool + data.deckOptions.flatMap { it.spells + it.lands }
                        ReadOnlyDeckList(saved.cards.map { card -> known.firstOrNull { it.name == card.name }?.copy(count = card.count) ?: DeckSpellUi(card.name, 0, "", null, count = card.count, isLand = card.isBasicLand, isBasicLand = card.isBasicLand) })
                    }
                    else -> {
                        QuietText("No final deck was saved. Showing the recorded drafted pool, not a reconstructed final deck.")
                        ReadOnlyDeckList(data.draftPool)
                    }
                }
            }
            "Pick log" -> {
                val seen = row.history?.state?.seen.orEmpty()
                if (seen.isEmpty()) QuietText("No pack history was recorded for this draft.")
                else if (selectedPack == null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Recorded packs",fontSize=13.sp,color=AppColors.textPrimary);QuietText("${seen.size} saved packs",true)}
                    QuietText("Card-taken attribution and the original recommendation scores were not saved, so they are not inferred here.")
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp)) {QuietText("Pick",true);Spacer(Modifier.width(56.dp));Text("Pack contents",fontSize=10.sp,color=AppColors.textMuted,modifier=Modifier.weight(1f));Text("Advice at the time",fontSize=10.sp,color=AppColors.textMuted,modifier=Modifier.width(if(layout.small)100.dp else 200.dp))};Hairline()
                    LazyColumn { items(seen.keys.sortedWith(compareBy({ it.first }, { it.second }))) { pack ->
                        Row(Modifier.fillMaxWidth().height(38.dp).clickable{selectedPack=pack}.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("P${pack.first} · ${pack.second}",fontSize=11.sp,color=AppColors.textPrimary,modifier=Modifier.width(76.dp))
                            Text("${seen[pack]?.size} recorded cards",fontSize=11.sp,color=AppColors.textSecondary,modifier=Modifier.weight(1f))
                            Text("Unavailable",fontSize=11.sp,color=AppColors.textMuted,modifier=Modifier.width(if(layout.small)85.dp else 185.dp));QuietText("↗",true)
                        };Hairline()
                    } }
                } else {
                    WorkbenchButton(onClick = { selectedPack = null }) { Text("← All recorded packs") }
                    val data = packReview
                    if (data == null) QuietText("Loading recorded pack…") else if (data.packCards.isEmpty()) QuietText(data.dataError ?: "Card details unavailable.")
                    else LazyColumn { items(data.packCards.sortedBy { it.originalIndex }) { card -> CardPreview(card.imageUrl, description = card.name, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(10.dp)) { Text(card.name, color = AppColors.textPrimary, style = AppTypography.body, modifier = Modifier.weight(1f)); PipRow(card.color.toList(), 14.dp) } }; Hairline() } }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Matches in this draft",fontSize=13.sp,color=AppColors.textPrimary);QuietText("${matches.count{it.outcome=="Win"}} wins · ${matches.count{it.outcome=="Loss"}} losses",true)}
                if(matches.isEmpty())QuietText("No individual matches could be linked to this draft. Event totals may still be available above.")
                else {
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp)){listOf("Match" to .7f,"Result" to .7f,"Position" to 1.3f,"Played" to 1.4f).forEach{(label,w)->Text(label,fontSize=10.sp,color=AppColors.textMuted,modifier=Modifier.weight(w))}};Hairline()
                    LazyColumn { items(matches.sortedBy{it.at}.withIndex().toList(),key={it.value.id}){(i,m)->
                        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            listOf("${i+1}" to .7f,m.outcome to .7f,when(m.onPlay){true->"On the play";false->"On the draw";null->"Unavailable"} to 1.3f,m.at.take(10) to 1.4f).forEachIndexed{col,(text,w)->Text(text,fontSize=if(layout.small)10.sp else 12.sp,color=if(col==1&&m.outcome=="Win")AppColors.accent else AppColors.textSecondary,modifier=Modifier.weight(w),maxLines=1,overflow=TextOverflow.Ellipsis)}
                        };Hairline()
                    } }
                }
            }
        }
        } }
    }
    WorkbenchInspector("Draft review","A completed draft, preserved for review.") {
        InspectorSection("Draft context"){InspectorLine("Expansion",code);InspectorLine("Format",format);InspectorLine("Completed",date)}
        InspectorSection("Result"){InspectorLine("Final record",record);InspectorLine("Match win rate",row.course?.let{c->val total=(c.wins?:0)+(c.losses?:0);if(c.wins!=null&&c.losses!=null&&total>0)"${(c.wins.toDouble()/total).asPct()}%" else "—"}?:"—")}
        InspectorSection("Review notes"){QuietText("Browse recorded cards, packs and linked matches. Original recommendation scores and unrecorded decisions are unavailable.")}
        QuietText("Read-only archive · Saved on this Mac.",true)
    }
    }
}
