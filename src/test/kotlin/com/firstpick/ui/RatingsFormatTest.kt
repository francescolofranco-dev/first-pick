package com.firstpick.ui

import com.firstpick.model.DraftFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class RatingsFormatTest {
    @Test
    fun explicitChoicesIgnoreDetectedFormat() {
        for (detected in DraftFormat.entries.filterNot { it == DraftFormat.SEALED }) {
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
    fun autoUsesSealedAndFallsBackToPremierForOtherNonDraftFormats() {
        assertEquals("Sealed", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.SEALED))
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.CUBE))
        assertEquals("PremierDraft", RatingsFormat.resolve(RatingsFormat.AUTO, DraftFormat.UNKNOWN))
    }

    @Test
    fun sealedDetectionOverridesPersistedDraftDataChoices() {
        for (choice in listOf(RatingsFormat.PREMIER, RatingsFormat.QUICK, RatingsFormat.TRAD, RatingsFormat.AUTO)) {
            assertEquals("Sealed", RatingsFormat.resolve(choice, DraftFormat.SEALED))
            assertEquals("Sealed · match event", RatingsFormat.displayLabel(choice, DraftFormat.SEALED))
        }
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
