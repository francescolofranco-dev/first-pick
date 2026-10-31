package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarPaneTest {

    @Test
    fun firstPackPairIsLeaningInsteadOfSettled() {
        val early = DraftUiState(pack = 1, pick = 9, poolSize = 8,
            lanePair = "UG", laneColors = listOf('U', 'G'))
        assertFalse(hasSettledLane(early))
        assertFalse(hasSettledLane(early.copy(pick = 15, poolSize = 14)))
        assertTrue(hasSettledLane(early.copy(pack = 2, pick = 1, poolSize = 15)))
        assertFalse(hasSettledLane(early.copy(pack = 2, lanePair = null)))
        assertFalse(hasSettledLane(early.copy(picksPerChoice = 2, pack = 2, poolSize = 8)))
        assertTrue(hasSettledLane(early.copy(picksPerChoice = 2, poolSize = 16)))
    }

    @Test
    fun safeFloatFractionHandlesNaNAndInfinity() {
        assertEquals(0f, safeFloatFraction(Float.NaN))
        assertEquals(0f, safeFloatFraction(Float.POSITIVE_INFINITY))
        assertEquals(0f, safeFloatFraction(Float.NEGATIVE_INFINITY))
        assertEquals(0.5f, safeFloatFraction(0.5f))
        assertEquals(1f, safeFloatFraction(1.5f))
        assertEquals(0f, safeFloatFraction(-0.5f))
    }

    @Test
    fun safeScoreFractionHandlesZeroMaxScoreAndNaN() {
        assertEquals(0.0, safeScoreFraction(0.0, 0.0))
        assertEquals(0.0, safeScoreFraction(10.0, 0.0))
        assertEquals(0.0, safeScoreFraction(Double.NaN, 10.0))
        assertEquals(0.0, safeScoreFraction(5.0, Double.NaN))
        assertEquals(0.5, safeScoreFraction(5.0, 10.0))
        assertEquals(1.0, safeScoreFraction(15.0, 10.0))
    }

    @Test
    fun archetypeFractionHandlesEqualOrNaNWinRates() {
        assertEquals(1.0f, archetypeFraction(0.55, 0.55, 0.55))
        assertEquals(1.0f, archetypeFraction(Double.NaN, 0.40, 0.60))
        assertEquals(0.15f, archetypeFraction(0.40, 0.40, 0.60))
        assertEquals(1.0f, archetypeFraction(0.60, 0.40, 0.60))
    }
}
