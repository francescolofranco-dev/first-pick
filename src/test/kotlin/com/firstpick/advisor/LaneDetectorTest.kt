package com.firstpick.advisor

import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
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
            card("c", 0.60, "B"),
            card("d", 0.58, "R"), // single weak red
        )
        val lane = LaneDetector.detect(pool, metrics)
        assertEquals(setOf('U', 'B'), lane.colors)
    }

    @Test
    fun archetypeStrengthGuidesAnEmptyLane() {
        // With no picks yet, the set's strongest archetype should be suggested.
        val strengths = mapOf("WR" to 0.59, "WU" to 0.55, "UB" to 0.54, "BG" to 0.53)
        val lane = LaneDetector.detect(emptyList(), metrics, strengths)
        assertEquals("WR", lane.pair)
    }

    @Test
    fun poolOverridesArchetypeStrengthOnceCommitted() {
        // Even if Boros (WR) is the set's best archetype, a committed UB pool wins.
        val strengths = mapOf("WR" to 0.59, "UB" to 0.54)
        val pool = List(6) { card("p$it", 0.60, if (it % 2 == 0) "U" else "B") }
        val lane = LaneDetector.detect(pool, metrics, strengths)
        assertEquals("UB", lane.pair)
    }

    @Test
    fun recencyLetsLatePivotOverturnEarlyPicks() {
        // Early: two strong green. Late: three strong white. Recency should favor white.
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
}
