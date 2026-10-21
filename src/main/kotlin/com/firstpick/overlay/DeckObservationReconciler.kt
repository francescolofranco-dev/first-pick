package com.firstpick.overlay

/** Reconcile asynchronous total/row/diamond updates before forgetting hidden cards. */
internal class DeckObservationReconciler(baseline: List<DeckCardCount>) {
    private val reducer = DeckObservationReducer(baseline)
    private var trusted = reducer.initialState()
    private var pending: DeckObservationState? = null
    private var pendingSinceMs = 0L

    fun observe(frame: DeckObservationFrame, nowMs: Long): DeckObservationState {
        val candidate = reducer.reduce(trusted, frame)
        if (candidate.failure != null) {
            pending = null
            return candidate
        }
        if (candidate.unexplainedChange) {
            // Retry against the pre-edit state: when the other surface catches up,
            // its delta can explain the edit without throwing away scroll progress.
            if (candidate != pending) {
                pending = candidate
                pendingSinceMs = nowMs
            }
            if (nowMs - pendingSinceMs < SETTLE_MS) {
                // Neither old nor conflicting new directions are safe during a transaction.
                return reducer.initialState().copy(totalCards = frame.totalCards)
            }
        }
        trusted = candidate.copy(unexplainedChange = false)
        pending = null
        return trusted
    }

    fun invalidate() {
        trusted = reducer.initialState()
        pending = null
    }

    companion object {
        const val SETTLE_MS = 600L
    }
}
