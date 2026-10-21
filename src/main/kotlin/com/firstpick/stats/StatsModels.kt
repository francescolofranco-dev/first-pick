package com.firstpick.stats

import kotlinx.serialization.Serializable

sealed interface ActivityRecord { val id: String; val at: String }

@Serializable
data class CourseRecord(
    override val id: String, override val at: String, val event: String,
    val wins: Int? = null, val losses: Int? = null, val complete: Boolean = false,
    val pool: List<Int> = emptyList(), val deck: Map<Int, Int> = emptyMap(),
    val colors: String? = null, val draftComplete: Boolean = false,
    val sourceUrl: String? = null, val historyId: String? = null,
) : ActivityRecord

@Serializable
data class MatchRecord(
    override val id: String, override val at: String, val event: String,
    val courseId: String? = null, val outcome: String, val onPlay: Boolean? = null,
    val games: Int = 1, val sourceUrl: String? = null, val draftDateOnly: Boolean = false,
) : ActivityRecord

@Serializable
data class RankRecord(
    override val id: String, override val at: String, val ladder: String, val season: Int,
    val rank: String, val tier: Int, val wins: Int, val losses: Int, val draws: Int = 0,
) : ActivityRecord {
    val label: String get() = if (rank.equals("Mythic", true)) rank else "$rank $tier"
}

data class PlayerStats(
    val courses: List<CourseRecord> = emptyList(), val matches: List<MatchRecord> = emptyList(),
    val ranks: List<RankRecord> = emptyList(), val importing: Boolean = false,
    val error: String? = null,
)

fun eventType(event: String): String {
    val name = event.lowercase()
    return when {
        "picktwo" in name.replace("_", "") -> "Pick Two Draft"
        "sealed" in name -> "Sealed"
        "cube" in name -> "Cube Draft"
        "traditional" in name || "traddraft" in name -> if ("draft" in name) "Traditional Draft" else "Traditional Constructed"
        "quickdraft" in name || "bot_draft" in name || "botdraft" in name -> "Quick Draft"
        "draft" in name -> "Premier Draft"
        "ranked" in name -> "Ranked Constructed"
        "mwm" in name -> "Midweek Magic"
        else -> "Other Constructed"
    }
}

internal fun isDraftEvent(event: String): Boolean = event.lowercase().let {
    "sealed" !in it && ("draft" in it || "cube" in it)
}

/** Apply before filters and summaries, including records saved by older app versions. */
internal fun PlayerStats.draftOnly(): PlayerStats = copy(
    courses = courses.filter { isDraftEvent(it.event) },
    matches = matches.filter { isDraftEvent(it.event) },
    ranks = ranks.filter { it.ladder == "limited" },
)

internal fun setOfEvent(event: String): String? = com.firstpick.model.setCodeFromEventName(event)
    ?: Regex("(?:Draft|Sealed)_([A-Za-z0-9]{2,5})(?:_|$)", RegexOption.IGNORE_CASE)
        .find(event)?.groupValues?.get(1)?.uppercase()

data class ResultSummary(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0) {
    val played: Int get() = wins + losses + draws
    val winRate: Double? get() = (wins + losses).takeIf { it > 0 }?.let { wins.toDouble() / it }
}

internal fun List<MatchRecord>.results() = ResultSummary(count { it.outcome == "Win" }, count { it.outcome == "Loss" }, count { it.outcome == "Draw" })
internal fun List<CourseRecord>.courseResults() = ResultSummary(sumOf { it.wins ?: 0 }, sumOf { it.losses ?: 0 })

/** Terminal win target depends on the draft event, including Pick Two's four-win track. */
internal fun CourseRecord.isTrophy(): Boolean = complete && (wins ?: 0) >= when (eventType(event)) {
    "Traditional Draft" -> 3
    "Pick Two Draft" -> 4
    else -> 7
}

@Serializable
internal data class RecoveredDraftResults(
    val courses: List<CourseRecord> = emptyList(), val matches: List<MatchRecord> = emptyList(),
)
