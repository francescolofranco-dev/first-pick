package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaneDetectorTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(name: String, gih: Double, color: String) = RankedCard(
        grpId = name.hashCode(),
        name = name,
        rating = CardRating(name = name, color = color, everDrawnWinRate = gih, everDrawnGameCount = 2000),
    )

    @Test
    fun emptyPoolHasNoLane() {
        val lane = LaneDetector.detect(emptyList(), metrics)
        assertTrue(lane.colors.isEmpty())
        assertEquals(false, lane.isEstablished)
    }

    @Test
    fun picksTheTwoMostCommittedColors() {
        val pool = listOf(
            card("a", 0.60, "U"),
            card("b", 0.60, "U"),
            card("e", 0.60, "U"),
            card("c", 0.60, "B"),
            card("d", 0.58, "B"),
        )
        val lane = LaneDetector.detect(pool, metrics)
        assertEquals(setOf('U', 'B'), lane.colors)
        assertEquals("UB", lane.pair)
        assertTrue(lane.isEstablished)
    }

    @Test
    fun archetypeStrengthGuidesAnEmptyLane() {
        val strengths = mapOf("WR" to 0.59, "WU" to 0.55, "UB" to 0.54, "BG" to 0.53)
        val lane = LaneDetector.detect(emptyList(), metrics, strengths)
        assertEquals(null, lane.pair)
        assertEquals("WR", lane.topPairs.first())
    }

    @Test
    fun passedCardSignalsStillGuideAnOpenLane() {
        val lane = LaneDetector.detect(emptyList(), metrics, signals = mapOf('R' to 4.0, 'G' to 5.0))

        assertEquals(null, lane.pair)
        assertEquals("RG", lane.topPairs.first())
        assertFalse(lane.isEstablished)
    }

    @Test
    fun poolOverridesArchetypeStrengthOnceCommitted() {
        val strengths = mapOf("WR" to 0.59, "UB" to 0.54)
        val pool = List(6) { card("p$it", 0.60, if (it % 2 == 0) "U" else "B") }
        val lane = LaneDetector.detect(pool, metrics, strengths)
        assertEquals("UB", lane.pair)
    }

    @Test
    fun passedCardSignalsCannotOverrideSupportedPoolIdentity() {
        val pool = List(5) { card("black$it", 0.53, "B") } +
            List(4) { card("green$it", 0.54, "G") } +
            List(2) { card("pest$it", 0.53, "BG") } +
            card("oneRed", 0.55, "R") +
            List(2) { card("white$it", 0.55, "W") } +
            card("colorless", 0.55, "")
        val signals = mapOf('R' to 10.0, 'G' to 10.0, 'B' to 0.1)

        val lane = LaneDetector.detect(pool, metrics, signals = signals)

        assertEquals("BG", lane.pair)
        assertEquals(setOf('B', 'G'), lane.colors)
        assertEquals("BG", lane.topPairs.first())
        assertTrue(lane.isEstablished)
    }

    @Test
    fun oneCardCannotEstablishASecondLaneColor() {
        val pool = List(6) { card("green$it", 0.56, "G") } + card("oneRed", 0.65, "R")

        val lane = LaneDetector.detect(pool, metrics, signals = mapOf('R' to 20.0, 'G' to 20.0))

        assertEquals(null, lane.pair)
        assertEquals(setOf('G'), lane.colors)
        assertFalse(lane.isEstablished)
    }

    @Test
    fun twoCardsBelowTheCommitmentShareCannotEstablishAColor() {
        val pool = List(2) { card("earlyRed$it", 0.55, "R") } +
            List(18) { card("green$it", 0.55, "G") }

        val lane = LaneDetector.detect(pool, metrics)

        assertEquals(null, lane.pair)
        assertEquals(setOf('G'), lane.colors)
        assertFalse(lane.isEstablished)
    }

    @Test
    fun closeEligiblePairsRemainUnestablished() {
        val pool = listOf(
            card("w1", 0.55, "W"),
            card("u1", 0.55, "U"),
            card("b1", 0.55, "B"),
            card("w2", 0.55, "W"),
            card("u2", 0.55, "U"),
            card("b2", 0.55, "B"),
        )

        val lane = LaneDetector.detect(pool, metrics)

        assertEquals(null, lane.pair)
        assertFalse(lane.isEstablished)
    }

    @Test
    fun mediocrePicksProvideRealCommitmentInsteadOfANearZeroFloor() {
        val pool = List(7) { card("black$it", 0.50, "B") } +
            List(2) { card("green$it", 0.61, "G") }

        val lane = LaneDetector.detect(pool, metrics)

        assertEquals("BG", lane.pair)
        assertTrue(lane.commitment.getValue('B') > lane.commitment.getValue('G'))
    }

    @Test
    fun flexibleHybridCardsDoNotFabricateASecondCommittedColor() {
        val pool = List(6) { card("blue$it", 0.56, "U") } +
            List(2) { card("hybrid$it", 0.60, "WU") }
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name.startsWith("hybrid")) {
                CardMeta(
                    name = name,
                    cmc = 2,
                    isCreature = true,
                    isLand = false,
                    hybridColorGroups = listOf(setOf('W', 'U')),
                )
            } else {
                null
            }
        }

        val lane = LaneDetector.detect(pool, metrics, meta = hybridMeta)

        assertEquals(null, lane.pair)
        assertEquals(setOf('U'), lane.colors)
        assertFalse(lane.isEstablished)
    }

    @Test
    fun screenshotPoolChoosesBlackRedDespiteGreenSignalsAndHybridIdentity() {
        val pool = listOf(
            card("Gollum the Abandoned", 0.55, "B"),
            card("Ravening Warg", 0.55, "B"),
            card("Dáin Ironfoot", 0.55, "R"),
            card("Duskwatch Hunter", 0.55, "BG"),
            card("Bolg of the North", 0.55, "BR"),
        )
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name == "Duskwatch Hunter") CardMeta(
                name, cmc = 3, isCreature = true, isLand = false,
                hybridColorGroups = listOf(setOf('B', 'G')),
                hybridPips = listOf(setOf('B', 'G')),
            ) else null
        }
        for (useRecency in listOf(true, false)) {
            val lane = LaneDetector.detect(
                pool, metrics,
                pairStrength = mapOf("BR" to 0.598, "WB" to 0.577, "BG" to 0.564),
                signals = mapOf('G' to 20.0),
                meta = hybridMeta,
                useRecency = useRecency,
            )

            assertEquals("BR", lane.pair)
            assertEquals("BR", lane.topPairs.first())
            assertEquals(setOf('B', 'R'), lane.colors)
            assertFalse('G' in lane.commitment)
        }
    }

    @Test
    fun fourPicksCanEstablishAPairWithTwoCardsSupportingEachColor() {
        val pool = listOf(
            card("black1", 0.55, "B"), card("red1", 0.55, "R"),
            card("black2", 0.55, "B"), card("red2", 0.55, "R"),
        )

        assertEquals("BR", LaneDetector.detect(pool, metrics).pair)
        assertFalse(LaneDetector.detect(pool.take(3), metrics).isEstablished)
    }

    @Test
    fun hybridPlayabilityBreaksATieBetweenSupportedPairs() {
        val pool = listOf("B", "R", "G").flatMap { color ->
            List(2) { card("$color$it", 0.55, color) }
        } + card("mixed", 0.55, "UBG")
        val mixedMeta = CardMeta(
            "mixed", cmc = 2, isCreature = true, isLand = false,
            coloredPips = mapOf('B' to 1),
            hybridColorGroups = listOf(setOf('U', 'G')),
        )

        val lane = LaneDetector.detect(
            pool, metrics, meta = { if (it == "mixed") mixedMeta else null }, useRecency = false,
        )

        assertEquals("BG", lane.pair, "BG can cast {B}{U/G}; BR cannot")
    }

    @Test
    fun pureHybridCardsCountForEveryPairThatCanCastThem() {
        val pool = listOf("B", "R", "W").flatMap { color ->
            List(2) { card("$color$it", 0.55, color) }
        } + card("hybrid", 0.55, "BG")
        val hybridMeta = CardMeta(
            "hybrid", cmc = 3, isCreature = true, isLand = false,
            hybridColorGroups = listOf(setOf('B', 'G')),
        )

        val lane = LaneDetector.detect(
            pool, metrics, meta = { if (it == "hybrid") hybridMeta else null }, useRecency = false,
        )

        assertEquals(null, lane.pair, "BR and WB are equally supported")
        assertEquals(setOf("BR", "WB"), lane.topPairs.take(2).toSet())
        assertFalse('G' in lane.commitment)
    }

    @Test
    fun lateWeakBlueCardsDoNotDisplaceTheBlackGreenPool() {
        val pool = listOf(
            card("Gollum", 0.585, "B"), card("Warg", 0.592, "B"),
            card("Dain", 0.620, "R"), card("Hunter", 0.567, "BG"),
            card("Bolg", 0.567, "BR"), card("Quarrel", 0.552, "G"),
            card("Attercop", 0.542, "G"), card("Wargling", 0.525, "G"),
            card("Galion", 0.516, "G"), card("Bear", 0.548, "G"),
            card("Arrow", 0.493, ""), card("Gandalf", 0.504, "U"),
            card("Spider1", 0.517, "U"), card("Spider2", 0.517, "U"),
        )
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name == "Hunter") CardMeta(name, 3, true, false,
                hybridColorGroups = listOf(setOf('B', 'G'))) else null
        }
        for (ordered in listOf(true, false)) {
            val lane = LaneDetector.detect(pool, metrics, meta = hybridMeta, useRecency = ordered)
            assertEquals(setOf('B', 'G'), lane.colors)
        }
    }

    @Test
    fun unorderedRecoveredPoolDoesNotTurnCardIdsIntoRecency() {
        val pool = List(4) { card("black$it", 0.55, "B") } +
            List(2) { card("green$it", 0.60, "G") }

        val forward = LaneDetector.detect(pool, metrics, useRecency = false)
        val reversed = LaneDetector.detect(pool.reversed(), metrics, useRecency = false)

        assertEquals(forward.pair, reversed.pair)
        assertEquals(forward.commitment, reversed.commitment)
    }

    @Test
    fun recencyLetsLatePivotOverturnEarlyPicks() {
        val pool = listOf(
            card("earlyG1", 0.62, "G"),
            card("earlyG2", 0.62, "G"),
            card("lateW1", 0.62, "W"),
            card("lateW2", 0.62, "W"),
            card("lateW3", 0.62, "W"),
        )
        val lane = LaneDetector.detect(pool, metrics)
        assertTrue('W' in lane.colors, "Recent white commitment should be in the lane")
    }

    @Test
    fun hybridPipSatisfiedByOneLaneColorIsNotUncastable() {
        val uncastable = LaneDetector.uncastableColors(
            colors = setOf('U', 'W'),
            available = setOf('U', 'G'),
            hybridGroups = listOf(setOf('U', 'W')),
        )
        assertEquals(emptySet(), uncastable)
    }

    @Test
    fun hybridPipUnsatisfiedByEitherLaneColorIsFullyUncastable() {
        val uncastable = LaneDetector.uncastableColors(
            colors = setOf('U', 'W'),
            available = setOf('B', 'G'),
            hybridGroups = listOf(setOf('U', 'W')),
        )
        assertEquals(setOf('U', 'W'), uncastable)
    }

    @Test
    fun hybridSatisfiedPipDoesNotMaskAGenuineOffColorPip() {
        val uncastable = LaneDetector.uncastableColors(
            colors = setOf('R', 'U', 'W'),
            available = setOf('U', 'G'),
            hybridGroups = listOf(setOf('U', 'W')),
        )
        assertEquals(setOf('R'), uncastable)
    }

    @Test
    fun exactPurePipIsNotErasedWhenItsColorAlsoAppearsInAHybridSymbol() {
        val options = LaneDetector.uncastableColorOptions(
            colors = setOf('B', 'R'),
            available = setOf('R', 'G'),
            hybridGroups = listOf(setOf('B', 'R')),
            pureColors = setOf('B'),
        )

        assertEquals(listOf(setOf('B')), options, "{B}{B/R} still needs black in a red deck")
    }

    @Test
    fun offBaseHybridSymbolKeepsEitherSingleColorCastingOption() {
        val options = LaneDetector.uncastableColorOptions(
            colors = setOf('B', 'R'),
            available = setOf('W', 'U'),
            hybridGroups = listOf(setOf('B', 'R')),
            pureColors = emptySet(),
        )

        assertEquals(listOf(setOf('B'), setOf('R')), options)
    }

    @Test
    fun noHybridGroupsBehavesLikePlainSetDifference() {
        val uncastable = LaneDetector.uncastableColors(colors = setOf('U', 'R'), available = setOf('U', 'G'))
        assertEquals(setOf('R'), uncastable)
    }
}
