package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class PackCardTitleMatcherTest {
    @Test
    fun anchorsExactPackTitlesToTheirCardRects() {
        val rects = listOf(
            CardDetector.CardRect(0, 100, 100, 200, 300),
            CardDetector.CardRect(1, 320, 100, 200, 300),
        )
        val frame = ScreenTextFrame(
            pixelWidth = 800,
            pixelHeight = 600,
            observations = listOf(
                observation("Arcane Omens", 0.95, x = 0.19, y = 0.20),
                observation("Killian's Confidence", 0.98, x = 0.52, y = 0.20),
                observation("Arcane Omens", 0.99, x = 0.52, y = 0.50),
            ),
        )

        assertEquals(
            mapOf(0 to 0, 1 to 1),
            PackCardTitleMatcher.anchors(frame, rects, listOf("Arcane Omens", "Killian's Confidence")),
        )
    }

    @Test
    fun ignoresLowConfidenceTyposAndDuplicateCardNames() {
        val rects = listOf(CardDetector.CardRect(0, 0, 0, 300, 400))
        val frame = ScreenTextFrame(
            pixelWidth = 600,
            pixelHeight = 600,
            observations = listOf(
                observation("ast Gasp", 0.99, x = 0.20, y = 0.05),
                observation("Last Gasp", 0.50, x = 0.20, y = 0.05),
            ),
        )

        assertEquals(emptyMap(), PackCardTitleMatcher.anchors(frame, rects, listOf("Last Gasp")))
        assertEquals(
            emptyMap(),
            PackCardTitleMatcher.anchors(
                frame.copy(observations = listOf(observation("Last Gasp", 0.99, x = 0.20, y = 0.05))),
                rects,
                listOf("Last Gasp", "Last Gasp"),
            ),
        )
    }

    @Test
    fun ignoresUnnamedCardsWithoutLosingTheirOriginalIndices() {
        val rects = listOf(CardDetector.CardRect(0, 0, 0, 300, 400))
        val frame = ScreenTextFrame(
            pixelWidth = 600,
            pixelHeight = 600,
            observations = listOf(observation("Last Gasp", 0.99, x = 0.20, y = 0.05)),
        )

        assertEquals(emptyMap(), PackCardTitleMatcher.anchors(frame, rects, listOf("", "   ")))
        assertEquals(mapOf(0 to 1), PackCardTitleMatcher.anchors(frame, rects, listOf("", " Last Gasp ")))
    }

    @Test
    fun oneTrustedTitleRepairsAVisualPairSwap() {
        val swapped = mapOf(0 to 1, 1 to 0, 2 to 2)

        assertEquals(
            mapOf(0 to 0, 1 to 1, 2 to 2),
            PackCardTitleMatcher.applyAnchors(swapped, mapOf(0 to 0)),
        )
    }

    @Test
    fun ignoresAnAnchorForACardThatWasNotVisuallyAssigned() {
        val partial = mapOf(0 to 1, 1 to 2)

        assertEquals(partial, PackCardTitleMatcher.applyAnchors(partial, mapOf(0 to 3)))
    }

    private fun observation(
        text: String,
        confidence: Double,
        x: Double,
        y: Double,
    ) = ScreenTextObservation(
        text = text,
        confidence = confidence,
        bounds = NormalizedRect(x, y, 0.10, 0.02),
    )
}
