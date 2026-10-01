package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeckPowerPresentationTest {
    @Test
    fun `catalog builds hide numerical power and tier while rated builds retain them`() {
        val rated = DeckOptionUi(
            colors = "WU", basePair = "WU", tier = "B", type = "Tempo",
            outlook = "Above-average build", power = 60,
            identityConfidence = "High", identityReasons = emptyList(),
            powerConfidence = "Medium", powerReasons = emptyList(),
            creatures = 15, removal = 3, landLine = "17 lands", spells = emptyList(),
        )
        assertEquals("Power 60", rated.powerLabel)
        assertEquals("B", rated.displayTier)

        val catalog = rated.copy(hasObservedPower = false)
        assertEquals("Power unavailable", catalog.powerLabel)
        assertNull(catalog.displayTier)
        assertEquals(rated.identityLine, catalog.identityLine)
    }
}
