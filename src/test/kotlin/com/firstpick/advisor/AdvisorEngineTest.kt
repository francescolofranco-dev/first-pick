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
        activeManaSources: ManaSourceReport? = null,
    ) = engine.score(
        pack, pool, packNumber, pickNumber, metrics,
        lane = LaneDetector.detect(pool, metrics),
        archetypeRating = archetypeRating,
        meta = meta,
        activeManaSources = activeManaSources,
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
        val pool = List(6) { card(100 + it, "DimirGuy$it", 0.58, if (it % 2 == 0) "U" else "B") }
        val pack = listOf(
            card(1, "RedBomb", 0.64, "R", iwd = 0.06),
            card(2, "BlueFiller", 0.555, "U"),
        )
        val result = run(pack, pool, packNumber = 3, pickNumber = 4, meta = creatureMeta)
        assertTrue(scoreOf(result, "RedBomb").isBomb)
        assertTrue("Bomb" in scoreOf(result, "RedBomb").reasons)
        assertEquals("RedBomb", result.first().card.name)
    }

    @Test
    fun offColorIsPenalizedLateWhenNotABomb() {
        val pool = List(6) { card(100 + it, "DimirGuy$it", 0.58, if (it % 2 == 0) "U" else "B") }
        val pack = listOf(card(1, "OnColor", 0.57, "U"), card(2, "OffColor", 0.575, "R"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5)
        assertTrue(scoreOf(result, "OnColor").value > scoreOf(result, "OffColor").value)
        assertTrue(scoreOf(result, "OffColor").reasons.any { it.startsWith("Off-color") })
        assertFalse(scoreOf(result, "OffColor").isBomb)
    }

    @Test
    fun monoColorPoolKeepsTheSecondColorOpen() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(card(1, "BlueCard", 0.57, "U"), card(2, "RedCandidate", 0.575, "R"))

        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = { name ->
            CardMeta(
                name = name,
                cmc = 2,
                isCreature = true,
                isLand = false,
                coloredPips = mapOf((if (name == "RedCandidate") 'R' else 'U') to 1),
            )
        })

        assertEquals("RedCandidate", result.first().card.name)
        assertFalse(scoreOf(result, "RedCandidate").reasons.any { it.startsWith("Off-color") })
    }

    @Test
    fun monoColorPoolConstrainsACardThatNeedsTwoNewColors() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(
            card(1, "BlueCard", 0.57, "U"),
            card(2, "GruulCandidate", 0.59, "RG"),
        )
        val meta: (String) -> CardMeta? = { name ->
            CardMeta(
                name = name,
                cmc = 3,
                isCreature = true,
                isLand = false,
                coloredPips = when (name) {
                    "GruulCandidate" -> mapOf('R' to 1, 'G' to 1)
                    else -> mapOf('U' to 1)
                },
            )
        }

        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = meta)
        val candidate = scoreOf(result, "GruulCandidate")

        assertEquals(ModelPromotionStatus.CONSTRAINED, candidate.guardrail.status)
        assertEquals(setOf('R', 'G'), candidate.guardrail.offColors)
        assertTrue(GuideConstraint.MULTIPLE_SPLASH_COLORS in candidate.guardrail.constraints)
        assertTrue(candidate.reasons.any { it == "Off-color (U base)" })
    }

    @Test
    fun monoColorPoolConstrainsAHeavySecondColorCost() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val pack = listOf(card(1, "RedCommitment", 0.59, "R"))
        val result = run(pack, pool, packNumber = 2, pickNumber = 5, meta = { name ->
            CardMeta(
                name = name,
                cmc = 4,
                isCreature = true,
                isLand = false,
                coloredPips = mapOf('R' to 3),
            )
        })
        val candidate = result.single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, candidate.guardrail.status)
        assertEquals(setOf('R'), candidate.guardrail.offColors)
        assertTrue(GuideConstraint.HEAVY_SPLASH_PIPS in candidate.guardrail.constraints)
    }

    @Test
    fun monoColorPoolConstrainsFixingForTwoUnrelatedColors() {
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val land = card(1, "GolgariLand", 0.59, "")
        val result = run(
            pack = listOf(land),
            pool = pool,
            packNumber = 2,
            pickNumber = 5,
            meta = { name ->
                CardMeta(
                    name = name,
                    cmc = 0,
                    isCreature = false,
                    isLand = true,
                    isFixing = true,
                    producedColors = setOf('B', 'G'),
                )
            },
        )
        val candidate = result.single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, candidate.guardrail.status)
        assertEquals(setOf('B', 'G'), candidate.guardrail.offColors)
        assertTrue(GuideConstraint.OFF_PLAN_FIXING in candidate.guardrail.constraints)
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
        val pool = List(6) { card(100 + it, "GolgariGuy$it", 0.58, if (it % 2 == 0) "B" else "G") }
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
    fun overlappingHybridSymbolCannotHideAMandatoryPurePip() {
        val pool = List(6) { card(100 + it, "AzoriusGuy$it", 0.58, if (it % 2 == 0) "W" else "U") }
        val candidate = card(1, "BlackHybridSpell", 0.56, "BR")
        val result = run(
            listOf(candidate),
            pool,
            packNumber = 2,
            pickNumber = 5,
            meta = {
                if (it == candidate.name) CardMeta(
                    it, 4, false, false,
                    coloredPips = mapOf('B' to 1),
                    hybridColorGroups = listOf(setOf('B', 'R')),
                    hybridPips = listOf(setOf('B', 'R')),
                ) else null
            },
        ).single()

        assertEquals(setOf('B'), result.guardrail.offColors)
        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertTrue(GuideConstraint.HEAVY_SPLASH_PIPS in result.guardrail.constraints)
        assertTrue(result.reasons.any { it.startsWith("Off-color") })
    }

    @Test
    fun offBaseHybridBombIsASingleColorSplashCandidate() {
        val pool = List(6) { card(100 + it, "AzoriusGuy$it", 0.58, if (it % 2 == 0) "W" else "U") }
        val candidate = card(1, "FlexibleBomb", 0.66, "BR", iwd = 0.07)
        val result = run(
            listOf(candidate),
            pool,
            packNumber = 2,
            pickNumber = 5,
            meta = {
                if (it == candidate.name) CardMeta(
                    it, 6, true, false, isFinisher = true,
                    hybridColorGroups = listOf(setOf('B', 'R')),
                    hybridPips = listOf(setOf('B', 'R')),
                ) else null
            },
        ).single()

        assertEquals(ModelPromotionStatus.SPLASH_CANDIDATE, result.guardrail.status)
        assertEquals(1, result.guardrail.offColors.size)
        assertFalse(GuideConstraint.MULTIPLE_SPLASH_COLORS in result.guardrail.constraints)
    }

    @Test
    fun effectiveDoubleHybridPipsAreNeverALightSpeculativeSplash() {
        val pool = List(6) { card(100 + it, "AzoriusGuy$it", 0.58, if (it % 2 == 0) "W" else "U") }
        val candidate = card(1, "DoubleHybridBomb", 0.70, "BR", iwd = 0.08)
        val result = run(
            listOf(candidate),
            pool,
            packNumber = 2,
            pickNumber = 5,
            meta = {
                if (it == candidate.name) CardMeta(
                    it, 6, true, false, isFinisher = true,
                    hybridColorGroups = listOf(setOf('B', 'R')),
                    hybridPips = listOf(setOf('B', 'R'), setOf('B', 'R')),
                ) else null
            },
        ).single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertTrue(GuideConstraint.HEAVY_SPLASH_PIPS in result.guardrail.constraints)
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
        val activeMana = ManaSourceReport(
            requiredSources = mapOf('B' to 7, 'G' to 7, 'W' to 4),
            basicSources = mapOf('B' to 7, 'G' to 6, 'W' to 2),
            nonbasicSources = mapOf('B' to 1, 'G' to 1, 'W' to 2),
            fixerSources = mapOf('B' to 0, 'G' to 0, 'W' to 0),
            totalSources = mapOf('B' to 8, 'G' to 7, 'W' to 4),
            shortfalls = mapOf('B' to 0, 'G' to 0, 'W' to 0),
            baseColors = setOf('B', 'G'),
            splashColor = 'W',
        )
        val result = run(pack, pool, packNumber = 3, pickNumber = 4, meta = meta, activeManaSources = activeMana)
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

    private fun supportedBlackSplash() = ManaSourceReport(
        requiredSources = mapOf('W' to 7, 'U' to 7, 'B' to 4),
        basicSources = mapOf('W' to 7, 'U' to 6, 'B' to 2),
        nonbasicSources = mapOf('W' to 1, 'U' to 1, 'B' to 2),
        fixerSources = mapOf('W' to 0, 'U' to 0, 'B' to 0),
        totalSources = mapOf('W' to 8, 'U' to 7, 'B' to 4),
        shortfalls = mapOf('W' to 0, 'U' to 0, 'B' to 0),
        baseColors = setOf('W', 'U'),
        splashColor = 'B',
    )

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
    fun deckFitCanEvaluateAMatureMonoColorBase() {
        var called = false
        val fit: (RankedCard) -> DeckProjector.Fit? = { _ ->
            called = true
            DeckProjector.Fit(true, emptyList(), false, null, 1.0)
        }
        val pool = List(6) { card(100 + it, "BlueGuy$it", 0.58, "U") }
        val lane = LaneDetector.detect(pool, metrics)

        engine.score(
            pack = listOf(card(1, "Candidate", 0.57, "R")),
            pool = pool,
            packNumber = 2,
            pickNumber = 5,
            metrics = metrics,
            lane = lane,
            meta = creatureMeta,
            deckFit = fit,
        )

        assertTrue(lane.hasBaseColorEvidence)
        assertFalse(lane.isEstablished)
        assertTrue(called, "a mature mono-color base should use deck projection to evaluate its second color")
    }

    @Test
    fun deckFitNamesTheSplashItOpens() {
        val pool = committedPool()
        val pack = listOf(card(1, "SplashBomb", 0.62, "B"))
        val fit: (RankedCard) -> DeckProjector.Fit? = { _ ->
            DeckProjector.Fit(
                true,
                listOf("WhiteGuy4"),
                false,
                splashAdded = 'B',
                powerDelta = 1.5,
                afterBasePair = "WU",
                afterSplash = 'B',
                afterManaSources = supportedBlackSplash(),
            )
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

    @Test
    fun modelGuardrailKeepsTheDraftFlexibleBeforeALaneIsEstablished() {
        val pool = listOf(card(100, "FirstPick", 0.58, "U"))
        val result = run(
            pack = listOf(card(1, "RedOption", 0.57, "R")),
            pool = pool,
            packNumber = 1,
            pickNumber = 2,
        )

        assertEquals(ModelPromotionStatus.FLEXIBLE, result.single().guardrail.status)
        assertTrue(result.single().guardrail.modelPromotable)
    }

    @Test
    fun openingFRAChoicesStayFlexibleDespiteATentativeBlackGreenPair() {
        val pool = listOf(
            card(100, "Hapatra, the Desert Fang", 0.60, "BG"),
            card(101, "Hapatra, the Desert Fang", 0.60, "BG"),
            card(102, "No Admittance", 0.52, "R"),
            card(103, "Fulminous Forte", 0.52, "R"),
        )
        val pack = listOf(
            card(1, "Variable Chaser", 0.60, "U"),
            card(2, "Primal Witchstalker", 0.55, "BG"),
            card(3, "Last Gasp", 0.54, "B"),
            RankedCard(grpId = 4, name = "Unresolved option", rating = null),
        )
        val meta: (String) -> CardMeta? = { name ->
            val pips = when (name) {
                "Hapatra, the Desert Fang", "Primal Witchstalker" -> mapOf('B' to 1, 'G' to 1)
                "Variable Chaser" -> mapOf('U' to 1)
                "Last Gasp" -> mapOf('B' to 1)
                "No Admittance", "Fulminous Forte" -> mapOf('R' to 1)
                else -> null
            }
            pips?.let { CardMeta(name, 3, true, false, coloredPips = it) }
        }
        val lane = LaneDetector.detect(pool, metrics, meta = meta)
        assertEquals("BG", lane.pair, "the pool can suggest BG without committing the next pick")
        val theme = SynergyIndex(SetSynergyProfile(
            set = "FRA",
            archetypes = listOf(SynergyArchetype(
                pair = "UR", name = "Prowess", payoffs = listOf("Variable Chaser"),
                enablers = listOf("No Admittance", "Fulminous Forte"),
            )),
        ))
        var archetypeQueries = 0
        val result = engine.score(
            pack, pool, packNumber = 1, pickNumber = 5, metrics = metrics, lane = lane,
            archetypeRating = { name, _ ->
                archetypeQueries++
                CardRating(name = name, everDrawnWinRate = 0.70, everDrawnGameCount = 2000)
            },
            meta = meta,
            synergy = theme,
        )

        assertEquals("Variable Chaser", result.first().card.name)
        assertEquals("BG", lane.pair, "scoring must preserve the tentative lane used by the UI")
        assertEquals(0, archetypeQueries, "an opening pick must not use tentative pair statistics")
        assertTrue(scoreOf(result, "Variable Chaser").breakdown!!.themeBonus > 0.0,
            "the tentative BG pair must not suppress useful UR theme evidence")
        assertTrue(result.zipWithNext().all { (a, b) -> a.value >= b.value })
        for (score in result) {
            assertEquals(PickGuardrail.OPEN, score.guardrail)
            assertEquals(0.0, score.breakdown!!.penalty, 1e-9)
            assertEquals(0.0, score.breakdown!!.archetypeShift)
            assertEquals(0.0, score.breakdown!!.synergyBonus)
            assertEquals(0.0, score.breakdown!!.guideAdjustment)
            assertTrue(score.reasons.none {
                it == "On-color" || it.startsWith("Off-color") || it.startsWith("Unsupported") ||
                    it.startsWith("Heavy") || it == "Mana requirements unavailable"
            }, "opening option ${score.card.name} must remain flexible: ${score.reasons}")
        }
    }

    @Test
    fun colorConstraintsStartAtTheConfiguredCommitmentBoundary() {
        val config = AdvisorEngine.Config(totalPicks = 40, penaltyRampStart = 0.15)
        val advisor = AdvisorEngine(config)
        val pool = List(6) { card(100 + it, "Base$it", 0.58, if (it % 2 == 0) "W" else "U") }
        val lane = LaneDetector.detect(pool, metrics)
        val pack = listOf(card(1, "BlackOption", 0.60, "B"), card(2, "WhiteOption", 0.54, "W"))
        val meta: (String) -> CardMeta? = { name ->
            CardMeta(name, 3, true, false, coloredPips = mapOf((if (name == "BlackOption") 'B' else 'W') to 1))
        }
        fun score(pick: Int) = advisor.score(pack, pool, 1, pick, metrics, lane, meta = meta)

        val before = score(6) // Five picks taken: progress 0.125.
        assertEquals("BlackOption", before.first().card.name)
        assertTrue(before.all { it.guardrail == PickGuardrail.OPEN })
        assertEquals(0.0, scoreOf(before, "BlackOption").breakdown!!.penalty, 1e-9)
        assertEquals(0.0, scoreOf(before, "BlackOption").breakdown!!.guideAdjustment)

        val boundary = score(7) // Six picks taken: progress exactly 0.15.
        val constrained = scoreOf(boundary, "BlackOption")
        assertEquals("WhiteOption", boundary.first().card.name)
        assertEquals(ModelPromotionStatus.CONSTRAINED, constrained.guardrail.status)
        assertEquals(0.0, constrained.breakdown!!.penalty, 1e-9)
        assertTrue(constrained.breakdown!!.guideAdjustment < 0.0,
            "hard feasibility starts at commitment even before the gradual color penalty grows")
        assertEquals(ModelPromotionStatus.ON_PLAN, boundary.first().guardrail.status)

        val after = score(8)
        assertEquals("WhiteOption", after.first().card.name)
        assertEquals(ModelPromotionStatus.CONSTRAINED, scoreOf(after, "BlackOption").guardrail.status)
        assertTrue(scoreOf(after, "BlackOption").breakdown!!.penalty < 0.0)
    }

    @Test
    fun modelGuardrailConstrainsUnsupportedOffColorCardInEstablishedLane() {
        val result = run(
            pack = listOf(card(1, "BlackFiller", 0.57, "B")),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 5,
            meta = creatureMeta,
        ).single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertEquals(setOf('B'), result.guardrail.offColors)
        assertTrue(GuideConstraint.UNSUPPORTED_SPLASH in result.guardrail.constraints)
        assertFalse(result.guardrail.modelPromotable)
    }

    @Test
    fun modelGuardrailAllowsUsefulLaneFixingButConstrainsIrrelevantFixing() {
        val fixingMeta: (String) -> CardMeta? = { name ->
            when (name) {
                "AzoriusDual" -> CardMeta(name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U'))
                "GolgariDual" -> CardMeta(name, 0, false, true, isFixing = true, producedColors = setOf('B', 'G'))
                else -> creatureMeta(name)
            }
        }
        val result = run(
            pack = listOf(
                card(1, "AzoriusDual", 0.57),
                card(2, "GolgariDual", 0.58),
            ),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 5,
            meta = fixingMeta,
        )

        assertEquals(ModelPromotionStatus.ON_PLAN, scoreOf(result, "AzoriusDual").guardrail.status)
        val irrelevant = scoreOf(result, "GolgariDual").guardrail
        assertEquals(ModelPromotionStatus.CONSTRAINED, irrelevant.status)
        assertTrue(GuideConstraint.OFF_PLAN_FIXING in irrelevant.constraints)
    }

    @Test
    fun modelGuardrailKeepsLightBombAsSplashCandidateButRejectsHeavySplashBomb() {
        val splashMeta: (String) -> CardMeta? = { name ->
            CardMeta(
                name = name,
                cmc = 5,
                isCreature = true,
                isLand = false,
                heavyPipColors = if (name == "HeavyBomb") setOf('B') else emptySet(),
            )
        }
        val result = run(
            pack = listOf(
                card(1, "LightBomb", 0.64, "B", iwd = 0.06),
                card(2, "HeavyBomb", 0.64, "B", iwd = 0.06),
            ),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 5,
            meta = splashMeta,
        )

        val light = scoreOf(result, "LightBomb").guardrail
        assertEquals(ModelPromotionStatus.SPLASH_CANDIDATE, light.status)
        assertTrue(light.modelPromotable)

        val heavy = scoreOf(result, "HeavyBomb")
        assertEquals(ModelPromotionStatus.CONSTRAINED, heavy.guardrail.status)
        assertTrue(GuideConstraint.HEAVY_SPLASH_PIPS in heavy.guardrail.constraints)
        assertTrue(heavy.reasons.any { it == "Heavy B splash" }, "got ${heavy.reasons}")
    }

    @Test
    fun modelGuardrailAllowsPositiveProjectedSplashUpgrade() {
        val pool = committedPool()
        val candidate = card(1, "BlackUpgrade", 0.57, "B")
        val result = engine.score(
            pack = listOf(candidate),
            pool = pool,
            packNumber = 2,
            pickNumber = 5,
            metrics = metrics,
            lane = LaneDetector.detect(pool, metrics),
            meta = creatureMeta,
            deckFit = {
                DeckProjector.Fit(
                    makesDeck = true,
                    displaced = listOf("WhiteGuy4"),
                    baseShifted = false,
                    splashAdded = 'B',
                    powerDelta = 1.0,
                    afterBasePair = "WU",
                    afterSplash = 'B',
                    afterManaSources = supportedBlackSplash(),
                )
            },
        ).single()

        assertEquals(ModelPromotionStatus.SUPPORTED_SPLASH, result.guardrail.status)
        assertTrue(result.guardrail.modelPromotable)
    }

    @Test
    fun projectedUpgradeMustMatchTheLaneSplashAndCarryCompleteSourceEvidence() {
        val pool = committedPool()
        val candidate = card(1, "BlackUpgrade", 0.57, "B")

        fun status(fit: DeckProjector.Fit): ModelPromotionStatus = engine.score(
            pack = listOf(candidate),
            pool = pool,
            packNumber = 3,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(pool, metrics),
            meta = creatureMeta,
            deckFit = { fit },
        ).single().guardrail.status

        val base = DeckProjector.Fit(
            makesDeck = true,
            displaced = listOf("WhiteGuy4"),
            baseShifted = false,
            splashAdded = 'B',
            powerDelta = 1.0,
            afterBasePair = "WU",
            afterSplash = 'B',
            afterManaSources = supportedBlackSplash(),
        )
        assertEquals(ModelPromotionStatus.CONSTRAINED, status(base.copy(afterBasePair = "UB")))
        assertEquals(ModelPromotionStatus.CONSTRAINED, status(base.copy(afterSplash = 'R')))
        assertEquals(ModelPromotionStatus.CONSTRAINED, status(base.copy(afterManaSources = null)))
    }

    @Test
    fun realSourceValidatedProjectionProducesSupportedSplashEvidence() {
        val pool = List(9) { card(100 + it, "WhiteBase$it", 0.57, "W") } +
            List(9) { card(200 + it, "BlueBase$it", 0.57, "U") }
        val candidate = card(1, "LateBlackBomb", 0.66, "B", iwd = 0.07)
        val impactMeta: (String) -> CardMeta? = { name ->
            if (name == candidate.name) CardMeta(name, 6, true, false, isFinisher = true)
            else creatureMeta(name)
        }
        val lane = LaneDetector.detect(pool, metrics)

        val result = engine.score(
            pack = listOf(candidate),
            pool = pool,
            packNumber = 2,
            pickNumber = 8,
            metrics = metrics,
            lane = lane,
            meta = impactMeta,
            deckFit = { DeckProjector.fit(pool, it, metrics, impactMeta) },
        ).single()

        assertEquals(ModelPromotionStatus.SUPPORTED_SPLASH, result.guardrail.status)
    }

    @Test
    fun failedDeckProjectionEndsSpeculationOnAnOffColorBomb() {
        val candidate = card(1, "BlackBombWithoutSources", 0.64, "B", iwd = 0.06)
        val result = engine.score(
            pack = listOf(candidate),
            pool = committedPool(),
            packNumber = 3,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(committedPool(), metrics),
            meta = creatureMeta,
            deckFit = {
                DeckProjector.Fit(
                    makesDeck = false,
                    displaced = emptyList(),
                    baseShifted = false,
                    splashAdded = null,
                    powerDelta = 0.0,
                )
            },
        ).single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertTrue(GuideConstraint.UNSUPPORTED_SPLASH in result.guardrail.constraints)
        assertFalse(result.guardrail.modelPromotable)
    }

    @Test
    fun hardGuideOrderAppliesWithoutAPickNetModel() {
        val pool = committedPool()
        val result = engine.score(
            pack = listOf(
                card(1, "UnsupportedBlackBomb", 0.75, "B", iwd = 0.08),
                card(2, "CastableWhiteCard", 0.54, "W"),
                card(3, "CastableBlueCard", 0.53, "U"),
            ),
            pool = pool,
            packNumber = 3,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(pool, metrics),
            meta = creatureMeta,
            deckFit = {
                DeckProjector.Fit(false, emptyList(), false, null, 0.0)
            },
        )

        assertEquals("CastableWhiteCard", result.first().card.name)
        val constrained = scoreOf(result, "UnsupportedBlackBomb")
        assertEquals(ModelPromotionStatus.CONSTRAINED, constrained.guardrail.status)
        assertTrue(result.zipWithNext().all { (a, b) -> a.value >= b.value },
            "hard guide order must agree with displayed scores")
        val weakestViable = result.filter { it.guardrail.modelPromotable }.minOf { it.value }
        assertEquals((weakestViable - 1.0).coerceAtLeast(0.0), constrained.value, 1e-9)
        assertTrue(constrained.breakdown!!.guideAdjustment < 0.0)
        for (score in result) {
            val b = score.breakdown!!
            val sum = b.baseScore + b.archetypeShift + b.synergyBonus + b.themeBonus + b.penalty +
                b.needsPoints + b.duplicatePenalty + b.deckFitPoints + b.wheelPenalty + b.scoreCap +
                b.guideAdjustment + b.modelShift
            assertEquals(sum, b.finalScore, 1e-9, "guide adjustment must remain visible in the score breakdown")
            assertEquals(b.finalScore, score.value, 1e-9)
            assertEquals(score.value - b.scoreCap, score.rawValue, 1e-9)
            assertEquals(null, score.modelRank)
            assertEquals(0.0, b.modelShift)
            assertFalse("Model pick" in score.reasons)
        }
    }

    @Test
    fun hardGuideOrderPreservesScoresWhenEveryCardIsConstrained() {
        val pool = committedPool()
        val pack = listOf(card(1, "BlackOption", 0.58, "B"), card(2, "RedOption", 0.56, "R"))
        fun score(cards: List<RankedCard>) = engine.score(
            pack = cards,
            pool = pool,
            packNumber = 2,
            pickNumber = 5,
            metrics = metrics,
            lane = LaneDetector.detect(pool, metrics),
            meta = creatureMeta,
        )
        val individually = pack.associate { it.name to score(listOf(it)).single() }
        val result = score(pack)

        assertEquals(listOf("BlackOption", "RedOption"), result.map { it.card.name })
        for (candidate in result) {
            assertEquals(ModelPromotionStatus.CONSTRAINED, candidate.guardrail.status)
            assertEquals(0.0, candidate.breakdown!!.guideAdjustment)
            assertEquals(individually.getValue(candidate.card.name).value, candidate.value, 1e-9)
            assertEquals(individually.getValue(candidate.card.name).rawValue, candidate.rawValue, 1e-9)
        }
    }

    @Test
    fun exactMetadataSuppliesColorsWhenRatingsAreMissing() {
        val unrated = RankedCard(grpId = 999, name = "UnratedDoubleBlack", rating = null)
        val result = engine.score(
            pack = listOf(unrated),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(committedPool(), metrics),
            meta = {
                CardMeta(
                    name = it,
                    cmc = 4,
                    isCreature = true,
                    isLand = false,
                    coloredPips = mapOf('B' to 2),
                    heavyPipColors = setOf('B'),
                )
            },
        ).single()

        assertEquals(setOf('B'), result.guardrail.offColors)
        assertTrue(GuideConstraint.HEAVY_SPLASH_PIPS in result.guardrail.constraints)
    }

    @Test
    fun unknownManaFailsClosedAfterTheLaneIsEstablished() {
        val unknown = RankedCard(grpId = 999, name = "UnresolvedCard", rating = null)
        val result = engine.score(
            pack = listOf(unknown),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(committedPool(), metrics),
            meta = { null },
        ).single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertTrue(GuideConstraint.UNKNOWN_MANA in result.guardrail.constraints)
        assertTrue(result.reasons.any { it == "Mana requirements unavailable" })
    }

    @Test
    fun offColorRatingWithoutExactCostMetadataFailsClosed() {
        val rated = card(999, "RatedButUnresolvedBlackCard", 0.70, "B", iwd = 0.08)
        val result = engine.score(
            pack = listOf(rated),
            pool = committedPool(),
            packNumber = 2,
            pickNumber = 8,
            metrics = metrics,
            lane = LaneDetector.detect(committedPool(), metrics),
            meta = { null },
        ).single()

        assertEquals(ModelPromotionStatus.CONSTRAINED, result.guardrail.status)
        assertEquals(setOf('B'), result.guardrail.offColors)
        assertTrue(GuideConstraint.UNKNOWN_MANA in result.guardrail.constraints)
    }
}
