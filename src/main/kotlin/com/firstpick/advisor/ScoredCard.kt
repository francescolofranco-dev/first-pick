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
    /** 1-based rank in the learned pick model's order for this pack; null when the model didn't run. */
    val modelRank: Int? = null,
)

/**
 * Every additive term that produces the displayed grade, so the tooltip rows sum to finalScore
 * exactly. baseScore + archetypeShift + synergyBonus + themeBonus + penalty + needsPoints +
 * deckFitPoints + wheelPenalty + duplicatePenalty = the raw score; scoreCap books the 0..100
 * clamp and modelShift books the learned model's rank transplant, giving finalScore.
 */
data class ValueBreakdown(
    val baseScore: Double,
    val archetypeShift: Double,
    val synergyBonus: Double,
    val penalty: Double,
    val needsPoints: Double,
    val finalScore: Double,
    val themeBonus: Double = 0.0,
    val duplicatePenalty: Double = 0.0,
    /** Constructive deck-fit: the candidate earns a slot in the projected best deck. */
    val deckFitPoints: Double = 0.0,
    /** Discount for a card likely to wheel back (subtracted from the raw score). */
    val wheelPenalty: Double = 0.0,
    /** Adjustment from clamping the raw score into 0..100 (nonzero only at the extremes). */
    val scoreCap: Double = 0.0,
    /** How much the learned pick model moved this card's displayed grade off its heuristic value. */
    val modelShift: Double = 0.0,
)
