package com.firstpick.model

enum class DraftFormat {
    PREMIER,
    TRADITIONAL,
    QUICK,
    SEALED,
    CUBE,
    UNKNOWN;

    companion object {
        fun fromEventName(eventName: String): DraftFormat {
            val n = eventName.lowercase().replace("_", "")
            return when {
                "traddraft" in n || "traditionaldraft" in n -> TRADITIONAL
                "premierdraft" in n || "premiumdraft" in n -> PREMIER
                "quickdraft" in n || "botdraft" in n -> QUICK
                "cube" in n -> CUBE
                "sealed" in n -> SEALED
                "draft" in n -> PREMIER
                else -> UNKNOWN
            }
        }
    }
}

private val SET_CODE_RE = Regex("_([A-Za-z0-9]{2,5})(?:_\\d{6,8}(?:_|$)|$)")

private val PREFIXED_DRAFT_SET_RE = Regex(
    "^Y\\d{2}_([A-Za-z0-9]{2,5})_(?:Bot|Premier|Traditional|Quick)_Draft(?:_|$)",
    RegexOption.IGNORE_CASE,
)

fun setCodeFromEventName(eventName: String): String? =
    (PREFIXED_DRAFT_SET_RE.find(eventName) ?: SET_CODE_RE.find(eventName))
        ?.groupValues?.get(1)?.uppercase()

/** Exact saved main deck; it is not a claim about unsaved builder edits. */
data class SavedDeck(val mainDeck: Map<Int, Int>, val sideboard: Map<Int, Int>)

sealed interface DraftEvent {
    data class DeckSaved(val eventName: String, val deck: SavedDeck, val courseId: String? = null) : DraftEvent

    data class EventJoined(
        val eventName: String,
        val courseId: String? = null,
    ) : DraftEvent

    data class Snapshot(
        val eventName: String,
        val pack: Int,
        val pick: Int,
        val packCards: List<Int>,
        val pool: List<Int>,
        val complete: Boolean,
        val courseId: String? = null,
        val savedDeck: SavedDeck? = null,
    ) : DraftEvent

    data class PackSeen(
        val eventName: String,
        val pack: Int,
        val pick: Int,
        val packCards: List<Int>,
        val draftId: String? = null,
    ) : DraftEvent

    data class PickMade(
        val pack: Int,
        val pick: Int,
        val cardIds: List<Int>,
    ) : DraftEvent
}

enum class DraftPhase { IDLE, DRAFTING, COMPLETE }

data class DraftState(
    val phase: DraftPhase = DraftPhase.IDLE,
    val format: DraftFormat = DraftFormat.UNKNOWN,
    val setCode: String? = null,
    val eventName: String? = null,
    val courseId: String? = null,
    val draftId: String? = null,
    val savedDeck: SavedDeck? = null,
    val pack: Int = 0,
    val pick: Int = 0,
    val packCards: List<Int> = emptyList(),
    val pool: List<Int> = emptyList(),
    /** False when some pool cards came from an unordered snapshot rather than PickMade events. */
    val poolOrderKnown: Boolean = true,
    val seen: Map<Pair<Int, Int>, List<Int>> = emptyMap(),
) {
    fun reduce(event: DraftEvent): DraftState = when (event) {
        is DraftEvent.DeckSaved -> if (
            phase == DraftPhase.COMPLETE && event.eventName == eventName &&
            (event.courseId == null || event.courseId == courseId)
        ) copy(savedDeck = event.deck) else this

        is DraftEvent.EventJoined -> {
            val resettingForNewEvent = isNewEvent(event.eventName, event.courseId)
            val base = if (resettingForNewEvent) DraftState() else this
            base.copy(
                format = DraftFormat.fromEventName(event.eventName),
                setCode = setCodeFromEventName(event.eventName) ?: base.setCode,
                eventName = event.eventName,
                courseId = event.courseId ?: base.courseId,
            )
        }

        is DraftEvent.Snapshot -> {
            val resettingForNewEvent = isNewEvent(event.eventName, event.courseId)
            val base = if (resettingForNewEvent) DraftState()
                else if (!event.complete && startsAgain(event.pack, event.pick)) freshDraft() else this
            val reconciled = base.reconcilePool(event.pool)
            base.copy(
                phase = if (event.complete) DraftPhase.COMPLETE else DraftPhase.DRAFTING,
                format = if (event.eventName.isNotEmpty()) DraftFormat.fromEventName(event.eventName) else base.format,
                setCode = (if (event.eventName.isNotEmpty()) setCodeFromEventName(event.eventName) else null) ?: base.setCode,
                eventName = if (event.eventName.isNotEmpty()) event.eventName else base.eventName,
                pack = event.pack,
                pick = event.pick,
                courseId = event.courseId ?: base.courseId,
                packCards = event.packCards,
                pool = reconciled.cards,
                poolOrderKnown = base.poolOrderKnown && reconciled.addedCardCount <= 1,
                seen = base.recordSeen(event.pack, event.pick, event.packCards),
                savedDeck = event.savedDeck ?: base.savedDeck,
            )
        }

        is DraftEvent.PackSeen -> {
            val resettingForNewEvent = event.eventName.isNotEmpty() && eventName != null && eventName != event.eventName
            val base = if (resettingForNewEvent) DraftState()
                else if ((event.draftId != null && draftId != null && event.draftId != draftId) ||
                    ((event.draftId == null || draftId == null) && startsAgain(event.pack, event.pick))) freshDraft() else this
            base.copy(
                phase = DraftPhase.DRAFTING,
                draftId = event.draftId ?: base.draftId,
                format = if (event.eventName.isNotEmpty()) DraftFormat.fromEventName(event.eventName) else base.format,
                setCode = (if (event.eventName.isNotEmpty()) setCodeFromEventName(event.eventName) else null) ?: base.setCode,
                eventName = if (event.eventName.isNotEmpty()) event.eventName else base.eventName,
                pack = event.pack,
                pick = event.pick,
                packCards = event.packCards,
                seen = base.recordSeen(event.pack, event.pick, event.packCards),
            )
        }

        is DraftEvent.PickMade -> {
            val newPool = pool + event.cardIds
            val isConfirmedFinalPick = pack >= 3 &&
                packCards.isNotEmpty() &&
                event.cardIds.isNotEmpty() &&
                packCards.size == event.cardIds.size
            val newPhase = if (isConfirmedFinalPick) DraftPhase.COMPLETE
                else if (phase == DraftPhase.IDLE) DraftPhase.DRAFTING
                else phase
            copy(
                phase = newPhase,
                pack = if (event.pack > 0) event.pack else pack,
                pick = if (event.pick > 0) event.pick else pick,
                pool = newPool,


                packCards = emptyList()
            )
        }
    }

    private fun isNewEvent(name: String, id: String?): Boolean =
        (name.isNotEmpty() && eventName != null && eventName != name) ||
            (id != null && courseId != null && courseId != id)

    private fun startsAgain(nextPack: Int, nextPick: Int): Boolean =
        nextPack == 1 && nextPick == 1 &&
            (phase == DraftPhase.COMPLETE || pack > 1 || pick > 1)

    private fun freshDraft() = DraftState(format = format, setCode = setCode, eventName = eventName)

    private fun recordSeen(pack: Int, pick: Int, cards: List<Int>): Map<Pair<Int, Int>, List<Int>> =
        if (cards.isEmpty()) seen else seen + (Pair(pack, pick) to cards)

    /**
     * Arena's PickedCards snapshots are authoritative multisets, but their order is unstable.
     * Retain the chronology learned from PickMade events and deterministically append any
     * occurrences first discovered in a snapshot.
     */
    private fun reconcilePool(snapshot: List<Int>): ReconciledPool {
        val remaining = snapshot.groupingBy { it }.eachCount().toMutableMap()
        val reconciled = buildList(snapshot.size) {
            for (cardId in pool) {
                val count = remaining[cardId] ?: continue
                add(cardId)
                if (count == 1) remaining.remove(cardId) else remaining[cardId] = count - 1
            }
            for ((cardId, count) in remaining.toSortedMap()) {
                repeat(count) { add(cardId) }
            }
        }
        return ReconciledPool(
            cards = reconciled,
            addedCardCount = remaining.values.sum(),
        )
    }

    private data class ReconciledPool(
        val cards: List<Int>,
        /** One multiset delta has an unambiguous latest position; two or more do not. */
        val addedCardCount: Int,
    )
}
