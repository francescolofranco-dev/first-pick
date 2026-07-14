package com.firstpick.advisor

import com.firstpick.cards.RankedCard

data class ScoredCard(
    val card: RankedCard,
    val value: Double,
    val z: Double,
    val isBomb: Boolean,
    val reasons: List<String>,
    val breakdown: ValueBreakdown? = null,
    val rawValue: Double = value,

    val modelRank: Int? = null,
)


data class ValueBreakdown(
    val baseScore: Double,
    val archetypeShift: Double,
    val synergyBonus: Double,
    val penalty: Double,
    val needsPoints: Double,
    val finalScore: Double,
    val themeBonus: Double = 0.0,
    val duplicatePenalty: Double = 0.0,

    val deckFitPoints: Double = 0.0,

    val wheelPenalty: Double = 0.0,

    val scoreCap: Double = 0.0,

    val modelShift: Double = 0.0,
)
