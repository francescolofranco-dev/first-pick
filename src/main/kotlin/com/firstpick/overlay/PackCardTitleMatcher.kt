package com.firstpick.overlay

internal object PackCardTitleMatcher {
    fun anchors(
        frame: ScreenTextFrame,
        rects: List<CardDetector.CardRect>,
        cardNames: List<String>,
    ): Map<Int, Int> {
        if (frame.pixelWidth <= 0 || frame.pixelHeight <= 0 || rects.isEmpty() || cardNames.isEmpty()) {
            return emptyMap()
        }

        val namedCards = cardNames.withIndex().mapNotNull { (index, name) ->
            name.trim().takeIf(String::isNotEmpty)?.let { IndexedValue(index, it) }
        }
        if (namedCards.isEmpty()) return emptyMap()

        val indicesByName = namedCards.groupBy({ it.value }, { it.index })
        val matcher = CanonicalCardNameMatcher(namedCards.map(IndexedValue<String>::value))
        val candidates = frame.observations.mapNotNull { observation ->
            if (observation.confidence < MIN_CONFIDENCE) return@mapNotNull null
            val matched = matcher.match(observation.text) as? CanonicalCardNameMatch.Matched
                ?: return@mapNotNull null
            val cardIndex = indicesByName[matched.name]?.singleOrNull() ?: return@mapNotNull null
            val centerX = observation.bounds.centerX * frame.pixelWidth
            val centerY = observation.bounds.centerY * frame.pixelHeight
            val rect = rects.singleOrNull { candidate ->
                centerX >= candidate.x && centerX <= candidate.x + candidate.w &&
                    centerY >= candidate.y && centerY <= candidate.y + candidate.h * TITLE_BAND_BOTTOM
            } ?: return@mapNotNull null
            Candidate(rect.index, cardIndex)
        }.distinct()

        val unambiguousRects = candidates.groupBy(Candidate::rectIndex)
            .filterValues { inRect -> inRect.map(Candidate::cardIndex).distinct().size == 1 }
            .values.map(List<Candidate>::first)
        val unambiguousCards = unambiguousRects.groupBy(Candidate::cardIndex)
            .filterValues { forCard -> forCard.map(Candidate::rectIndex).distinct().size == 1 }
            .values.map(List<Candidate>::first)
        return unambiguousCards.associate { it.rectIndex to it.cardIndex }
    }

    fun applyAnchors(
        assignment: Map<Int, Int>,
        anchors: Map<Int, Int>,
    ): Map<Int, Int> {
        if (assignment.isEmpty() || anchors.isEmpty()) return assignment
        val corrected = assignment.toMutableMap()
        for ((rectIndex, cardIndex) in anchors) {
            val currentCard = corrected[rectIndex]
            val currentRect = corrected.entries.firstOrNull { it.value == cardIndex }?.key ?: continue
            corrected[rectIndex] = cardIndex
            if (currentRect != rectIndex) {
                if (currentCard == null) corrected.remove(currentRect)
                else corrected[currentRect] = currentCard
            }
        }
        return corrected
    }

    private data class Candidate(val rectIndex: Int, val cardIndex: Int)

    private const val MIN_CONFIDENCE = 0.75
    private const val TITLE_BAND_BOTTOM = 0.16
}
