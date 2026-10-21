package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.stats.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsPane(stats: PlayerStats, history: List<DraftHistoryEntry>) {
    val draftStats = remember(stats) { stats.draftOnly() }
    val draftHistory = remember(history) { history.filter { isDraftEvent(it.state.eventName.orEmpty()) } }
    var section by remember { mutableStateOf("Overview") }
    var period by remember { mutableStateOf("All time") }
    var set by remember { mutableStateOf("All sets") }
    var format by remember { mutableStateOf("All draft types") }
    val cutoff = when (period) {
        "Last 7 days" -> Instant.now().minus(Duration.ofDays(7))
        "Last 30 days" -> Instant.now().minus(Duration.ofDays(30))
        else -> Instant.MIN
    }
    fun included(at: String, event: String): Boolean =
        runCatching { Instant.parse(at) >= cutoff }.getOrDefault(false) &&
            (set == "All sets" || setOfEvent(event) == set) &&
            (format == "All draft types" || eventType(event) == format)
    val courses = draftStats.courses.filter { included(it.at, it.event) }
    val matches = draftStats.matches.filter { included(it.at, it.event) }
    val drafts = draftHistory.filter { included(it.completedAt, it.state.eventName.orEmpty()) }
    val sets = (draftHistory.mapNotNull { it.state.setCode } + draftStats.courses.mapNotNull { setOfEvent(it.event) }).distinct().sorted()
    val formats = (draftStats.courses.map { eventType(it.event) } + draftStats.matches.map { eventType(it.event) } +
        draftHistory.map { eventType(it.state.eventName.orEmpty()) }).distinct().sorted()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Draft stats", style = AppTheme.typography.paneTitle, color = AppTheme.colors.textPrimary)
            Text(if (stats.importing) "Recovering local records…" else "Saved on this Mac",
                style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        }
        stats.error?.let { Text(it, color = AppTheme.colors.warning, style = AppTheme.typography.caption) }
        AppSegmentedTabs(listOf("Overview", "Matches", "Rank").map { label ->
            SegmentedTabItem(label, selected = section == label, onClick = { section = label })
        }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StatsFilter(period, listOf("All time", "Last 30 days", "Last 7 days")) { period = it }
            if (section != "Rank") {
                StatsFilter(set, listOf("All sets") + sets) { set = it }
                StatsFilter(format, listOf("All draft types") + formats) { format = it }
            }
        }
        when (section) {
            "Overview" -> OverviewStats(courses, matches, drafts)
            "Matches" -> MatchStats(matches)
            else -> RankStats(draftStats.ranks.filter { runCatching { Instant.parse(it.at) >= cutoff }.getOrDefault(false) })
        }
    }
}

@Composable
private fun StatsFilter(value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
            Text("$value ▾", fontSize = 11.sp, color = AppTheme.colors.textPrimary)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (choice in choices) DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); open = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewStats(courses: List<CourseRecord>, matches: List<MatchRecord>, drafts: List<DraftHistoryEntry>) {
    val limited = courses.filter { isDraftEvent(it.event) }
    val withResults = limited.filter { it.wins != null && it.losses != null && (it.wins + it.losses) > 0 }
    val results = withResults.courseResults()
    val trophies = withResults.count { it.isTrophy() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsMetric("Drafts saved", drafts.size.toString())
                StatsMetric("Draft record", if (withResults.isEmpty()) "—" else "${results.wins}–${results.losses}")
                StatsMetric("Win rate", rate(results.winRate))
                StatsMetric("Recorded trophies", if (withResults.isEmpty()) "—" else trophies.toString())
            }
        }
        item {
            Text("Draft results cover ${withResults.size} events with recorded results. Saved drafts without results are excluded. Counts may be partial if an event’s final result was not logged. Date filters select events by first observation and show their full recorded totals.",
                style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        }
        if (withResults.any { it.sourceUrl != null }) item {
            Text("Includes recovered Untapped.gg event totals. Individual match coverage may be smaller; imported match dates use the draft date when the exact time is unavailable.",
                style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        }
        item {
            Panel("By set", trailing = "Draft event results") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = (limited.mapNotNull { setOfEvent(it.event) } + drafts.mapNotNull { it.state.setCode }).distinct().sorted()
                    if (groups.isEmpty()) StatsEmpty("No draft records in this selection.")
                    for (code in groups) {
                        val rows = withResults.filter { setOfEvent(it.event) == code }
                        val count = drafts.count { it.state.setCode == code }
                        StatsResultRow(code, rows.courseResults(), "$count drafts · ${rows.size} events with results")
                    }
                }
            }
        }
        item {
            Panel("By draft type", trailing = "Event totals") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = (courses.map { eventType(it.event) } + drafts.map { eventType(it.state.eventName.orEmpty()) }).distinct().sorted()
                    if (groups.isEmpty()) StatsEmpty("No drafts in this selection.")
                    for (name in groups) {
                        val rows = withResults.filter { eventType(it.event) == name }
                        val count = drafts.count { eventType(it.state.eventName.orEmpty()) == name }
                        StatsResultRow(name, rows.courseResults(), "$count drafts · ${rows.size} events with results")
                    }
                }
            }
        }
        item {
            Panel("Deck colors", trailing = "Saved decks") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = withResults.filter { it.colors != null }.groupBy { it.colors!! }
                    if (groups.isEmpty()) StatsEmpty("Color results appear when Arena logs a saved deck and its event record.")
                    for ((colors, rows) in groups.toSortedMap()) StatsResultRow(colors, rows.courseResults(), "${rows.size} events")
                }
            }
        }
        item {
            Panel("On the play / draw", trailing = "First game of each match") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsResultRow("On the play", matches.filter { it.onPlay == true }.results())
                    StatsResultRow("On the draw", matches.filter { it.onPlay == false }.results())
                    Text("${matches.count { it.onPlay == null }} matches have no recorded starting player. Match results are separate from event totals above.",
                        style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatsMetric(label: String, value: String) {
    Column(Modifier.widthIn(min = 125.dp).clip(RoundedCornerShape(10.dp)).background(AppTheme.colors.surface).padding(12.dp)) {
        Text(label, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        Spacer(Modifier.height(5.dp))
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
    }
}

@Composable
private fun StatsResultRow(label: String, results: ResultSummary, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AppTheme.colors.textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (results.played == 0) "Results unavailable" else "${results.wins}–${results.losses}${if (results.draws > 0) "–${results.draws}" else ""}   ${rate(results.winRate)}",
                color = AppTheme.colors.textSecondary, fontSize = 11.sp)
        }
        if (detail != null) Text(detail, style = AppTheme.typography.caption, color = AppTheme.colors.textMuted)
        results.winRate?.let { winRate ->
            Box(Modifier.fillMaxWidth().height(3.dp).background(AppTheme.colors.borderDefault)) {
                Box(Modifier.fillMaxWidth(winRate.toFloat().coerceIn(0f, 1f)).height(3.dp).background(AppTheme.colors.accent))
            }
        }
    }
}

@Composable
private fun MatchStats(matches: List<MatchRecord>) {
    val summary = matches.results()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Panel("Draft match record", trailing = "${matches.size} confirmed matches") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatsResultRow("Overall", summary)
                    Text("Each match is counted once, including best-of-three. Draws are shown separately and excluded from win rate.",
                        style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
                }
            }
        }
        if (matches.isEmpty()) item { StatsEmpty("No completed matches in the available logs for this selection.") }
        items(matches, key = { it.id }) { match ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(AppTheme.colors.surface).padding(11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(match.outcome, fontWeight = FontWeight.SemiBold,
                    color = when (match.outcome) { "Win" -> AppTheme.colors.positive; "Loss" -> AppTheme.colors.danger; else -> AppTheme.colors.textSecondary })
                Column(Modifier.weight(1f)) {
                    Text(eventType(match.event) + (setOfEvent(match.event)?.let { " · $it" } ?: ""), color = AppTheme.colors.textPrimary, fontSize = 12.sp)
                    Text(if (match.sourceUrl != null) "Untapped.gg · recovered" else match.event, color = AppTheme.colors.textMuted, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (match.draftDateOnly) "Draft " + match.at.take(10) else date(match.at), color = AppTheme.colors.textSecondary, fontSize = 10.sp)
                    Text(when (match.onPlay) { true -> "On the play"; false -> "On the draw"; null -> "Starting player unavailable" },
                        color = AppTheme.colors.textMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun RankStats(ranks: List<RankRecord>) {
    val rows = ranks.filter { it.ladder == "limited" }
    val latest = rows.firstOrNull()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Panel("Limited rank", trailing = latest?.let { "Season ${it.season}" }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(latest?.label ?: "Rank unavailable", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
                    if (latest != null) Text("Observed ${date(latest.at)}", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
                    Text("Arena’s Limited ladder is shared by Draft and Sealed. Draft-only results are shown in Overview and Matches.",
                        style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
                }
            }
        }
        if (rows.isEmpty()) item { StatsEmpty("Rank snapshots will appear when Arena reports your ladder rank.") }
        items(rows, key = { it.id }) { rank ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(rank.label, color = AppTheme.colors.textPrimary, fontSize = 12.sp)
                    Text("Season ${rank.season}", color = AppTheme.colors.textMuted, fontSize = 10.sp)
                }
                Text(date(rank.at), color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatsEmpty(message: String) = Text(message, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
private fun rate(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "—"
private fun date(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
