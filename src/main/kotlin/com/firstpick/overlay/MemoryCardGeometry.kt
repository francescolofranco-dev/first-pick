package com.firstpick.overlay

import com.firstpick.cards.canonicalBasicLandName

internal enum class ArenaCardZone { PACK, POOL, DECK, COLUMN, PREVIEW }

internal data class ArenaVisibleCard(
    val grpId: Int,
    val count: Int,
    val zone: ArenaCardZone,
    val bounds: NormalizedRect,
    val occluded: Boolean = false,
    val sourceIndex: Int? = null,
    val previewSettled: Boolean = true,
    val occlusionBounds: NormalizedRect? = null,
    val occlusions: List<NormalizedRect> = emptyList(),
)

/** Coordinates describe Arena's client viewport, excluding the macOS title bar. */
internal data class ArenaCardGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val cards: List<ArenaVisibleCard>,
    val occlusions: List<NormalizedRect> = emptyList(),
) {
    fun inWindow(window: WindowBounds): List<ArenaVisibleCard> {
        if (screenWidth !in 320..16384 || screenHeight !in 200..16384 || window.w <= 0 || window.h <= 0) return emptyList()
        if (window.backingScale?.let { kotlin.math.abs(screenWidth - window.w * it) > 4.0 } == true) return emptyList()
        val contentHeight = window.w.toDouble() * screenHeight / screenWidth
        val topInset = window.h - contentHeight
        // A changed viewport must settle before any old rectangles are reused.
        if (topInset < -1.0 || topInset > 40.0) return emptyList()
        fun adjusted(rect: NormalizedRect) = NormalizedRect(rect.x,
            (topInset.coerceAtLeast(0.0) + rect.y * contentHeight) / window.h,
            rect.width, rect.height * contentHeight / window.h)
        val blockers = occlusions.map(::adjusted)
        return cards.mapNotNull { card ->
            adjusted(card.bounds).takeIf(NormalizedRect::isValid)?.let {
                card.copy(bounds = it, occluded = occlusions.any(card.bounds::overlaps), occlusions = blockers,
                    occlusionBounds = card.occlusionBounds?.let(::adjusted))
            }
        }
    }
}

internal fun draftScoreMarks(
    cards: List<OverlayCard>, placements: List<ArenaVisibleCard>,
    viewportWidth: Float = 1920f, viewportHeight: Float = 1080f,
    includeCovered: Boolean = false,
): List<DraftScoreMark> {
    val pack = placements.filter { it.zone == ArenaCardZone.PACK }
    if (pack.map { it.grpId } != cards.map { it.grpId } || pack.any { it.count != 1 || !it.bounds.isValid() }) return emptyList()
    val best = bestRatedOverlayCard(cards, cards.indices.toList())
    val paired = cards.any { it.recommendedPair }
    val previews = placements.filter { it.zone == ArenaCardZone.PREVIEW }
    val marks = cards.mapIndexed { index, card ->
        DraftScoreMark(pack[index].bounds, card.value, if (paired) card.recommendedPair else index == best, card.breakdown, card.model, card.gihWr, card.highlight, slot = index)
    }
    fun applyOcclusion(mark: DraftScoreMark, blockers: List<NormalizedRect>): DraftScoreMark? {
        // Card borders and score badges have different footprints. A tooltip over
        // artwork must not hide a score that remains below the tooltip.
        val visible = if (blockers.any(mark.bounds::overlaps)) mark.copy(highlight = com.firstpick.ui.PickHighlight.NONE) else mark
        val badge = scoreBadgeBounds(visible, viewportWidth, viewportHeight) ?: return null
        val footprint = NormalizedRect((badge.left / viewportWidth).toDouble(), (badge.top / viewportHeight).toDouble(),
            (badge.width / viewportWidth).toDouble(), (badge.height / viewportHeight).toDouble())
        return visible.copy(covered = blockers.any(footprint::overlaps))
    }
    val all = marks.mapIndexedNotNull { index, mark ->
        applyOcclusion(mark, pack[index].occlusions + previews.map { it.occlusionBounds ?: it.bounds })
    } + previews.mapIndexedNotNull { previewIndex, preview ->
        val index = preview.sourceIndex?.takeIf { cards.getOrNull(it)?.grpId == preview.grpId }
            ?: cards.indexOfFirst { it.grpId == preview.grpId }
        if (index < 0 || !preview.bounds.isValid()) null else applyOcclusion(marks[index].copy(bounds = preview.bounds, slot = -previewIndex - 1),
            preview.occlusions + previews.filter { it !== preview }.map { it.occlusionBounds ?: it.bounds })
                ?.let { if (preview.previewSettled) it else it.copy(covered = true, highlight = com.firstpick.ui.PickHighlight.NONE) }
    }
    return if (includeCovered) all else all.filterNot { it.covered }
}

internal fun NormalizedRect.overlaps(other: NormalizedRect): Boolean =
    x < other.right && right > other.x && y < other.bottom && bottom > other.y

internal fun DeckGuidanceOverlayModel.withMemoryMarks(
    placements: List<ArenaVisibleCard>,
    names: Map<Int, String>,
): DeckGuidanceOverlayModel {
    if (status != DeckGuidanceStatus.NEEDS_CHANGES) return copy(marks = emptyList())
    val advice = (changes + basicLandChanges).associateBy { it.name }
    val matcher = CanonicalCardNameMatcher(advice.keys)
    return copy(marks = placements.mapNotNull { card ->
        if (card.occluded || placements.any { it.zone == ArenaCardZone.PREVIEW && card.zone != ArenaCardZone.PREVIEW && card.bounds.overlaps(it.occlusionBounds ?: it.bounds) } || !card.bounds.isValid() || card.count !in 1..300) return@mapNotNull null
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
            ArenaCardZone.PACK, ArenaCardZone.PREVIEW -> null
        }
    })
}
