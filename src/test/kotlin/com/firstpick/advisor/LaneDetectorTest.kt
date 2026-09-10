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
