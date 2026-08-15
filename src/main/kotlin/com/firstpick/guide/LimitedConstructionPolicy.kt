package com.firstpick.guide

import com.firstpick.model.DraftFormat

/** The construction context is explicit so Sealed never silently uses Draft heuristics. */
enum class LimitedMode {
    DRAFT,
    SEALED;

    companion object {
        fun fromFormat(format: DraftFormat): LimitedMode =
            if (format == DraftFormat.SEALED) SEALED else DRAFT
    }
}

/**
 * Typed deck-construction rules shared by the guide and the projection engine.
 *
 * Source targets follow Frank Karsten's 40-card tables:
 * https://www.tcgplayer.com/content/article/how-many-sources-do-you-need-to-consistently-cast-your-spells-a-2022-update/dc23a7d2-0a16-4c0b-ad36-586fcca03ad8
 * Practical splash discipline is also described by Wizards:
 * https://magic.wizards.com/en/news/feature/making-splash-2017-04-26
 */
data class DeckConstructionPolicy(
    val mode: LimitedMode,
    val deckSize: Int,
    val landSlots: Int,
    val minimumBuildableSpells: Int,
    val creatureTarget: Int,
    val removalTarget: Int,
    val nonCreatureCap: Int,
    val creatureCurve: Map<Int, Int>,
    val removalMinimumZ: Double,
    val minimumBaseColorPips: Int,
    val minimumBaseColorRatio: Double,
    val minimumBaseSources: Int,
    val baseSourceRelaxation: Int,
    val maximumSplashCards: Int,
    val minimumSplashSources: Int,
    /** How many turns later than printed mana value a splash is allowed to come online. */
    val splashCastingDelay: Int,
    val splashUpgradeMargin: Double,
    val removalSelectionBonus: Double,
    val finisherSelectionBonus: Double,
    val stallBreakerSelectionBonus: Double,
    val meanQualityWeight: Double,
    val topQualityWeight: Double,
    val floorQualityWeight: Double,
    val manaShortfallPenalty: Double,
    val reliableManaBonus: Double,
    val finisherTarget: Int,
    val stallBreakerTarget: Int,
) {
    val spellSlots: Int get() = deckSize - landSlots
}

object LimitedDeckPolicies {
    val DRAFT = DeckConstructionPolicy(
        mode = LimitedMode.DRAFT,
        deckSize = 40,
        landSlots = 17,
        minimumBuildableSpells = 21,
        creatureTarget = 15,
        removalTarget = 4,
        nonCreatureCap = 8,
        creatureCurve = linkedMapOf(2 to 4, 3 to 4, 4 to 3, 5 to 2, 6 to 1, 1 to 1),
        removalMinimumZ = -0.75,
        minimumBaseColorPips = 4,
        minimumBaseColorRatio = 0.20,
        minimumBaseSources = 6,
        // Draft projections tolerate a modest source risk so existing pick-time
        // behavior remains flexible. Sealed below requires full source targets.
        baseSourceRelaxation = 2,
        maximumSplashCards = 4,
        minimumSplashSources = 3,
        splashCastingDelay = 3,
        splashUpgradeMargin = 0.02,
        removalSelectionBonus = 0.0,
        finisherSelectionBonus = 0.0,
        stallBreakerSelectionBonus = 0.0,
        meanQualityWeight = 0.72,
        topQualityWeight = 0.18,
        floorQualityWeight = 0.10,
        manaShortfallPenalty = 0.0,
        reliableManaBonus = 0.0,
        finisherTarget = 2,
        stallBreakerTarget = 3,
    )

    val SEALED = DeckConstructionPolicy(
        mode = LimitedMode.SEALED,
        deckSize = 40,
        landSlots = 17,
        minimumBuildableSpells = 21,
        creatureTarget = 14,
        removalTarget = 5,
        nonCreatureCap = 9,
        creatureCurve = linkedMapOf(2 to 4, 3 to 4, 4 to 3, 5 to 2, 6 to 1, 1 to 0),
        removalMinimumZ = -0.90,
        minimumBaseColorPips = 5,
        minimumBaseColorRatio = 0.23,
        minimumBaseSources = 8,
        baseSourceRelaxation = 0,
        maximumSplashCards = 2,
        minimumSplashSources = 3,
        splashCastingDelay = 2,
        splashUpgradeMargin = 0.03,
        removalSelectionBonus = 0.010,
        finisherSelectionBonus = 0.008,
        stallBreakerSelectionBonus = 0.004,
        // Sealed pools are less synergy-dense, so their best bombs and answers
        // matter more than a small improvement to the average playable.
        meanQualityWeight = 0.62,
        topQualityWeight = 0.28,
        floorQualityWeight = 0.10,
        manaShortfallPenalty = 3.0,
        reliableManaBonus = 2.0,
        finisherTarget = 2,
        stallBreakerTarget = 4,
    )

    fun forMode(mode: LimitedMode): DeckConstructionPolicy = when (mode) {
        LimitedMode.DRAFT -> DRAFT
        LimitedMode.SEALED -> SEALED
    }
}
