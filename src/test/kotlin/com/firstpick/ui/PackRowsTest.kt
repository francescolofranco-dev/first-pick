package com.firstpick.ui

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.ConfidenceLevel
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.ModelPromotionStatus
import com.firstpick.advisor.PickGuardrail
import com.firstpick.advisor.ScoredCard
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackRowsTest {
    private fun card(id: Int, name: String, rating: CardRating = CardRating(name = name)) =
        RankedCard(id, name, rating)

    private fun ratedScore(
        id: Int,
        value: Double,
        guardrail: PickGuardrail = PickGuardrail.OPEN,
        reasons: List<String> = emptyList(),
    ) = ScoredCard(
        card(id, "Rated $id", CardRating(everDrawnWinRate = 0.60, everDrawnGameCount = 500)),
        value = value,
        z = 0.0,
        isBomb = false,
        reasons = reasons,
        guardrail = guardrail,
    )

    @Test
    fun `catalog first pick does not present alphabetical ties as power grades`() {
        val pack = listOf(card(1, "Z catalog card"), card(2, "A catalog card"))
        val scored = AdvisorEngine().score(
            pack, emptyList(), 1, 1, SetMetrics.EMPTY,
            LaneDetector.detect(emptyList(), SetMetrics.EMPTY),
        )
        val rows = packRows(scored, emptyList(), pack.map { it.grpId })

        assertEquals(2, rows.size)
        assertTrue(hasUnratedCards(rows))
        assertNull(packConfidence(rows))
        for (row in rows) {
            assertNull(row.value)
            assertEquals("—", letterGrade(row.value))
            assertNull(row.breakdown)
            assertNull(row.modelRank)
            assertFalse(row.isBomb)
            assertTrue("Unrated — power unknown" in row.reasons)
        }
        assertEquals(listOf(1, 0), rows.map { it.originalIndex })
    }

    @Test
    fun `unrated cards retain pool fit notes without exposing fallback strength`() {
        val scored = ScoredCard(
            card = card(1, "Unrated payoff"), value = 43.2, z = -0.8, isBomb = false,
            reasons = listOf("On-color", "Synergy: Counters"),
        )
        val row = packRows(listOf(scored), emptyList(), listOf(1)).single()
        assertNull(row.value)
        assertEquals(listOf("Unrated — power unknown", "On-color", "Synergy: Counters"), row.reasons)
    }

    @Test
    fun `observed ratings and learned scores retain their normal presentation`() {
        val observed = card(1, "Observed", CardRating(everDrawnWinRate = 0.60, everDrawnGameCount = 500))
        val seen = card(2, "Pick order", CardRating(avgSeen = 3.0))
        val modeled = card(3, "Modeled")
        val scored = listOf(
            ScoredCard(observed, 60.0, 0.625, false, listOf("On-color")),
            ScoredCard(seen, 59.0, 0.5625, false, emptyList()),
            ScoredCard(modeled, 58.0, 0.5, false, emptyList(), modelRank = 3),
        )
        val rows = packRows(scored, emptyList(), listOf(1, 2, 3))
        assertEquals(listOf(60.0, 59.0, 58.0), rows.map { it.value })
        assertFalse(hasUnratedCards(rows))
        assertEquals(ConfidenceLevel.TOSS_UP, assertNotNull(packConfidence(rows)).level)
    }

    @Test
    fun `unrated spell suppresses comparative confidence but basic land does not`() {
        val scored = listOf(1, 2).map { id ->
            ScoredCard(card(id, "Rated $id", CardRating(avgSeen = 3.0)), 60.0, 0.6, false, emptyList())
        }
        val basic = card(3, "Island")
        val ratedWithBasic = packRows(scored, listOf(basic), listOf(1, 2, 3))
        assertNotNull(packConfidence(ratedWithBasic))
        assertEquals(listOf("Basic land", "Not rated"), ratedWithBasic.last().reasons)

        val unknown = ScoredCard(card(4, "Unknown"), 37.2, -0.8, false, emptyList())
        val mixed = packRows(scored + unknown, listOf(basic), listOf(1, 2, 3, 4))
        assertNull(packConfidence(mixed))
    }

    @Test
    fun `one viable card does not become a toss up with a constrained near tie`() {
        val scored = listOf(
            ratedScore(1, 60.0),
            ratedScore(2, 59.8, PickGuardrail(ModelPromotionStatus.CONSTRAINED)),
        )
        val rows = packRows(scored, emptyList(), listOf(1, 2))

        assertFalse(hasUnratedCards(rows))
        assertNull(packConfidence(rows))
    }

    @Test
    fun `confidence compares viable cards without a constrained near tie`() {
        val scored = listOf(
            ratedScore(1, 60.0),
            ratedScore(2, 59.8, PickGuardrail(ModelPromotionStatus.CONSTRAINED)),
            ratedScore(3, 50.0, PickGuardrail(ModelPromotionStatus.ON_PLAN)),
        )
        val rows = packRows(scored, emptyList(), listOf(1, 2, 3))
        val confidence = assertNotNull(packConfidence(rows))

        assertEquals(ConfidenceLevel.CLEAR, confidence.level)
        assertEquals(10.0, confidence.gap)
        assertEquals(1, confidence.contenders)
        assertEquals(listOf(60.0, 59.8, 50.0), rows.map { it.value })
    }

    @Test
    fun `all constrained cards retain confidence from their fallback values`() {
        val constrained = PickGuardrail(ModelPromotionStatus.CONSTRAINED)
        val scored = listOf(ratedScore(1, 60.0, constrained), ratedScore(2, 59.5, constrained))
        val rows = packRows(scored, emptyList(), listOf(1, 2))
        val confidence = assertNotNull(packConfidence(rows))

        assertEquals(ConfidenceLevel.TOSS_UP, confidence.level)
        assertEquals(0.5, confidence.gap)
        assertEquals(2, confidence.contenders)
    }

    @Test
    fun `guide constraint follows the typed guardrail regardless of display reasons`() {
        val promotableStatuses = listOf(
            ModelPromotionStatus.FLEXIBLE,
            ModelPromotionStatus.ON_PLAN,
            ModelPromotionStatus.SPLASH_CANDIDATE,
            ModelPromotionStatus.SUPPORTED_SPLASH,
        )
        val scored = promotableStatuses.mapIndexed { index, status ->
            ratedScore(index + 1, 60.0, PickGuardrail(status), listOf("Guide-restricted", "Off-color (BG lane)"))
        } + ratedScore(5, 59.0, PickGuardrail(ModelPromotionStatus.CONSTRAINED))
        val rows = packRows(scored, emptyList(), scored.map { it.card.grpId })

        for (row in rows.take(4)) assertFalse(row.guideConstrained, row.name)
        assertTrue(rows.last().guideConstrained)
        assertTrue(rows.last().reasons.isEmpty())
    }
}
