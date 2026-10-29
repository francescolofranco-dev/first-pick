package com.firstpick.overlay

import com.firstpick.cards.canonicalBasicLandName

internal enum class ArenaCardZone { PACK, POOL, DECK, COLUMN }

internal data class ArenaVisibleCard(
    val grpId: Int,
    val count: Int,
    val zone: ArenaCardZone,
    val bounds: NormalizedRect,
)

/** Coordinates describe Arena's client viewport, excluding the macOS title bar. */
internal data class ArenaCardGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val cards: List<ArenaVisibleCard>,
) {
    fun inWindow(window: WindowBounds): List<ArenaVisibleCard> {
        if (screenWidth !in 320..16384 || screenHeight !in 200..16384 || window.w <= 0 || window.h <= 0) return emptyList()
        if (window.backingScale?.let { kotlin.math.abs(screenWidth - window.w * it) > 4.0 } == true) return emptyList()
        val contentHeight = window.w.toDouble() * screenHeight / screenWidth
        val topInset = window.h - contentHeight
        // A changed viewport must settle before any old rectangles are reused.
        if (topInset < -1.0 || topInset > 40.0) return emptyList()
        return cards.mapNotNull { card ->
            val rect = card.bounds
            val adjusted = NormalizedRect(rect.x, (topInset.coerceAtLeast(0.0) + rect.y * contentHeight) / window.h,
                rect.width, rect.height * contentHeight / window.h)
            adjusted.takeIf(NormalizedRect::isValid)?.let { card.copy(bounds = it) }
        }
    }
}

internal fun draftScoreMarks(cards: List<OverlayCard>, placements: List<ArenaVisibleCard>): List<DraftScoreMark> {
    val pack = placements.filter { it.zone == ArenaCardZone.PACK }
    if (pack.map { it.grpId } != cards.map { it.grpId } || pack.any { it.count != 1 || !it.bounds.isValid() }) return emptyList()
    val best = bestRatedOverlayCard(cards, cards.indices.toList())
    val paired = cards.any { it.recommendedPair }
    return cards.mapIndexed { index, card ->
        DraftScoreMark(pack[index].bounds, card.value, if (paired) card.recommendedPair else index == best)
    }
}

internal fun DeckGuidanceOverlayModel.withMemoryMarks(
    placements: List<ArenaVisibleCard>,
    names: Map<Int, String>,
): DeckGuidanceOverlayModel {
    if (status != DeckGuidanceStatus.NEEDS_CHANGES) return copy(marks = emptyList())
    val advice = (changes + basicLandChanges).associateBy { it.name }
    val matcher = CanonicalCardNameMatcher(advice.keys)
    return copy(marks = placements.mapNotNull { card ->
        if (!card.bounds.isValid() || card.count !in 1..300) return@mapNotNull null
        val name = names[card.grpId] ?: return@mapNotNull null
        val canonical = canonicalBasicLandName(name)?.removePrefix("Snow-Covered ")
            ?: (matcher.match(name) as? CanonicalCardNameMatch.Matched)?.name
        val guidance = advice[canonical] ?: return@mapNotNull null
        val bounds = card.bounds
        when (card.zone) {
            ArenaCardZone.DECK, ArenaCardZone.COLUMN -> when (guidance.action) {
                DeckGuidanceAction.ADD -> DeckGuidanceVisualMark.AddCard(bounds, inDeckList = true, compact = card.zone == ArenaCardZone.COLUMN)
                DeckGuidanceAction.REMOVE -> DeckGuidanceVisualMark.RemoveRow(bounds, card.count, compact = card.zone == ArenaCardZone.COLUMN)
                DeckGuidanceAction.OK -> null
            }
            ArenaCardZone.POOL -> if (guidance.action == DeckGuidanceAction.ADD) DeckGuidanceVisualMark.AddCard(card.bounds) else null
            ArenaCardZone.PACK -> null
        }
    })
}
