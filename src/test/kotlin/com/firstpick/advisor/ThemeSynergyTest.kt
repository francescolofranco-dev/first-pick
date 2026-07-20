package com.firstpick.advisor

import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SetSynergyProfile
import com.firstpick.cards.SynergyArchetype
import com.firstpick.cards.SynergyCombo
import com.firstpick.cards.SynergyIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeSynergyTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)
    private val engine = AdvisorEngine()

    private fun card(
        id: Int,
        name: String,
        gih: Double,
        color: String = "U",
        rarity: String = "common",
        iwd: Double? = 0.0,
    ) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(
            name = name, mtgaId = id, color = color, rarity = rarity,
            everDrawnWinRate = gih, everDrawnGameCount = 2000, drawnImprovementWinRate = iwd, avgSeen = 4.0,
        ),
    )

    private val index = SynergyIndex(
        SetSynergyProfile(
            set = "TST",
            archetypes = listOf(
                SynergyArchetype(
                    pair = "UG",
                    name = "Counters",
                    signposts = listOf("Signpost"),
                    payoffs = listOf("Payoff"),
                    enablers = listOf("Enabler1", "Enabler2", "Enabler3", "Enabler4"),
                    keyCards = listOf("KeyCard"),
                ),
            ),
            combos = listOf(SynergyCombo(listOf("ComboA", "ComboB"), note = "engine")),
        ),
    )

    private fun run(pack: List<RankedCard>, pool: List<RankedCard>, pick: Int = 6) =
        engine.score(
            pack, pool, packNumber = 1, pickNumber = pick, metrics = metrics,
            lane = LaneDetector.detect(pool, metrics),
            synergy = index,
        )

    private fun scoreOf(result: List<ScoredCard>, name: String) = result.first { it.card.name == name }

    @Test
    fun emptyPoolGivesNoThemeBonusAtP1P1() {
        val pack = listOf(card(1, "Payoff", 0.55), card(2, "Vanilla", 0.55))
        val result = run(pack, pool = emptyList(), pick = 1)
        assertEquals(0.0, scoreOf(result, "Payoff").breakdown!!.themeBonus)
    }

    @Test
    fun earlyBombMakesItsThemeSupportMatterSlightlyMoreWithoutPenalizingOtherCards() {
        val pack = listOf(card(1, "Enabler1", 0.55), card(2, "Vanilla", 0.55))
        val pool = listOf(card(100, "Payoff", 0.61, "G", rarity = "rare", iwd = 0.06))
        val lane = LaneDetector.detect(pool, metrics)
        val baseline = AdvisorEngine(AdvisorEngine.Config(earlyBombThemeFuelBonus = 0.0)).score(
            pack, pool, 1, 2, metrics, lane, synergy = index,
        )
        val reinforced = run(pack, pool, pick = 2)

        val baselineSupport = scoreOf(baseline, "Enabler1")
        val reinforcedSupport = scoreOf(reinforced, "Enabler1")
        assertTrue(reinforcedSupport.breakdown!!.themeBonus > baselineSupport.breakdown!!.themeBonus)
        assertTrue(reinforcedSupport.value > scoreOf(reinforced, "Vanilla").value)
        assertTrue(reinforcedSupport.reasons.any { it.startsWith("Synergy:") })
        assertEquals(
            scoreOf(baseline, "Vanilla").value,
            scoreOf(reinforced, "Vanilla").value,
            1e-9,
            "the early anchor may reward support, but must not penalize an unrelated card",
        )
    }

    @Test
    fun anEarlyRareThatIsNotABombDoesNotReceiveExtraThemeWeight() {
        val pack = listOf(card(1, "Enabler1", 0.55))
        val pool = listOf(card(100, "Payoff", 0.575, "G", rarity = "rare", iwd = 0.06))
        val lane = LaneDetector.detect(pool, metrics)
        val baseline = AdvisorEngine(AdvisorEngine.Config(earlyBombThemeFuelBonus = 0.0)).score(
            pack, pool, 1, 2, metrics, lane, synergy = index,
        )
        val scored = run(pack, pool, pick = 2)

        assertEquals(
            scoreOf(baseline, "Enabler1").breakdown!!.themeBonus,
            scoreOf(scored, "Enabler1").breakdown!!.themeBonus,
            1e-9,
        )
    }

    @Test
    fun bombAnchorBoostStopsAfterTheEarlyOpenLaneStage() {
        val pack = listOf(card(1, "Enabler1", 0.55))
        val pool = listOf(card(100, "Payoff", 0.61, "G", rarity = "rare", iwd = 0.06)) +
            List(4) { card(200 + it, "Filler$it", 0.56, "G") }
        val lane = LaneDetector.detect(pool, metrics)
        val baseline = AdvisorEngine(AdvisorEngine.Config(earlyBombThemeFuelBonus = 0.0)).score(
            pack, pool, 1, 6, metrics, lane, synergy = index,
        )
        val scored = run(pack, pool, pick = 6)

        assertTrue(lane.isEstablished)
        assertEquals(
            scoreOf(baseline, "Enabler1").breakdown!!.themeBonus,
            scoreOf(scored, "Enabler1").breakdown!!.themeBonus,
            1e-9,
        )
    }

    @Test
    fun payoffBonusScalesWithEnablersInPool() {
        val pack = listOf(card(1, "Payoff", 0.55))
        val fewEnablers = List(1) { card(100 + it, "Enabler${it + 1}", 0.56, "G") } +
            List(4) { card(200 + it, "Filler$it", 0.56, "G") }
        val manyEnablers = List(4) { card(100 + it, "Enabler${it + 1}", 0.56, "G") } +
            List(1) { card(200 + it, "Filler$it", 0.56, "G") }
        val few = scoreOf(run(pack, fewEnablers), "Payoff").breakdown!!.themeBonus
        val many = scoreOf(run(pack, manyEnablers), "Payoff").breakdown!!.themeBonus
        assertTrue(few > 0.0, "one enabler should already give a small bonus")
        assertTrue(many > few, "more enablers must increase the payoff's bonus (few=$few many=$many)")
    }

    @Test
    fun enablerIsRewardedWhenPoolHasPayoffs() {
        val pack = listOf(card(1, "Enabler1", 0.55), card(2, "Vanilla", 0.55))
        val pool = listOf(card(100, "Payoff", 0.56, "G"), card(101, "Signpost", 0.56, "UG")) +
            List(3) { card(200 + it, "Filler$it", 0.56, "G") }
        val result = run(pack, pool)
        assertTrue(scoreOf(result, "Enabler1").breakdown!!.themeBonus > 0.0)
        assertEquals(0.0, scoreOf(result, "Vanilla").breakdown!!.themeBonus)
        assertTrue(scoreOf(result, "Enabler1").value > scoreOf(result, "Vanilla").value)
    }

    @Test
    fun clearlyStrongerOffThemeCardStillWins() {

        val pack = listOf(card(1, "Payoff", 0.53), card(2, "StrongVanilla", 0.60))
        val pool = List(5) { card(100 + it, "Enabler${(it % 4) + 1}", 0.56, "G") }
        val result = run(pack, pool)
        assertEquals("StrongVanilla", result.first().card.name)
    }

    @Test
    fun synergyDecidesCloseCalls() {
        val pack = listOf(card(1, "Payoff", 0.552), card(2, "Vanilla", 0.555))
        val pool = List(5) { card(100 + it, "Enabler${(it % 4) + 1}", 0.56, "G") }
        val result = run(pack, pool)
        assertEquals("Payoff", result.first().card.name)
        assertTrue(scoreOf(result, "Payoff").reasons.any { it.startsWith("Synergy:") })
    }

    @Test
    fun comboPartnerInPoolGivesBonusAndReason() {
        val pack = listOf(card(1, "ComboA", 0.55))
        val pool = listOf(card(100, "ComboB", 0.56, "G")) + List(4) { card(200 + it, "Filler$it", 0.56, "G") }
        val scored = scoreOf(run(pack, pool), "ComboA")
        assertTrue(scored.breakdown!!.themeBonus > 0.0)
        assertTrue(scored.reasons.any { it.startsWith("Combo:") })
    }

    @Test
    fun themePlusStatisticalSynergyRespectsTheTotalCap() {
        val config = AdvisorEngine.Config()
        val pack = listOf(card(1, "Payoff", 0.56))
        val pool = List(10) { card(100 + it, "Enabler${(it % 4) + 1}", 0.56, "G") }
        val arch = CardRating(name = "Payoff", everDrawnWinRate = 0.66, everDrawnGameCount = 5000)
        val result = AdvisorEngine(config).score(
            pack, pool, 2, 5, metrics,
            lane = LaneDetector.detect(pool, metrics),
            archetypeRating = { name, _ -> if (name == "Payoff") arch else null },
            synergy = index,
        )
        val b = scoreOf(result, "Payoff").breakdown!!
        assertTrue(
            b.synergyBonus + b.themeBonus <= config.synergyTotalCapPts + 1e-9,
            "stacked synergy ${b.synergyBonus} + ${b.themeBonus} must respect the cap",
        )
    }

    @Test
    fun noProfileScoresExactlyAsBefore() {
        val pack = listOf(card(1, "Payoff", 0.57), card(2, "Vanilla", 0.55))
        val pool = List(5) { card(100 + it, "Enabler${(it % 4) + 1}", 0.56, "G") }
        val without = engine.score(pack, pool, 1, 6, metrics, LaneDetector.detect(pool, metrics))
        val with = run(pack, pool)
        assertEquals(0.0, without.first { it.card.name == "Payoff" }.breakdown!!.themeBonus)
        assertEquals(
            without.first { it.card.name == "Vanilla" }.value,
            with.first { it.card.name == "Vanilla" }.value,
            1e-9,
            "untagged cards must score identically with and without a profile",
        )
    }
}
