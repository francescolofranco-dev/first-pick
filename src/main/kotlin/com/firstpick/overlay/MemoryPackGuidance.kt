package com.firstpick.overlay

import com.firstpick.cards.ArenaCardNameResolver
import com.firstpick.cards.isBasicLandName

internal sealed interface MemoryPackValidation {
    data class Ready(val cards: List<OverlayCard>) : MemoryPackValidation
    data class Rejected(val reason: String, val settling: Boolean = false) : MemoryPackValidation
}

/** Complete multisets prevent a grade from following a different card or draft. */
internal fun validateMemoryPack(
    raw: ArenaMemoryPack,
    cards: List<OverlayCard>,
    pool: List<DeckCardCount>,
    names: Map<Int, String>,
): MemoryPackValidation {
    if (cards.isEmpty() || cards.any { it.grpId <= 0 } ||
        raw.cards.groupingBy { it }.eachCount() != cards.groupingBy { it.grpId }.eachCount()) {
        return MemoryPackValidation.Rejected("Waiting for recommendations for Arena's complete current pack.")
    }
    val baseline = pool.filterNot { it.isBasicLand || isBasicLandName(it.name) }
    if (baseline.isEmpty()) {
        if (raw.picked.any { names[it.grpId]?.let(::isBasicLandName) != true })
            return MemoryPackValidation.Rejected("Waiting for this draft's current card pool.")
    } else if (validateMemoryDeck(ArenaMemoryDeck(raw.editorSession, emptyList(), raw.picked, 0), pool, names)
        !is MemoryDeckValidation.Ready) {
        return MemoryPackValidation.Rejected("Waiting for this draft's current card pool.")
    }
    val available = cards.groupBy { it.grpId }.mapValues { it.value.sortedBy(OverlayCard::originalIndex).toMutableList() }
    return MemoryPackValidation.Ready(raw.cards.map { available.getValue(it).removeAt(0) })
}

internal class MemoryPackGuidanceSession(
    private val resolver: (Set<Int>) -> Map<Int, String> = ArenaCardNameResolver()::resolveAll,
) {
    private var candidate: ArenaMemoryPack? = null
    private var candidateAtMs = 0L
    private var confirmed = false

    fun observe(raw: ArenaMemoryPack, cards: List<OverlayCard>, pool: List<DeckCardCount>, nowMs: Long): MemoryPackValidation {
        val current = validateMemoryPack(raw, cards, pool, resolver(raw.picked.mapTo(HashSet()) { it.grpId }))
        if (current !is MemoryPackValidation.Ready) { invalidate(); return current }
        val identity = raw.copy(geometry = null)
        if (candidate != identity || nowMs - candidateAtMs > 2000) {
            candidate = identity; candidateAtMs = nowMs; confirmed = false
            return MemoryPackValidation.Rejected("Checking the new pack in Arena.", settling = true)
        }
        if (!confirmed && nowMs - candidateAtMs < 120) return MemoryPackValidation.Rejected("Checking the new pack in Arena.", settling = true)
        confirmed = true
        candidateAtMs = nowMs
        return current
    }
    fun invalidate() { candidate = null; candidateAtMs = 0; confirmed = false }
}
