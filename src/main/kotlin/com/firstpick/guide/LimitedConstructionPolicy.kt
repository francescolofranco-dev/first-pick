package com.firstpick.guide

/**
 * Typed deck-construction rules shared by the guide and the projection engine.
 *
 * Source targets follow Frank Karsten's 40-card tables:
 * https://www.tcgplayer.com/content/article/how-many-sources-do-you-need-to-consistently-cast-your-spells-a-2022-update/dc23a7d2-0a16-4c0b-ad36-586fcca03ad8
 * Practical splash discipline is also described by Wizards:
 * https://magic.wizards.com/en/news/feature/making-splash-2017-04-26
 */
data class DeckConstructionPolicy(
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
    val meanQualityWeight: Double,
    val topQualityWeight: Double,
    val floorQualityWeight: Double,
    val manaShortfallPenalty: Double,
    val reliableManaBonus: Double,
) {
    val spellSlots: Int get() = deckSize - landSlots
}

object LimitedDeckPolicies {
    val DRAFT = DeckConstructionPolicy(
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
        minimumBaseSources = 8,
        baseSourceRelaxation = 0,
        maximumSplashCards = 4,
        minimumSplashSources = 3,
        splashCastingDelay = 3,
        splashUpgradeMargin = 0.02,
        meanQualityWeight = 0.72,
        topQualityWeight = 0.18,
        floorQualityWeight = 0.10,
        manaShortfallPenalty = 3.0,
        reliableManaBonus = 2.0,
    )

}
