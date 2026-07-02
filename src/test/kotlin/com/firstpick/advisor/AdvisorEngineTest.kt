package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvisorEngineTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)
    private val engine = AdvisorEngine()

    private fun card(
        id: Int,
        name: String,
        gih: Double?,
        color: String = "",
        iwd: Double? = 0.0,
        alsa: Double? = 4.0,
        rarity: String = "common",
        games: Int = 2000,
    ) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(
            name = name,
            mtgaId = id,
            color = color,
            rarity = rarity,
            everDrawnWinRate = gih,
            everDrawnGameCount = games,
            drawnImprovementWinRate = iwd,
            avgSeen = alsa,
        ),
    )

    private fun run(
        pack: List<RankedCard>,
        pool: List<RankedCard>,
        packNumber: Int,
        pickNumber: Int,
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        meta: (String) -> CardMeta? = { null },
    ) = engine.score(
        pack, pool, packNumber, pickNumber, metrics,
        lane = LaneDetector.detect(pool, metrics),
        archetypeRating = archetypeRating,
        meta = meta,
    )

    private fun scoreOf(result: List<ScoredCard>, name: String) = result.first { it.card.name == name }

    @Test
    fun higherWinRateScoresHigherWithEmptyPool() {
        val pack = listOf(card(1, "Strong", 0.61, "U"), card(2, "Weak", 0.49, "U"))
        val result = run(pack, pool = emptyList(), packNumber = 1, pickNumber = 1)
        assertEquals("Strong", result.first().card.name)
        assertTrue(scoreOf(result, "Strong").value > scoreOf(result, "Weak").value)
    }

    @Test
    fun bombIsFlaggedAndImmuneToOffColorPenalty() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(
            card(1, "RedBomb", 0.64, "R", iwd = 0.06),
            card(2, "BlueFiller", 0.555, "U"),
        )
        val result = run(pack, pool, packNumber = 3, pickNumber = 4)
        assertTrue(scoreOf(result, "RedBomb").isBomb)
        assertTrue("Bomb" in scoreOf(result, "RedBomb").reasons)
        assertEquals("RedBomb", result.first().card.name)
    }

    @Test
    fun offColorIsPenalizedLateWhenNotABomb() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(card(1, "OnColor", 0.57, "U"), card(2, "OffColor", 0.575, "R"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5)
        assertTrue(scoreOf(result, "OnColor").value > scoreOf(result, "OffColor").value)
        assertTrue(scoreOf(result, "OffColor").reasons.any { it.startsWith("Off-color") })
        assertFalse(scoreOf(result, "OffColor").isBomb)
    }

    @Test
    fun hybridManaCardIsNotOffColorPenalizedWhenOneHybridColorMatchesTheLane() {
        // Reproduces the reported bug: a {4}{U/W} card scored an off-color penalty in a
        // Simic (UG) lane, even though the hybrid pip is fully payable with U alone.
        val pool = List(6) { card(100 + it, "SimicGuy$it", 0.58, if (it % 2 == 0) "U" else "G") }
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name == "Seedpod Squire") CardMeta("Seedpod Squire", cmc = 5, isCreature = true, isLand = false, hybridColorGroups = listOf(setOf('U', 'W')))
            else null
        }
        val pack = listOf(card(1, "Seedpod Squire", 0.56, "UW"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = hybridMeta)
        val scored = scoreOf(result, "Seedpod Squire")
        assertTrue("On-color" in scored.reasons, "expected On-color, got ${scored.reasons}")
        assertFalse(scored.reasons.any { it.startsWith("Off-color") })
        assertEquals(0.0, scored.breakdown!!.penalty, 0.001)
    }

    @Test
    fun hybridManaCardIsStillPenalizedWhenNeitherHybridColorMatchesTheLane() {
        val pool = List(6) { card(100 + it, "BlackGuy$it", 0.58, "B") }
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name == "Seedpod Squire") CardMeta("Seedpod Squire", cmc = 5, isCreature = true, isLand = false, hybridColorGroups = listOf(setOf('U', 'W')))
            else null
        }
        val pack = listOf(card(1, "Seedpod Squire", 0.56, "UW"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = hybridMeta)
        val scored = scoreOf(result, "Seedpod Squire")
        assertTrue(scored.reasons.any { it.startsWith("Off-color") }, "expected an off-color penalty, got ${scored.reasons}")
    }

    @Test
    fun colorlessCardsAreNeverPenalized() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(card(1, "Artifact", 0.55, color = ""))
        val result = run(pack, pool, packNumber = 3, pickNumber = 8)
        assertEquals(50.0, scoreOf(result, "Artifact").value, 0.5)
    }

    @Test
    fun curveBoostsCheapCreatureWhenPoolLacksTwoDrops() {
        val meta: (String) -> CardMeta? = { name ->
            when (name) {
                "TwoDrop" -> CardMeta("TwoDrop", cmc = 2, isCreature = true, isLand = false)
                "FiveDrop" -> CardMeta("FiveDrop", cmc = 5, isCreature = true, isLand = false)
                else -> null
            }
        }
        val pack = listOf(card(1, "TwoDrop", 0.55, "U"), card(2, "FiveDrop", 0.55, "U"))
        val result = run(pack, emptyList(), packNumber = 2, pickNumber = 1, meta = meta)
        assertTrue(scoreOf(result, "TwoDrop").value > scoreOf(result, "FiveDrop").value)
        assertTrue("Fills 2-drop need" in scoreOf(result, "TwoDrop").reasons)
    }

    @Test
    fun lateAlsaCardsAreFlaggedAsWheelers() {
        val pack = listOf(card(1, "LateCard", 0.55, "U", alsa = 8.5))
        val result = run(pack, emptyList(), packNumber = 1, pickNumber = 1)
        assertTrue("Likely to wheel" in scoreOf(result, "LateCard").reasons)
    }

    @Test
    fun archetypeSynergyRaisesACardThatOverperformsInTheLane() {
        val pool = List(6) { card(100 + it, "Pick$it", 0.58, if (it % 2 == 0) "U" else "B") }
        val glue = card(1, "Glue", 0.55, "U", rarity = "common")
        val plain = card(2, "Plain", 0.55, "U", rarity = "common")
        val archetypeRating: (String, String) -> CardRating? = { name, pair ->
            if (name == "Glue" && pair == "UB") {
                CardRating(name = "Glue", everDrawnWinRate = 0.60, everDrawnGameCount = 1500)
            } else {
                null
            }
        }
        val result = run(listOf(glue, plain), pool, packNumber = 2, pickNumber = 6, archetypeRating = archetypeRating)
        assertTrue(scoreOf(result, "Glue").value > scoreOf(result, "Plain").value)
        assertTrue(scoreOf(result, "Glue").reasons.any { it.contains("synergy", true) || it.contains("glue", true) })
    }

    @Test
    fun deckNeedsCarryLittleWeightEarlyButMoreLate() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val killMeta: (String) -> CardMeta? = { name ->
            if (name == "Kill") CardMeta("Kill", cmc = 3, isCreature = false, isLand = false, isRemoval = true) else null
        }
        val pack = listOf(card(1, "Kill", 0.54, "U"))
        val early = scoreOf(run(pack, pool, packNumber = 1, pickNumber = 1, meta = killMeta), "Kill").value
        val late = scoreOf(run(pack, pool, packNumber = 3, pickNumber = 6, meta = killMeta), "Kill").value
        assertTrue(late > early + 5.0, "needed removal should be worth more late ($late) than early ($early)")
    }

    @Test
    fun valueStaysWithinBounds() {
        val pack = listOf(card(1, "Insane", 0.80, "U", iwd = 0.10), card(2, "Terrible", 0.30, "R"))
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val result = run(pack, pool, packNumber = 3, pickNumber = 10)
        result.forEach { assertTrue(it.value in 0.0..100.0, "${it.card.name} value ${it.value} out of bounds") }
    }
}
