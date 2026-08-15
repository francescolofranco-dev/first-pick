package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AccessibilityLabelsTest {
    @Test
    fun manaSymbolsUseSpokenColorNames() {
        assertEquals("White mana", manaColorName('W'))
        assertEquals("Blue mana", manaColorName('U'))
        assertEquals("Black mana", manaColorName('B'))
        assertEquals("Red mana", manaColorName('R'))
        assertEquals("Green mana", manaColorName('G'))
        assertEquals("Colorless mana", manaColorName('C'))
    }

    @Test
    fun ratingsStatusIncludesSourceAndSampleEvidence() {
        assertEquals(
            "17Lands · Stale cache · 240/260 reliable · median 1830 games",
            ratingsDataDescription(
                DraftUiState(
                    ratingsDataStatus = RatingsDataStatus.STALE_CACHE,
                    ratingsCardCount = 260,
                    ratingsReliableCardCount = 240,
                    ratingsMedianGamesPerCard = 1830,
                ),
            ),
        )
    }
}
