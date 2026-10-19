package com.firstpick.advisor

import com.firstpick.cards.*
import kotlin.test.*

class HaymakerAssessmentTest {
    private val metrics = SetMetrics(0.55, 0.03)
    private fun card(wr: Double? = 0.65, iwd: Double? = null, games: Int = 2000, name: String = "Candidate") =
        RankedCard(1, name, CardRating(
            name = name, everDrawnWinRate = wr, drawnImprovementWinRate = iwd, everDrawnGameCount = games,
        ))
    private fun meta(cmc: Int = 5) = CardMeta("Candidate", cmc, false, false)
    private fun reason(meta: CardMeta, candidate: RankedCard = card()) =
        HaymakerAssessment.reason(candidate, metrics, meta)

    @Test
    fun everySetUsesTheSameEffectAndRelativePerformanceRules() {
        val impact = meta().copy(isFinisher = true)
        assertNotNull(reason(impact))
        assertNotNull(reason(impact, card(name = "An unrelated future-set card")))
        assertNull(HaymakerAssessment.reason(card(), SetMetrics(0.64, 0.03), impact))
    }

    @Test
    fun creaturesPlaneswalkersAndSweepersCanQualify() {
        assertNotNull(reason(meta().copy(isCreature = true, isFinisher = true)))
        assertTrue(reason(meta().copy(isPlaneswalker = true, isRemoval = true))!!.contains("planeswalker"))
        assertTrue(reason(meta().copy(isMassRemoval = true))!!.contains("board-clearing"))
        assertNull(reason(meta().copy(isPlaneswalker = true)))
    }

    @Test
    fun highCostOrdinaryBodiesAndSingleTargetRemovalDoNotQualify() {
        assertNull(reason(meta(8).copy(isCreature = true)))
        assertNull(reason(meta().copy(isRemoval = true)))
        assertNull(reason(meta().copy(isLand = true, isCardDraw = true)))
    }

    @Test
    fun cheapExceptionsNeedStrongerResultsAndRepeatableAdvantage() {
        val cheap = meta(3).copy(isRepeatableAdvantage = true)
        assertNull(reason(cheap)) // Missing improvement data.
        assertNull(reason(cheap, card(0.61, 0.07))) // Good but not exceptional enough for a cheap card.
        assertNotNull(reason(cheap, card(0.65, 0.07)))
        assertNull(reason(cheap.copy(isRepeatableAdvantage = false, isCardDraw = true), card(0.65, 0.07)))
    }

    @Test
    fun unknownOrWeakStatisticsCannotInventHaymakers() {
        val impact = meta().copy(isMassRemoval = true)
        assertNull(reason(impact, card(wr = null)))
        assertNull(reason(impact, card(games = 50)))
        assertNull(reason(impact, card(wr = 0.54)))
        assertNull(reason(impact, card(iwd = 0.01)))
        assertNull(HaymakerAssessment.reason(card(), metrics, null))
    }

    @Test
    fun metadataRecognizesGeneralEffectsWithoutCardNames() {
        assertTrue(ScryfallClient.isMassRemoval("Destroy all creatures."))
        assertTrue(ScryfallClient.isMassRemoval("Exile all creatures."))
        assertTrue(ScryfallClient.isMassRemoval("This spell deals 5 damage to each creature."))
        assertFalse(ScryfallClient.isMassRemoval("Destroy target creature."))
        assertFalse(ScryfallClient.isMassRemoval("Choose a creature type. Destroy all creatures that aren't of the chosen type."))
        assertFalse(ScryfallClient.isMassRemoval("If you control an artifact, destroy all creatures."))
        assertTrue(ScryfallClient.isRepeatableAdvantage("At the beginning of your end step, draw a card."))
        assertFalse(ScryfallClient.isRepeatableAdvantage("When this creature enters, draw a card."))
    }
}
