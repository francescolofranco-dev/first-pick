package com.firstpick.ui

import com.firstpick.model.DraftFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class RatingsFormatTest {
    @Test
    fun explicitChoicesIgnoreDetectedFormat() {
        for (detected in DraftFormat.entries) {
            assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.PREMIER, detected))
            assertEquals("QuickDraft", RatingsFormat.resolve(RatingsFormat.QUICK, detected))
            assertEquals("TradDraft", RatingsFormat.resolve(RatingsFormat.TRAD, detected))
        }
    }

    @Test
    fun autoFollowsDetectedFormat() {
        assertEquals("QuickDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.QUICK))
        assertEquals("TradDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.TRADITIONAL))
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.PREMIER))
    }

    @Test
    fun autoFallsBackToPremierForFormatsWithoutCardRatings() {
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.SEALED))
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.CUBE))
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.UNKNOWN))
    }

    @Test
    fun unknownChoiceFallsBackToPremier() {
        assertEquals("PremierDraft", RatingsFormat.resolve("garbage", DraftFormat.QUICK))
    }

    @Test
    fun everyChoiceHasANonBlankLabel() {
        for (choice in RatingsFormat.choices) {
            assert(RatingsFormat.label(choice).isNotBlank())
        }
    }
}
