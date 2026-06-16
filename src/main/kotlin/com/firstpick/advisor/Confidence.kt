package com.firstpick.advisor

/** How clearly the top pick stands out from the rest of the pack. */
enum class ConfidenceLevel { CLEAR, LEAN, TOSS_UP }

data class PickConfidence(val level: ConfidenceLevel, val gap: Double, val contenders: Int)

/**
 * Turns the spread of pack VALUEs into a confidence read. When the top picks are
 * within noise of each other the advisor should admit it ("toss-up — your call")
 * rather than imply false precision.
 */
object Confidence {
    const val TOSS_UP_GAP = 3.0
    const val LEAN_GAP = 7.0

    fun of(values: List<Double>): PickConfidence {
        if (values.size < 2) return PickConfidence(ConfidenceLevel.CLEAR, Double.POSITIVE_INFINITY, values.size)
        val sorted = values.sortedDescending()
        val gap = sorted[0] - sorted[1]
        val contenders = sorted.count { it >= sorted[0] - TOSS_UP_GAP }
        val level = when {
            gap < TOSS_UP_GAP -> ConfidenceLevel.TOSS_UP
            gap < LEAN_GAP -> ConfidenceLevel.LEAN
            else -> ConfidenceLevel.CLEAR
        }
        return PickConfidence(level, gap, contenders)
    }
}
