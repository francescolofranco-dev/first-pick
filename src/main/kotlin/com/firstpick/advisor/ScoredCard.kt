package com.firstpick.advisor

import com.firstpick.cards.RankedCard

/** A pack card with the advisor's contextual evaluation. */
data class ScoredCard(
    val card: RankedCard,
    /** 0–100 contextual pick value. */
    val value: Double,
    /** Raw power as a set z-score (pre-context), for transparency. */
    val z: Double,
    val isBomb: Boolean,
    /** Short human-readable drivers, best first (e.g. "On-color", "Fills 2-drop need"). */
    val reasons: List<String>,
    /** Detailed breakdown of the scalar points contributing to the final value. */
    val breakdown: ValueBreakdown? = null,
)

data class ValueBreakdown(
    val baseScore: Double,
    val archetypeShift: Double,
    val synergyBonus: Double,
    val penalty: Double,
    val needsMultiplier: Double,
    val finalScore: Double
)
