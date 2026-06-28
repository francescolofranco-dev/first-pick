package com.firstpick.draft

import com.firstpick.log.EventParser
import com.firstpick.model.DraftState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Folds a stream of log lines into a live [DraftState]. Pure and side-effect
 * free apart from the exposed [state] flow, so it is trivial to unit test by
 * feeding lines via [onLine].
 */
class DraftTracker(
    private val parser: EventParser = EventParser(),
) {
    private val _state = MutableStateFlow(DraftState())
    val state: StateFlow<DraftState> = _state.asStateFlow()

    /** Decode one line and fold it into the state. Returns true if it advanced. */
    fun onLine(line: String): Boolean {
        val event = parser.parse(line) ?: return false
        _state.update { it.reduce(event) }
        return true
    }

    /** Drive the tracker from a flow of log lines until the flow completes. */
    suspend fun consume(lines: Flow<String>) {
        lines.collect { onLine(it) }
    }

    /** Reset to the initial idle state (e.g. when exiting a demo draft). */
    fun reset() {
        _state.value = DraftState()
    }
}
