package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnratedOverlayTest {
    @Test
    fun `partial title verification cannot promote a weaker card to best pick`() {
        val cards = listOf(OverlayCard(80.0, null), OverlayCard(40.0, null))
        assertNull(bestRatedOverlayCard(cards, listOf(1)))
        assertEquals(0, bestRatedOverlayCard(cards, listOf(0)))
    }

    @Test
    fun `catalog cards have no best-pick halo`() {
        val cards = listOf(OverlayCard(null, null), OverlayCard(null, null))
        assertNull(bestRatedOverlayCard(cards, listOf(0, 1)))
    }

    @Test
    fun `rated best pick still ignores an ungraded basic land`() {
        val cards = listOf(OverlayCard(null, null), OverlayCard(70.0, null), OverlayCard(55.0, null))
        assertEquals(1, bestRatedOverlayCard(cards, listOf(0, 1, 2)))
        assertNull(bestRatedOverlayCard(cards, emptyList()))
    }
}
