package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.guide.LimitedMode

data class DeckOption(
    val colors: String,
    val basePair: String,
    val splash: Char?,
    val theme: String? = null,
    val powerScore: Double,
    val tier: String,
    val type: String,
    val outlook: String,
    val deckWinRate: Double,
    val identityConfidence: String,
    val identityReasons: List<String>,
    val powerConfidence: String,
    val powerReasons: List<String>,
    val spells: List<RankedCard>,
    val nonbasicLands: List<RankedCard>,
    val landCount: Int,
    val creatures: Int,
    val removal: Int,
    val curve: List<Pair<String, Int>>,
    val manaSources: ManaSourceReport? = null,
    val constructionMode: LimitedMode = LimitedMode.DRAFT,
)


object DeckBuilder {
    fun build(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
        synergy: SynergyIndex? = null,
        mode: LimitedMode = LimitedMode.DRAFT,
    ): List<DeckOption> = DeckProjector.projectAll(pool, metrics, meta, archetypeRating, pairStrength, maxOptions, synergy, mode)
}
