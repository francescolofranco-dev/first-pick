package com.firstpick.model

/** MTGA limited event families we care about. */
enum class DraftFormat {
    PREMIER,
    TRADITIONAL,
    QUICK,
    SEALED,
    CUBE,
    UNKNOWN;

    companion object {
        /** Derive the format from an Arena InternalEventName, e.g. "QuickDraftEmblem_SOS_20260611". */
        fun fromEventName(eventName: String): DraftFormat {
            val n = eventName.lowercase().replace("_", "")
            return when {
                "traddraft" in n || "traditionaldraft" in n -> TRADITIONAL
                "premierdraft" in n -> PREMIER
                "quickdraft" in n || "botdraft" in n -> QUICK
                "cube" in n -> CUBE
                "sealed" in n -> SEALED
                "draft" in n -> PREMIER // generic human-draft fallback
                else -> UNKNOWN
            }
        }
    }
}

/** Matches the 3–5 char set code wedged before the YYYYMMDD date in an event name. */
private val SET_CODE_RE = Regex("_([A-Za-z0-9]{2,5})_\\d{6,8}")

/** "QuickDraftEmblem_SOS_20260611" -> "SOS". Null if the name doesn't carry a set code. */
fun setCodeFromEventName(eventName: String): String? =
    SET_CODE_RE.find(eventName)?.groupValues?.get(1)?.uppercase()

/** Low-level signals decoded from a single log line. Pack/pick are 1-indexed. */
sealed interface DraftEvent {
    /** A complete state snapshot (MTGA Quick/Bot draft logs every pick this way). */
    data class Snapshot(
        val eventName: String,
        val pack: Int,
        val pick: Int,
        val packCards: List<Int>,
        val pool: List<Int>,
        val complete: Boolean,
    ) : DraftEvent

    /** A pack offered to the player (human Premier/Traditional draft). */
    data class PackSeen(
        val eventName: String,
        val pack: Int,
        val pick: Int,
        val packCards: List<Int>,
    ) : DraftEvent

    /** Cards the player locked in (human Premier/Traditional draft). */
    data class PickMade(
        val pack: Int,
        val pick: Int,
        val cardIds: List<Int>,
    ) : DraftEvent
}

enum class DraftPhase { IDLE, DRAFTING, COMPLETE }

/** Immutable, UI-facing reconstruction of the current draft. */
data class DraftState(
    val phase: DraftPhase = DraftPhase.IDLE,
    val format: DraftFormat = DraftFormat.UNKNOWN,
    val setCode: String? = null,
    val eventName: String? = null,
    val pack: Int = 0,
    val pick: Int = 0,
    val packCards: List<Int> = emptyList(),
    val pool: List<Int> = emptyList(),
    /** Every pack offered, keyed by (pack, pick) — feeds open-lane signal detection. */
    val seen: Map<Pair<Int, Int>, List<Int>> = emptyMap(),
) {
    /** Pure reducer: fold one decoded event into the next state. */
    fun reduce(event: DraftEvent): DraftState = when (event) {
        is DraftEvent.Snapshot -> {
            // Snapshots are authoritative — adopt them wholesale.
            val resettingForNewEvent = eventName != null && eventName != event.eventName
            val base = if (resettingForNewEvent) DraftState() else this
            base.copy(
                phase = if (event.complete) DraftPhase.COMPLETE else DraftPhase.DRAFTING,
                format = DraftFormat.fromEventName(event.eventName),
                setCode = setCodeFromEventName(event.eventName) ?: base.setCode,
                eventName = event.eventName,
                pack = event.pack,
                pick = event.pick,
                packCards = event.packCards,
                pool = event.pool,
                seen = base.recordSeen(event.pack, event.pick, event.packCards),
            )
        }

        is DraftEvent.PackSeen -> {
            val resettingForNewEvent = eventName != null && eventName != event.eventName
            val base = if (resettingForNewEvent) DraftState() else this
            base.copy(
                phase = DraftPhase.DRAFTING,
                format = DraftFormat.fromEventName(event.eventName),
                setCode = setCodeFromEventName(event.eventName) ?: base.setCode,
                eventName = event.eventName,
                pack = event.pack,
                pick = event.pick,
                packCards = event.packCards,
                seen = base.recordSeen(event.pack, event.pick, event.packCards),
            )
        }

        is DraftEvent.PickMade ->
            // Incremental (human draft): move the picked cards into the pool.
            copy(pool = pool + event.cardIds)
    }

    /** Record a pack we were offered (idempotent on repeated snapshots of the same pick). */
    private fun recordSeen(pack: Int, pick: Int, cards: List<Int>): Map<Pair<Int, Int>, List<Int>> =
        if (cards.isEmpty()) seen else seen + (Pair(pack, pick) to cards)
}
