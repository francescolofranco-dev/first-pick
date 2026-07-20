package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SetSynergyProfile
import com.firstpick.cards.SynergyArchetype
import com.firstpick.cards.SynergyIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckAnalysisTest {
    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(id: Int, name: String, gih: Double = 0.55, games: Int = 2_000) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = if (id % 2 == 0) "W" else "U", everDrawnWinRate = gih, everDrawnGameCount = games),
    )

    @Test
    fun recognizesTempoFromEvasiveThreatsAndCheapInteraction() {
        val metas = mutableMapOf<String, CardMeta>()
        val spells = buildList {
            repeat(6) {
                val name = "Early$it"
                add(card(it, name))
                metas[name] = CardMeta(name, cmc = 2, isCreature = true, isLand = false, isEvasion = it < 5)
            }
            repeat(7) {
                val name = "Threat$it"
                add(card(20 + it, name))
                metas[name] = CardMeta(name, cmc = 3, isCreature = true, isLand = false)
            }
            repeat(5) {
                val name = "Interaction$it"
                add(card(40 + it, name))
                metas[name] = CardMeta(name, cmc = 2, isCreature = false, isLand = false, isRemoval = true)
            }
            repeat(5) {
                val name = "Spell$it"
                add(card(60 + it, name))
                metas[name] = CardMeta(name, cmc = 3, isCreature = false, isLand = false)
            }
        }

        val identity = DeckAnalysis.identity(spells, "WU", { metas[it] }, synergy = null)

        assertEquals(DeckPace.TEMPO, identity.pace)
        assertTrue(identity.reasons.any { "evasive" in it })
        assertTrue(identity.reasons.any { "interaction" in it })
    }

    @Test
    fun recognizesControlFromAnswersDrawAndTopEnd() {
        val metas = mutableMapOf<String, CardMeta>()
        val spells = buildList {
            repeat(2) {
                val name = "Blocker$it"
                add(card(it, name))
                metas[name] = CardMeta(name, cmc = 2, isCreature = true, isLand = false)
            }
            repeat(6) {
                val name = "Finisher$it"
                add(card(20 + it, name))
                metas[name] = CardMeta(name, cmc = 5, isCreature = true, isLand = false, isFinisher = true)
            }
            repeat(7) {
                val name = "Answer$it"
                add(card(40 + it, name))
                metas[name] = CardMeta(name, cmc = 3, isCreature = false, isLand = false, isRemoval = true)
            }
            repeat(5) {
                val name = "Draw$it"
                add(card(60 + it, name))
                metas[name] = CardMeta(name, cmc = 4, isCreature = false, isLand = false, isCardDraw = true)
            }
            repeat(3) {
                val name = "Spell$it"
                add(card(80 + it, name))
                metas[name] = CardMeta(name, cmc = 3, isCreature = false, isLand = false)
            }
        }

        val identity = DeckAnalysis.identity(spells, "UB", { metas[it] }, synergy = null)

        assertEquals(DeckPace.CONTROL, identity.pace)
        assertTrue(identity.reasons.any { "removal" in it && "draw" in it })
    }

    @Test
    fun clearDeckEvidenceCanOverrideAConflictingSetPrior() {
        val metas = mutableMapOf<String, CardMeta>()
        val spells = buildList {
            repeat(8) {
                val name = "ControlCreature$it"
                add(card(it, name))
                metas[name] = CardMeta(name, cmc = if (it < 2) 2 else 5, isCreature = true, isLand = false, isFinisher = it >= 2)
            }
            repeat(8) {
                val name = "Removal$it"
                add(card(20 + it, name))
                metas[name] = CardMeta(name, cmc = 3, isCreature = false, isLand = false, isRemoval = true)
            }
            repeat(7) {
                val name = "Draw$it"
                add(card(40 + it, name))
                metas[name] = CardMeta(name, cmc = 4, isCreature = false, isLand = false, isCardDraw = true)
            }
        }
        val synergy = SynergyIndex(
            SetSynergyProfile(
                set = "TST",
                archetypes = listOf(SynergyArchetype(pair = "UB", name = "Fast aggro", speed = "aggro")),
            ),
        )

        assertEquals(DeckPace.CONTROL, DeckAnalysis.identity(spells, "UB", { metas[it] }, synergy).pace)
    }

    @Test
    fun reliablePairPerformanceImprovesPowerMoreThanTinySamples() {
        val spells = (0 until 23).map { card(it, "Card$it") }
        val meta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }
        val identity = DeckAnalysis.identity(spells, "WU", meta, synergy = null)
        fun pairRating(games: Int): (String, String) -> CardRating? = { name, _ ->
            CardRating(name = name, everDrawnWinRate = 0.61, everDrawnGameCount = games)
        }

        val baseline = DeckAnalysis.power(spells, "WU", metrics, meta, { _, _ -> null }, null, identity, null, 0, 0)
        val tiny = DeckAnalysis.power(spells, "WU", metrics, meta, pairRating(10), null, identity, null, 0, 0)
        val reliable = DeckAnalysis.power(spells, "WU", metrics, meta, pairRating(10_000), null, identity, null, 0, 0)

        assertTrue(tiny.score > baseline.score)
        assertTrue(tiny.score < baseline.score + 2.0, "ten games should be almost entirely shrunk: ${tiny.score} vs ${baseline.score}")
        assertTrue(reliable.score > tiny.score + 15.0, "reliable pair data should materially move the estimate")
        assertTrue(reliable.confidence > tiny.confidence)
    }

    @Test
    fun unsupportedSplashAndMissingSpellsLowerTheScoreWithReasons() {
        val complete = (0 until 23).map { card(it, "Card$it", gih = 0.57) }
        val short = complete.take(20)
        val meta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }
        val completeIdentity = DeckAnalysis.identity(complete, "WU", meta, synergy = null)
        val shortIdentity = DeckAnalysis.identity(short, "WU", meta, synergy = null)
        val completePower = DeckAnalysis.power(complete, "WU", metrics, meta, { _, _ -> null }, null, completeIdentity, null, 3, 3)
        val shortPower = DeckAnalysis.power(short, "WU", metrics, meta, { _, _ -> null }, null, shortIdentity, null, 0, 3)

        assertTrue(completePower.score >= shortPower.score + 10.0)
        assertTrue(shortPower.reasons.any { "spell slots short" in it })
        assertTrue(shortPower.reasons.any { "splash cards lack fixing" in it })
    }
}
