package com.firstpick.cards

import kotlin.math.sqrt

/**
 * Set-wide distribution of GIH win rates, used to turn a card's raw win rate
 * into a z-score (standard deviations above/below the format average) — the
 * base "power" signal for the advisor.
 */
data class SetMetrics(val meanGihWr: Double, val stdDevGihWr: Double) {

    /** z-score of a win rate, or null if the rate is missing. */
    fun z(gihWr: Double?): Double? {
        if (gihWr == null) return null
        if (stdDevGihWr <= 1e-9) return 0.0
        return (gihWr - meanGihWr) / stdDevGihWr
    }

    companion object {
        /** Reasonable limited-format defaults when a set has too little data. */
        val EMPTY = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

        fun from(ratings: List<CardRating>): SetMetrics {
            val wrs = ratings.filter { it.hasReliableWinRate }.mapNotNull { it.gihWr }
            if (wrs.size < 2) return EMPTY
            val mean = wrs.average()
            val variance = wrs.sumOf { (it - mean) * (it - mean) } / wrs.size
            val sd = sqrt(variance)
            return SetMetrics(mean, if (sd <= 1e-9) EMPTY.stdDevGihWr else sd)
        }
    }
}
