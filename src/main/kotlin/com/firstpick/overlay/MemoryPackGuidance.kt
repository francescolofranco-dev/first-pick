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
    if (cards.isEmpty() || cards.any { it.grpId <= 0 } || raw.cards.size != cards.size) {
        return MemoryPackValidation.Rejected("Waiting for recommendations for Arena's complete current pack.")
    }
    val available = cards.sortedBy(OverlayCard::originalIndex).toMutableList()
    val ordered = raw.cards.map { id ->
        var index = available.indexOfFirst { it.grpId == id }
        if (index < 0) {
            // Arena can display the player's preferred basic-land printing instead
            // of the printing offered by the draft log. Verify both catalog names.
            val basic = names[id]?.takeIf(::isBasicLandName)
            if (basic != null) index = available.indexOfFirst { names[it.grpId] == basic }
        }
        if (index < 0) return MemoryPackValidation.Rejected("Waiting for recommendations for Arena's complete current pack.")
        available.removeAt(index).copy(grpId = id)
    }
    val baseline = pool.filterNot { it.isBasicLand || isBasicLandName(it.name) }
    if (baseline.isEmpty()) {
        if (raw.picked.any { names[it.grpId]?.let(::isBasicLandName) != true })
            return MemoryPackValidation.Rejected("Waiting for this draft's current card pool.")
    } else if (validateMemoryDeck(ArenaMemoryDeck(raw.editorSession, emptyList(), raw.picked, 0), pool, names)
        !is MemoryDeckValidation.Ready) {
        return MemoryPackValidation.Rejected("Waiting for this draft's current card pool.")
    }
    return MemoryPackValidation.Ready(ordered)
}

internal class MemoryPackGuidanceSession(
    private val resolver: (Set<Int>) -> Map<Int, String> = ArenaCardNameResolver()::resolveAll,
) {
    private var candidate: ArenaMemoryPack? = null
    private var candidateAtMs = 0L
    private var confirmed = false

    fun observe(raw: ArenaMemoryPack, cards: List<OverlayCard>, pool: List<DeckCardCount>, nowMs: Long): MemoryPackValidation {
        val ids = (raw.picked.map { it.grpId } + raw.cards + cards.map { it.grpId }).filter { it > 0 }.toSet()
        val current = validateMemoryPack(raw, cards, pool, resolver(ids))
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
