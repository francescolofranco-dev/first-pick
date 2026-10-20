package com.firstpick.draft

import com.firstpick.log.EventParser
import com.firstpick.model.DraftState
import com.firstpick.model.DraftEvent
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

    private var ignoringUnsupportedDraft = false

    fun onLine(line: String): Boolean {
        if (parser.isUnsupportedDraftJoin(line)) {
            ignoringUnsupportedDraft = true
            _state.value = DraftState()
            return false
        }
        val event = parser.parse(line) ?: return false
        val namedDraft = when (event) {
            is DraftEvent.EventJoined -> event.eventName.isNotBlank()
            is DraftEvent.PackSeen -> event.eventName.isNotBlank()
            is DraftEvent.Snapshot -> event.eventName.isNotBlank()
            else -> false
        }
        if (namedDraft) ignoringUnsupportedDraft = false
        if (ignoringUnsupportedDraft) return false
        _state.update { it.reduce(event) }
        return true
    }

    suspend fun consume(lines: Flow<String>) {
        lines.collect { onLine(it) }
    }

    fun reset(keepEventContext: Boolean = false) {
        if (!keepEventContext) ignoringUnsupportedDraft = false
        _state.update {
            if (keepEventContext) DraftState(format = it.format, setCode = it.setCode, eventName = it.eventName)
            else DraftState()
        }
    }
}
