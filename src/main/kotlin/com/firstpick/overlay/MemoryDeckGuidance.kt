package com.firstpick.overlay

import com.firstpick.cards.ArenaCardNameResolver
import com.firstpick.cards.isBasicLandName

internal sealed interface MemoryDeckValidation {
    data class Ready(val editorSession: String, val deck: DeckSnapshot) : MemoryDeckValidation
    data class Rejected(val reason: String) : MemoryDeckValidation
}

/** Full pool equality binds a live editor to this draft; a shared card name is insufficient. */
internal fun validateMemoryDeck(
    snapshot: ArenaMemoryDeck,
    draftPool: List<DeckCardCount>,
    names: Map<Int, String>,
): MemoryDeckValidation {
    val baseline = draftPool.filterNot { it.isBasicLand || isBasicLandName(it.name) }
        .groupingBy(DeckCardCount::name).fold(0) { sum, card -> sum + card.count }
    if (baseline.isEmpty()) return MemoryDeckValidation.Rejected("Waiting for this draft's card pool.")
    val matcher = CanonicalCardNameMatcher(baseline.keys)
    fun resolve(card: ArenaCardQuantity): DeckCardCount? {
        val name = names[card.grpId] ?: return null
        if (isBasicLandName(name)) return DeckCardCount(name, card.count, isBasicLand = true)
        // Catalog identities are complete names. Never use an ellipsis/prefix as identity.
        if (name.trim().endsWith('…') || name.trim().endsWith("...")) return null
        val canonical = (matcher.match(name) as? CanonicalCardNameMatch.Matched)?.name ?: return null
        return DeckCardCount(canonical, card.count)
    }
    val pool = snapshot.pool.map { resolve(it) ?: return MemoryDeckValidation.Rejected(
        "Open this draft's deck in Arena; its card pool could not be matched.",
    ) }
    val poolCounts = pool.filterNot { it.isBasicLand }.groupingBy(DeckCardCount::name)
        .fold(0) { sum, card -> sum + card.count }
    if (poolCounts != baseline) return MemoryDeckValidation.Rejected("Open this draft's deck in Arena; a different card pool is selected.")
    val cards = snapshot.main.map { resolve(it) ?: return MemoryDeckValidation.Rejected("A live card identity could not be verified.") }
    val mainCounts = cards.filterNot { it.isBasicLand }.groupingBy(DeckCardCount::name)
        .fold(0) { sum, card -> sum + card.count }
    if (mainCounts.any { (name, count) -> count > (baseline[name] ?: 0) } || cards.sumOf { it.count } != snapshot.total) {
        return MemoryDeckValidation.Rejected("Arena's deck is updating. Waiting for consistent counts.")
    }
    return MemoryDeckValidation.Ready(snapshot.editorSession, DeckSnapshot(cards, snapshot.total))
}

/** Require a second stable poll before publishing an edit; never retain old instructions across a gap. */
internal class MemoryDeckGuidanceSession(
    private val resolver: (Set<Int>) -> Map<Int, String> = ArenaCardNameResolver()::resolveAll,
) {
    private var candidate: MemoryDeckValidation.Ready? = null
    private var candidateAtMs = 0L

    fun observe(raw: ArenaMemoryDeck, pool: List<DeckCardCount>, nowMs: Long): MemoryDeckValidation {
        val ids = (raw.main + raw.pool).mapTo(HashSet()) { it.grpId }
        val current = validateMemoryDeck(raw, pool, resolver(ids))
        if (current !is MemoryDeckValidation.Ready) { invalidate(); return current }
        if (candidate != current || nowMs - candidateAtMs > 2000) {
            candidate = current; candidateAtMs = nowMs
            return MemoryDeckValidation.Rejected("Checking recent changes in Arena.")
        }
        if (nowMs - candidateAtMs < 120) return MemoryDeckValidation.Rejected("Checking recent changes in Arena.")
        candidateAtMs = nowMs
        return current
    }

    fun invalidate() { candidate = null; candidateAtMs = 0 }
}

internal fun memoryGuidanceModel(target: List<DeckCardCount>, deck: DeckSnapshot): DeckGuidanceOverlayModel {
    val guidance = DeckGuidance.evaluate(target, DeckReadState.Ready(deck))
    val expected = target.filterNot { it.isBasicLand || isBasicLandName(it.name) }.sumOf { it.count }
    return DeckGuidanceOverlayModel(
        status = guidance.status,
        remainingAdds = guidance.remainingAdds,
        remainingRemovals = guidance.remainingRemovals,
        basicLandDelta = guidance.basicLandDelta,
        verifiedCards = expected,
        expectedCards = expected,
        changes = guidance.cards.filter { it.action != DeckGuidanceAction.OK },
    )
}

/** Geometry can expire independently; its OCR counts never replace memory quantities. */
internal fun memoryGuidanceMarks(
    model: DeckGuidanceOverlayModel,
    deck: DeckSnapshot,
    pool: List<DeckCardCount>,
    screen: DeckBuilderScreenObservation,
    aspectRatio: Double,
): List<DeckGuidanceVisualMark> {
    if (screen.deckSize != deck.totalCards || !isSupportedLimitedDeckBuilder(screen)) return emptyList()
    val counts = deck.cards.filterNot { it.isBasicLand }.associate { it.name to it.count }
    val baseline = pool.filterNot { it.isBasicLand || isBasicLandName(it.name) }
        .groupingBy(DeckCardCount::name).fold(0) { sum, card -> sum + card.count }
    val rows = DeckRowNameResolver(baseline).resolve(screen.deckRows, baseline.mapValues { (name, _) -> counts[name] ?: 0 })
    val matcher = CanonicalCardNameMatcher(baseline.keys)
    return buildList {
        for (row in screen.deckRows) {
            val name = rows[row] ?: continue
            val action = model.changes.firstOrNull { it.name == name }?.action ?: continue
            // Reject geometry from an earlier duplicate quantity even if the overall total matches.
            if (counts[name] != row.count) continue
            action.toVisualMark(row.bounds, row.count)?.let { mark ->
                add(if (mark is DeckGuidanceVisualMark.AddCard) mark.copy(inDeckList = true) else mark)
            }
        }
        for (card in screen.poolCardTitles) {
            val name = (matcher.match(card.cardName) as? CanonicalCardNameMatch.Matched)?.name ?: continue
            if (model.changes.none { it.name == name && it.action == DeckGuidanceAction.ADD }) continue
            val bounds = DeckPoolGeometry.cardBounds(card, aspectRatio) ?: continue
            add(DeckGuidanceVisualMark.AddCard(bounds))
        }
    }
}
