package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarPaneTest {

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
