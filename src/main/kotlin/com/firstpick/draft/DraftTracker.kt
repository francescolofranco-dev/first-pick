package com.firstpick.draft

import com.firstpick.log.EventParser
import com.firstpick.model.DraftState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DraftTracker(
    private val parser: EventParser = EventParser(),
) {
    private val _state = MutableStateFlow(DraftState())
    val state: StateFlow<DraftState> = _state.asStateFlow()

    fun onLine(line: String): Boolean {
        val event = parser.parse(line) ?: return false
        _state.update { it.reduce(event) }
        return true
    }

    suspend fun consume(lines: Flow<String>) {
        lines.collect { onLine(it) }
    }

    fun reset() {
        _state.value = DraftState()
    }
}
