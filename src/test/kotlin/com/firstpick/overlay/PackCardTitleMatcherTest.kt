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

    @Test
    fun screenshotPermutationCannotAttachOtherCardsRatingsToVisibleTitles() {
        val names = listOf("Ajani's Anguish", "Arni, Humble Scribe", "Terminal Criticism",
            "Arni, Renowned Champion", "Edgar, Ancient Bloodlord", "Unflinching Hortimancer",
            "Surveillance Phantasm", "Void Extrapolator", "Blazing Crescendo", "Heartstring Puller",
            "Tethermage's Advantage", "Ferocity of the Hunt", "Afterthought Sentry", "Formidable Commons")
        val wrongArt = listOf(12, 7, 4, 0, 2, 5, 11, 1, 6, 9, 10, 8, 3, 13)
            .mapIndexed { rect, card -> rect to card }.toMap()
        val rects = names.indices.map { index ->
            CardDetector.CardRect(index, index % 5 * 200, index / 5 * 300, 180, 270)
        }
        val frame = ScreenTextFrame(1000, 900, names.mapIndexed { index, name ->
            ScreenTextObservation(name, 0.99, NormalizedRect(
                (index % 5 * 200 + 10) / 1000.0, (index / 5 * 300 + 10) / 900.0, 0.15, 0.018,
            ))
        })
        val titleMatches = PackCardTitleMatcher.anchors(frame, rects, names)
        assertEquals(names.indices.associateWith { it }, titleMatches)
        assertEquals(titleMatches, PackCardTitleMatcher.verifiedAssignment(wrongArt, titleMatches, names))
    }

    @Test
    fun unreadableTitlesDoNotInheritRatingsFromAForcedArtPermutation() {
        val art = mapOf(0 to 2, 1 to 0, 2 to 1)
        assertEquals(mapOf(1 to 1), PackCardTitleMatcher.verifiedAssignment(
            art, mapOf(1 to 1), listOf("Ajani's Anguish", "Surveillance Phantasm", "Afterthought Sentry"),
        ))
        assertEquals(emptyMap(), PackCardTitleMatcher.verifiedAssignment(art, emptyMap(), listOf("A", "B", "C")))
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
