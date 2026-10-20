package com.firstpick.advisor

import com.firstpick.cards.*
import kotlin.test.*

class OpeningDraftPolicyTest {
    private val metrics = SetMetrics(0.55, 0.03)
    private val engine = AdvisorEngine()
    private fun card(name: String, wr: Double = 0.56, color: String = "U") = RankedCard(
        name.hashCode(), name, CardRating(name = name, color = color,
            everDrawnWinRate = wr, everDrawnGameCount = 2000, avgSeen = 4.0),
    )
    private val first = card("First", 0.62)
    private val synergy = SynergyIndex(SetSynergyProfile(set = "TST", combos = listOf(
        SynergyCombo(listOf("First", "Partner")),
    )))
    private fun score(pool: List<RankedCard>, pack: List<RankedCard>) = engine.score(
        pack, pool, 1, pool.size + 1, metrics, LaneDetector.detect(pool, metrics), synergy = synergy,
    )

    @Test fun actualSynergyCanBreakACloseOpeningComparison() {
        for (count in 1..4) {
            val pool = listOf(first) + List(count - 1) { card("Other $it") }
            val picks = score(pool, listOf(card("Independent", 0.562), card("Partner")))
            assertEquals("Partner", picks.first().card.name, "Opening pick ${count + 1}")
            assertTrue(picks.first().breakdown!!.themeBonus > 0)
        }
    }

    @Test fun anOpeningSynergyDoesNotJustifyALargeQualityLoss() {
        val picks = score(listOf(first), listOf(card("Independent", 0.60), card("Partner")))
        assertEquals("Independent", picks.first().card.name)
        assertFalse(picks.last().openingQualityEligible)
    }

    @Test fun matchingColorAloneIsNotSynergyInTheOpening() {
        val picks = score(listOf(first), listOf(card("Same color", 0.56), card("Stronger", 0.565, "R")))
        assertEquals("Stronger", picks.first().card.name)
        assertTrue(picks.all { it.breakdown!!.penalty == 0.0 && it.breakdown.themeBonus == 0.0 })
    }

    @Test fun repeatedColorsInfluencePickSixButNotPickFive() {
        val pool = listOf(card("U1"), card("U2"), card("W1", color = "W"), card("W2", color = "W"))
        val pack = listOf(card("Off", 0.57, "R"), card("On", 0.565))
        val fifth = score(pool, pack)
        assertEquals("Off", fifth.first().card.name)
        assertTrue(fifth.all { it.guardrail == PickGuardrail.OPEN })
        val sixth = score(pool + card("U3"), pack)
        assertEquals("On", sixth.first().card.name)
        assertTrue(sixth.first().guardrail.modelPromotable)
        assertFalse(sixth.last().guardrail.modelPromotable)
    }

    @Test fun fiveScatteredPicksDoNotForceAColorPair() {
        val pool = "WUBRG".map { card("Single $it", color = it.toString()) }
        val lane = LaneDetector.detect(pool, metrics)
        assertNull(lane.pair)
        assertTrue(lane.colors.isEmpty())
        val picks = score(pool, listOf(card("Strong", 0.59, "B"), card("Weak", 0.55, "U")))
        assertEquals("Strong", picks.first().card.name)
        assertTrue(picks.all { it.guardrail == PickGuardrail.OPEN })
    }

    @Test fun manyOpeningCombosStillHaveABoundedBonus() {
        val pool = (1..4).map { card("Fuel $it") }
        val index = SynergyIndex(SetSynergyProfile(set = "TST", combos = pool.map {
            SynergyCombo(listOf(it.name, "Payoff"))
        }))
        val result = engine.score(listOf(card("Payoff")), pool, 1, 5, metrics,
            LaneDetector.detect(pool, metrics), synergy = index).single()
        assertEquals(3.0, result.breakdown!!.themeBonus)
    }
}
