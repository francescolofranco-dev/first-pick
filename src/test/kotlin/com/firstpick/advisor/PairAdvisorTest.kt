package com.firstpick.advisor

import com.firstpick.cards.*
import kotlin.test.*

class PairAdvisorTest {
    private val metrics = SetMetrics(0.55, 0.03)
    private val engine = AdvisorEngine()
    private val lane = Lane(emptySet(), null, emptyMap())
    private fun card(id: Int, name: String, wr: Double = 0.56) = RankedCard(id, name,
        CardRating(name = name, mtgaId = id, color = "U", everDrawnWinRate = wr,
            everDrawnGameCount = 2000, avgSeen = 8.0))
    private val synergy = SynergyIndex(SetSynergyProfile(set = "TST", combos = listOf(
        SynergyCombo(listOf("Enabler", "Payoff")),
    )))
    private fun score(pack: List<RankedCard>, pool: List<RankedCard> = emptyList()) =
        engine.score(pack, pool, 1, 1, metrics, lane, synergy = synergy, picksPerChoice = 2)
    private fun pair(pack: List<RankedCard>): List<String> {
        val ranked = score(pack)
        val indices = PairAdvisor.recommend(ranked) { candidate, partner ->
            score(listOf(candidate), listOfNotNull(partner)).single()
        }
        return indices.map { ranked[it].card.name }
    }

    @Test fun complementaryCardsCanBeatTwoIndependentSinglePicks() {
        val pack = listOf(card(1, "Standalone", 0.561), card(2, "Enabler"), card(3, "Payoff"))
        assertEquals("Standalone", score(pack).first().card.name)
        assertEquals(setOf("Enabler", "Payoff"), pair(pack).toSet())
    }

    @Test fun synergyCannotDisplaceMuchStrongerCards() {
        assertEquals(setOf("Strong A", "Strong B"), pair(listOf(
            card(1, "Strong A", 0.64), card(2, "Strong B", 0.63),
            card(3, "Enabler"), card(4, "Payoff"),
        )).toSet())
    }

    @Test fun pairsUsePhysicalEntriesEvenForIdenticalCopies() {
        val duplicate = card(1, "Duplicate")
        val scored = score(listOf(duplicate, duplicate))
        assertEquals(setOf(0, 1), PairAdvisor.recommend(scored) { candidate, partner ->
            score(listOf(candidate), listOfNotNull(partner)).single()
        })
        assertEquals(listOf("Duplicate"), pair(listOf(duplicate)))
        assertTrue(pair(emptyList()).isEmpty())
    }

    @Test fun unsupportedCardsCannotBePromotedByAPartner() {
        val cards = listOf(card(1, "A"), card(2, "B"), card(3, "Unsupported"))
        val ranked = score(cards).map { if (it.card.name == "Unsupported") it.copy(
            value = 100.0, guardrail = PickGuardrail(status = ModelPromotionStatus.CONSTRAINED),
        ) else it }
        val chosen = PairAdvisor.recommend(ranked) { candidate, _ -> ranked.first { it.card == candidate } }
        assertEquals(setOf("A", "B"), chosen.map { ranked[it].card.name }.toSet())
    }

    @Test fun pickTwoDoesNotApplySinglePickWheelTiming() {
        val pack = listOf(card(1, "Card"))
        assertTrue(engine.score(pack, emptyList(), 1, 1, metrics, lane).single().breakdown!!.wheelPenalty < 0)
        assertTrue(score(pack).single().breakdown!!.wheelPenalty == 0.0)
    }
}
