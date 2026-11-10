package com.firstpick.ui

import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.stats.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Copy counts matter: a build can include one copy and leave another in the pool. */
internal fun closeCuts(pool: List<DeckSpellUi>, deck: DeckOptionUi): List<DeckSpellUi> {
    val used = (deck.spells + deck.lands).groupBy { it.name }.mapValues { (_, rows) -> rows.sumOf { it.count } }
    return pool.groupBy { it.name }.mapNotNull { (name, rows) ->
        val count = rows.sumOf { it.count } - (used[name] ?: 0)
        rows.first().copy(count = count).takeIf { count > 0 && !it.isBasicLand }
    }.sortedWith(compareByDescending<DeckSpellUi> { it.gihWr ?: Double.NEGATIVE_INFINITY }.then(deckSpellOrder)).take(8)
}

internal data class AnalyticsSelection(
    val set: String? = null,
    val format: String? = null,
    val month: YearMonth? = null,
    val last30Days: Boolean = false,
) {
    fun includes(at: String, event: String, setCode: String? = setOfEvent(event), now: Instant = Instant.now()): Boolean {
        val instant = runCatching { Instant.parse(at) }.getOrNull() ?: return false
        return (set == null || set == setCode) && (format == null || format == eventType(event)) &&
            (month == null || YearMonth.from(instant.atZone(ZoneId.systemDefault())) == month) &&
            (!last30Days || instant >= now.minusSeconds(30L * 86400) && instant <= now)
    }
}

/** In-progress or incomplete totals must never masquerade as final draft finishes. */
internal fun finishDistribution(courses: List<CourseRecord>): List<Int> = (0..7).map { wins ->
    courses.count { isDraftEvent(it.event) && it.complete && it.wins == wins && it.losses != null }
}

internal fun courseForDraft(entry: DraftHistoryEntry, courses: List<CourseRecord>): CourseRecord? =
    courses.firstOrNull { it.historyId == entry.id } ?: entry.state.courseId?.let { id -> courses.firstOrNull { it.id == id } }
