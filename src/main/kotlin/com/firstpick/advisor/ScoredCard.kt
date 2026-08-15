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

    /** Typed policy result consumed by learned re-rankers; never inferred from display reasons. */
    val guardrail: PickGuardrail = PickGuardrail.OPEN,
)


enum class ModelPromotionStatus {
    /** The lane is not established, so the model remains free to find a better direction. */
    FLEXIBLE,

    /** The card is castable in the established lane, including useful fixing and colorless cards. */
    ON_PLAN,

    /** A light, impactful off-color bomb worth considering before a complete mana projection exists. */
    SPLASH_CANDIDATE,

    /** The source-validated deck projection confirms that the off-color card improves the build. */
    SUPPORTED_SPLASH,

    /** The card remains visible, but a learned score may not promote it over a viable card. */
    CONSTRAINED,
}


enum class GuideConstraint {
    UNKNOWN_MANA,
    UNSUPPORTED_SPLASH,
    MULTIPLE_SPLASH_COLORS,
    HEAVY_SPLASH_PIPS,
    OFF_PLAN_FIXING,
}


data class PickGuardrail(
    val status: ModelPromotionStatus,
    val laneColors: Set<Char> = emptySet(),
    val offColors: Set<Char> = emptySet(),
    val constraints: Set<GuideConstraint> = emptySet(),
) {
    val modelPromotable: Boolean get() = status != ModelPromotionStatus.CONSTRAINED

    companion object {
        val OPEN = PickGuardrail(ModelPromotionStatus.FLEXIBLE)
    }
}


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
