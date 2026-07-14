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
    fun nonbasicLandIsJudgedByProducedColorsNotItsBlankColor() {


        val pool = List(6) { card(100 + it, "Guy$it", 0.58, if (it % 2 == 0) "B" else "G") }
        val meta: (String) -> CardMeta? = { name ->
            when (name) {
                "GolgariDual" -> CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('B', 'G'))
                "SelesnyaDual" -> CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('G', 'W'))
                "SwampTap" -> CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('B'))
                "RainbowLand" -> CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('W', 'U', 'B', 'R', 'G'))
                else -> null
            }
        }
        val pack = listOf(
            card(1, "GolgariDual", 0.57, color = ""),
            card(2, "SelesnyaDual", 0.57, color = ""),
            card(3, "SwampTap", 0.57, color = ""),
            card(4, "RainbowLand", 0.57, color = ""),
        )
        val result = run(pack, pool, packNumber = 2, pickNumber = 7, meta = meta)
        val golgari = scoreOf(result, "GolgariDual")
        val selesnya = scoreOf(result, "SelesnyaDual")
        assertTrue(golgari.value > selesnya.value, "on-lane dual must beat the wrong dual")
        assertTrue(selesnya.reasons.any { it.contains("Off-color fixing") }, "wrong dual should be flagged: ${selesnya.reasons}")
        assertEquals(0.0, golgari.breakdown!!.penalty, 0.001, "a B/G dual is not penalized in BG")
        assertEquals(0.0, scoreOf(result, "SwampTap").breakdown!!.penalty, 0.001, "on-color tapland is fine")
        assertEquals(0.0, scoreOf(result, "RainbowLand").breakdown!!.penalty, 0.001, "a land that makes both your colors is premium fixing")
    }

    @Test
    fun fixingLandForACommittedSplashIsNotPenalized() {


        val meta: (String) -> CardMeta? = { name ->
            when (name) {
                "OrzhovDual" -> CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('W', 'B'))
                else -> null
            }
        }
        val pool = List(8) { card(100 + it, "Guy$it", 0.58, if (it % 2 == 0) "B" else "G") } +
            listOf(card(200, "WhiteA", 0.57, "W"), card(201, "WhiteB", 0.57, "W"))
        val pack = listOf(card(1, "OrzhovDual", 0.57, color = ""))
        val result = run(pack, pool, packNumber = 3, pickNumber = 4, meta = meta)
        val land = scoreOf(result, "OrzhovDual")
        assertEquals(0.0, land.breakdown!!.penalty, 0.001, "a W/B land that fixes main + splash should not be penalized")
        assertTrue(land.reasons.any { it.contains("splash") }, "should note it fixes the splash: ${land.reasons}")
    }

    @Test
    fun fixingLandForAColorYouAreNotPlayingIsStillPenalized() {

        val meta: (String) -> CardMeta? = { name ->
            if (name == "OrzhovDual") CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = setOf('W', 'B')) else null
        }
        val pool = List(6) { card(100 + it, "Guy$it", 0.58, if (it % 2 == 0) "B" else "G") }
        val pack = listOf(card(1, "OrzhovDual", 0.57, color = ""))
        val result = run(pack, pool, packNumber = 3, pickNumber = 4, meta = meta)
        val land = scoreOf(result, "OrzhovDual")
        assertTrue(land.breakdown!!.penalty < 0.0, "an unused off-color pip should still be penalized: ${land.breakdown}")
    }

    @Test
    fun extraCopiesOfTheSameSpellLoseValueButAFreshEqualCardDoesNot() {
        val meta: (String) -> CardMeta? = { name ->
            when {
                name.startsWith("Removal") -> CardMeta(name, cmc = 3, isCreature = false, isLand = false, isRemoval = true)
                else -> CardMeta(name, cmc = 3, isCreature = true, isLand = false)
            }
        }

        val pool = List(3) { card(200, "RemovalA", 0.57, "U") } + List(4) { card(100 + it, "Guy$it", 0.57, "U") }
        val pack = listOf(card(1, "RemovalA", 0.57, "U"), card(2, "RemovalB", 0.57, "U"))
        val result = run(pack, pool, packNumber = 3, pickNumber = 1, meta = meta)
        val fourth = scoreOf(result, "RemovalA")
        val fresh = scoreOf(result, "RemovalB")
        assertTrue(fresh.value > fourth.value, "a 4th copy must rank below a fresh equal card (${fresh.value} vs ${fourth.value})")
        assertTrue(fourth.reasons.any { it.contains("copy") }, "the redundant copy should be flagged: ${fourth.reasons}")
        assertTrue(fourth.breakdown!!.duplicatePenalty < 0.0)
        assertEquals(0.0, fresh.breakdown!!.duplicatePenalty, 0.001)
    }

    @Test
    fun twoCopiesOfARemovalSpellAreNotPenalized() {
        val meta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = false, isLand = false, isRemoval = true) }
        val pool = List(1) { card(200, "RemovalA", 0.57, "U") } + List(5) { card(100 + it, "Guy$it", 0.57, "U") }
        val pack = listOf(card(1, "RemovalA", 0.57, "U"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = meta)
        assertEquals(0.0, scoreOf(result, "RemovalA").breakdown!!.duplicatePenalty, 0.001, "the 2nd copy of removal is fine")
    }

    @Test
    fun duplicateCreaturesArePenalizedLessThanSpells() {
        val spellMeta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = false, isLand = false) }
        val creatureMeta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }
        val pool = List(3) { card(200, "Dup", 0.57, "U") } + List(4) { card(100 + it, "Guy$it", 0.57, "U") }
        val pack = listOf(card(1, "Dup", 0.57, "U"))
        val asSpell = scoreOf(run(pack, pool, 3, 1, meta = spellMeta), "Dup").breakdown!!.duplicatePenalty
        val asCreature = scoreOf(run(pack, pool, 3, 1, meta = creatureMeta), "Dup").breakdown!!.duplicatePenalty
        assertTrue(asCreature > asSpell, "a 4th creature is penalized less than a 4th spell ($asCreature vs $asSpell)")
        assertTrue(asCreature < 0.0)
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

    @Test
    fun breakdownComponentsSumToTheFinalScore() {


        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(
            card(1, "Insane", 0.82, "U", iwd = 0.10),
            card(2, "Wheeler", 0.54, "U", alsa = 9.0),
            card(3, "OffColor", 0.60, "R"),
            card(4, "Terrible", 0.30, "R"),
        )
        val result = run(pack, pool, packNumber = 3, pickNumber = 10)
        for (s in result) {
            val b = s.breakdown!!
            val sum = b.baseScore + b.archetypeShift + b.synergyBonus + b.themeBonus +
                b.penalty + b.needsPoints + b.deckFitPoints + b.wheelPenalty + b.duplicatePenalty + b.scoreCap + b.modelShift
            assertEquals(b.finalScore, sum, 1e-6, "${s.card.name}: rows sum to $sum but finalScore is ${b.finalScore}")
            assertEquals(s.value, b.finalScore, 1e-9, "${s.card.name}: finalScore must equal the displayed value")
        }

        assertTrue(scoreOf(result, "Wheeler").breakdown!!.wheelPenalty < 0.0, "wheel penalty should fire")
        assertTrue(scoreOf(result, "Insane").breakdown!!.scoreCap < 0.0, "top-end clamp should book a negative scoreCap")
    }


    private val creatureMeta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }

    private fun committedPool() = List(10) { card(100 + it, "BlueGuy$it", 0.58, "U") } +
        List(5) { card(200 + it, "WhiteGuy$it", 0.58, "W") }

    @Test
    fun deckFitBreaksATieTowardTheCardThatMakesTheDeck() {
        val pool = committedPool()
        val pack = listOf(card(1, "Filler", 0.57, "U"), card(2, "Upgrade", 0.57, "U"))
        val fit: (RankedCard) -> DeckProjector.Fit? = { c ->
            if (c.name == "Upgrade") DeckProjector.Fit(true, listOf("BlueGuy0"), false, null, powerDelta = 2.0)
            else DeckProjector.Fit(false, emptyList(), false, null, 0.0)
        }
        val result = engine.score(
            pack, pool, packNumber = 2, pickNumber = 5, metrics = metrics,
            lane = LaneDetector.detect(pool, metrics), meta = creatureMeta, deckFit = fit,
        )
        assertEquals("Upgrade", result.first().card.name)
        assertTrue("Makes your deck" in scoreOf(result, "Upgrade").reasons)
        assertEquals(4.0, scoreOf(result, "Upgrade").breakdown!!.deckFitPoints, 0.01)
        assertEquals(0.0, scoreOf(result, "Filler").breakdown!!.deckFitPoints, 0.01)
        assertTrue(scoreOf(result, "Filler").reasons.none { it == "Makes your deck" })
    }

    @Test
    fun deckFitIsNeverConsultedWhileTheLaneIsOpen() {
        var called = false
        val fit: (RankedCard) -> DeckProjector.Fit? = { _ ->
            called = true
            DeckProjector.Fit(true, emptyList(), false, null, 3.0)
        }
        val pack = listOf(card(1, "A", 0.57, "U"), card(2, "B", 0.57, "W"))
        val result = engine.score(
            pack, pool = listOf(card(100, "OnlyCard", 0.58, "U")), packNumber = 1, pickNumber = 2,
            metrics = metrics, lane = LaneDetector.detect(listOf(card(100, "OnlyCard", 0.58, "U")), metrics),
            meta = creatureMeta, deckFit = fit,
        )
        assertTrue(!called, "P1 early picks are pure power — the projector must not even run")
        assertEquals(0.0, result.first().breakdown!!.deckFitPoints, 1e-9)
    }

    @Test
    fun deckFitNamesTheSplashItOpens() {
        val pool = committedPool()
        val pack = listOf(card(1, "SplashBomb", 0.62, "B"))
        val fit: (RankedCard) -> DeckProjector.Fit? = { _ ->
            DeckProjector.Fit(true, listOf("WhiteGuy4"), false, splashAdded = 'B', powerDelta = 1.5)
        }
        val result = engine.score(
            pack, pool, packNumber = 2, pickNumber = 5, metrics = metrics,
            lane = LaneDetector.detect(pool, metrics), meta = creatureMeta, deckFit = fit,
        )
        assertTrue("Worth a B splash" in result.first().reasons, "got ${result.first().reasons}")
    }

    @Test
    fun deckFitBonusIsCapped() {
        val pool = committedPool()
        val pack = listOf(card(1, "MegaBomb", 0.70, "U", iwd = 0.08))
        val fit: (RankedCard) -> DeckProjector.Fit? = { _ ->
            DeckProjector.Fit(true, emptyList(), false, null, powerDelta = 50.0)
        }
        val result = engine.score(
            pack, pool, packNumber = 3, pickNumber = 10, metrics = metrics,
            lane = LaneDetector.detect(pool, metrics), meta = creatureMeta, deckFit = fit,
        )
        assertEquals(AdvisorEngine.Config().fitCapPts, result.first().breakdown!!.deckFitPoints, 1e-9)
    }
}
