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
internal fun WorkbenchAnalytics(stats: PlayerStats, history: List<DraftHistoryEntry>, section: String, review: suspend (DraftState) -> DraftUiState, query: String = "") {
    var selection by remember { mutableStateOf(AnalyticsSelection()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(section) { selectedKey = null }
    val records = remember(stats) { stats.draftOnly() }
    val courses = records.courses.filter { selection.includes(it.at, it.event) && "${it.event} ${it.at}".contains(query, true) }
    val matches = records.matches.filter { selection.includes(it.at, it.event) && "${it.event} ${it.at} ${it.outcome}".contains(query, true) }
    val drafts = history.filter { selection.includes(it.completedAt, it.state.eventName.orEmpty(), it.state.setCode) && "${it.state.setCode} ${it.state.eventName} ${it.completedAt}".contains(query, true) }
    val allRows = history.map { ArchiveRow("history:${it.id}", it, courseForDraft(it, records.courses)) } +
        records.courses.filter { c -> history.none { courseForDraft(it, listOf(c)) != null } }.map { ArchiveRow("course:${it.id}", null, it) }
    val selected = allRows.firstOrNull { it.key == selectedKey }
    if (selected != null) {
        ArchiveReview(selected, selected.course?.id?.let { id -> records.matches.filter { it.courseId == id } }.orEmpty(), review) { selectedKey = null }
        return
    }
    val sets = (history.mapNotNull { it.state.setCode } + records.courses.mapNotNull { setOfEvent(it.event) }).distinct().sorted()
    val formats = records.courses.map { eventType(it.event) }.distinct().sorted()
    val months = (records.courses.map { it.at } + history.map { it.completedAt }).mapNotNull { at -> runCatching { YearMonth.from(Instant.parse(at).atZone(ZoneId.systemDefault())) }.getOrNull() }.distinct().sortedDescending()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (section == "Overview") "Your draft performance" else "Match history", style = AppTypography.paneTitle.copy(fontSize = 22.sp), color = AppColors.textPrimary)
        if (stats.importing) QuietText("Recovering local records…")
        stats.error?.let { Text(it, color = AppColors.warning, style = AppTypography.caption) }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            WorkbenchChoice(selection.set ?: "All expansions", listOf("All expansions") + sets) { selection = selection.copy(set = it.takeUnless { s -> s == "All expansions" }) }
            WorkbenchChoice(selection.format ?: "All formats", listOf("All formats") + formats) { selection = selection.copy(format = it.takeUnless { s -> s == "All formats" }) }
            WorkbenchChoice(if (selection.last30Days) "Last 30 days" else selection.month?.toString() ?: "All time", listOf("All time", "Last 30 days") + months.map { "$it" }) {
                selection = selection.copy(last30Days = it == "Last 30 days", month = runCatching { YearMonth.parse(it) }.getOrNull())
            }
            if (selection != AnalyticsSelection()) AppButton(onClick = { selection = AnalyticsSelection() }) { Text("Reset", fontSize = 10.sp) }
        }
        if (selection.month != null) QuietText("Calendar month in local time. Historical Arena season assignments are not recorded.", true)
        Hairline()
        if (section == "Overview") {
            val results = courses.filter { it.wins != null && it.losses != null }.courseResults()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AnalyticsMetric("Match win rate", results.winRate?.let { "${it.asPct()}%" } ?: "—", "${results.wins} wins · ${results.losses} losses")
                        AnalyticsMetric("Completed drafts", "${courses.count { it.complete }}", "${drafts.size} saved draft logs")
                        AnalyticsMetric("Trophy runs", "${courses.count { it.isTrophy() }}", "Confirmed completed results")
                    }
                }
                item { QuietText("Result totals use recorded event results. In-progress events may be partial; only completed events appear in the finish chart.", true) }
                item { FinishChart(courses) }
                item { Text("Recent matches", style = AppTypography.paneTitle, color = AppColors.textPrimary) }
                if (matches.isEmpty()) item { QuietText("No matches in this selection.") }
                items(matches.take(20), key = { it.id }) { match ->
                    MatchRecordRow(match) { match.courseId?.let { id -> allRows.firstOrNull { it.course?.id == id } }?.let { selectedKey = it.key } }
                }
            }
        } else {
            val visibleRows = allRows.filter { row -> row.history?.let { it in drafts } ?: (row.course in courses) }.sortedByDescending { it.history?.completedAt ?: it.course?.at }
            QuietText("Open a completed draft to review the recorded deck, pack history and matches.")
            LazyColumn {
                items(visibleRows, key = { it.key }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { selectedKey = row.key }.padding(vertical = 13.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.history?.state?.setCode ?: row.course?.let { setOfEvent(it.event) } ?: "Unknown expansion", style = AppTypography.body, color = AppColors.textPrimary)
                            QuietText((row.course?.event ?: row.history?.state?.eventName)?.let(::eventType).orEmpty(), true)
                        }
                        QuietText((row.history?.completedAt ?: row.course?.at).orEmpty().take(10))
                        Spacer(Modifier.width(18.dp))
                        Text(row.course?.let { c -> if (c.wins != null && c.losses != null) "${c.wins}–${c.losses}" else "—" } ?: "—", style = AppTypography.monoValue, color = if (row.course?.isTrophy() == true) AppColors.warning else AppColors.textSecondary)
                        Spacer(Modifier.width(10.dp)); QuietText("↗")
                    }
                    Hairline()
                }
                if (visibleRows.isEmpty()) item { QuietText("No draft records match your filters.") }
            }
        }
    }
}

@Composable internal fun WorkbenchChoice(value: String, choices: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppButton(onClick = { expanded = true }) { Text("$value ▾", style = AppTypography.micro, maxLines = 1) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onChoose(choice) }) } }
    }
}

@Composable private fun AnalyticsMetric(label: String, value: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        QuietText(label)
        Text(value, style = AppTypography.monoValue.copy(fontSize = 24.sp), color = AppColors.textPrimary)
        QuietText(detail, true)
    }
}

@Composable private fun FinishChart(courses: List<CourseRecord>) {
    val counts = finishDistribution(courses)
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxWidth().background(AppColors.surface, androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Final wins per draft", style = AppTypography.body, color = AppColors.textPrimary)
            QuietText("${counts.sum()} completed drafts", true)
        }
        QuietText("Number of drafts ending at each win total", true)
        if (counts.sum() == 0) QuietText("No completed results available. In-progress drafts are excluded.")
        else {
            Box(Modifier.fillMaxWidth().height(172.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) { repeat(3) { Hairline() } }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    counts.forEachIndexed { wins, count ->
                        Box(Modifier.weight(1f)) {
                            AppTooltip(title = "$wins final wins", tooltip = "$count ${if (count == 1) "draft" else "drafts"} finished with $wins wins.") {
                                Column(Modifier.fillMaxWidth().height(172.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                    Text(if (count > 0) "$count" else "—", style = AppTypography.monoMicro, color = if (wins == 7) AppColors.warning else AppColors.textSecondary)
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth(.65f).widthIn(max = 56.dp).height((count.toFloat() / max * 124).dp.coerceAtLeast(1.dp)).background(Brush.verticalGradient(listOf(if (wins == 7) AppColors.warning else AppColors.accent, if (wins == 7) AppColors.warning.copy(alpha = .5f) else AppColors.accent.copy(alpha = .45f)))))
                                    Spacer(Modifier.height(7.dp)); QuietText("$wins", true)
                                }
                            }
                        }
                    }
                }
            }
            QuietText("Final match wins · 0–7. Gold marks seven-win finishes.", true)
        }
    }
}

@Composable private fun MatchRecordRow(match: MatchRecord, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(match.outcome, color = if (match.outcome == "Win") AppColors.positive else AppColors.textSecondary, style = AppTypography.body, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) { QuietText("${setOfEvent(match.event).orEmpty()} · ${eventType(match.event)}"); QuietText(match.at.take(10), true) }
        QuietText(when (match.onPlay) { true -> "On the play"; false -> "On the draw"; null -> "Position unavailable" }, true)
    }
    Hairline()
}

@Composable private fun ArchiveReview(row: ArchiveRow, matches: List<MatchRecord>, onReview: suspend (DraftState) -> DraftUiState, onBack: () -> Unit) {
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppButton(onClick = onBack) { Text("← Back to history") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(row.history?.state?.setCode ?: row.course?.let { setOfEvent(it.event) } ?: "Archived draft", style = AppTypography.paneTitle.copy(fontSize = 22.sp), color = AppColors.textPrimary)
                QuietText("${(row.course?.event ?: row.history?.state?.eventName)?.let(::eventType).orEmpty()} · ${(row.history?.completedAt ?: row.course?.at).orEmpty().take(10)}")
            }
            Text(row.course?.let { if (it.wins != null && it.losses != null) "${it.wins}–${it.losses}" else "—" } ?: "—", style = AppTypography.monoValue.copy(fontSize = 25.sp), color = AppColors.warning)
        }
        AppSegmentedTabs(listOf("Deck", "Pick log", "Matches").map { label -> SegmentedTabItem(label, selected = tab == label, onClick = { tab = label; selectedPack = null }) })
        when (tab) {
            "Deck" -> {
                val data = review
                val saved = data?.savedDeck
                when {
                    row.history == null -> QuietText("No card snapshot was recorded for this imported event.")
                    data == null -> QuietText("Loading archived cards…")
                    data.dataError != null -> QuietText(data.dataError)
                    saved != null -> {
                        QuietText("Recorded saved deck · ${saved.totalCards} cards. Unsaved Arena edits are not included.")
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
                    QuietText("Recorded packs. Card-taken attribution and the original recommendation scores were not saved, so they are not inferred here.")
                    LazyColumn { items(seen.keys.sortedWith(compareBy({ it.first }, { it.second }))) { pack ->
                        AppButton(onClick = { selectedPack = pack }, modifier = Modifier.fillMaxWidth()) { Text("Pack ${pack.first} · Pick ${pack.second}    ${seen[pack]?.size} cards") }
                        Spacer(Modifier.height(6.dp))
                    } }
                } else {
                    AppButton(onClick = { selectedPack = null }) { Text("← All recorded packs") }
                    val data = packReview
                    if (data == null) QuietText("Loading recorded pack…") else if (data.packCards.isEmpty()) QuietText(data.dataError ?: "Card details unavailable.")
                    else LazyColumn { items(data.packCards.sortedBy { it.originalIndex }) { card -> CardPreview(card.imageUrl, description = card.name, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(10.dp)) { Text(card.name, color = AppColors.textPrimary, style = AppTypography.body, modifier = Modifier.weight(1f)); PipRow(card.color.toList(), 14.dp) } }; Hairline() } }
                }
            }
            else -> {
                if (matches.isEmpty()) QuietText("No individual matches could be linked to this draft. Event totals may still be available above.")
                else LazyColumn { items(matches, key = { it.id }) { MatchRecordRow(it) {} } }
            }
        }
    }
}
