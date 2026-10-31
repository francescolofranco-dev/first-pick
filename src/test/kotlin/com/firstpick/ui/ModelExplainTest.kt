package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelExplainTest {
    @Test
    fun explainsPackPreferenceAndScoreBeforeAdjustment() {
        val lines = modelExplainLines(ModelExplain(rank = 1, packSize = 14, soloValue = 30.6))
        assertEquals(listOf("AI preference: #1 of 14 in this pack", "Score before AI adjustment: 30.6"), lines)
    }

    @Test
    fun demotedBombShowsItsScoreBeforeAdjustment() {
        val lines = modelExplainLines(ModelExplain(rank = 7, packSize = 14, soloValue = 84.9))
        assertEquals(listOf("AI preference: #7 of 14 in this pack", "Score before AI adjustment: 84.9"), lines)
    }

    @Test
    fun omitsScoreWhenUnavailable() {
        assertEquals(listOf("AI preference: #2 of 8 in this pack"),
            modelExplainLines(ModelExplain(rank = 2, packSize = 8, soloValue = null)))
    }

    @Test
    fun bombTierGatesTheStar() {
        assertTrue(isBombTier(85.0))
        assertTrue(!isBombTier(52.6))
    }
}
