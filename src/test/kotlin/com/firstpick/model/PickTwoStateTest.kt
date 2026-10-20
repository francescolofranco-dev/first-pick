package com.firstpick.model

import com.firstpick.log.EventParser
import kotlin.test.*

class PickTwoStateTest {
    private val parser = EventParser()
    @Test fun eventNameAndPackHintDetectTwoAndSurviveLastSingleCard() {
        var state = DraftState().reduce(DraftEvent.EventJoined("PickTwoDraft_FRA_20260929"))
        assertEquals(2, state.picksPerChoice)
        state = state.reduce(DraftEvent.PackSeen("", 1, 1, listOf(1, 2, 3), picksPerChoice = 2))
        state = state.reduce(DraftEvent.PickMade(1, 1, listOf(1, 2)))
        assertEquals(listOf(1, 2), state.pool)
        state = state.reduce(DraftEvent.PackSeen("", 3, 8, listOf(3), picksPerChoice = 1))
        state = state.reduce(DraftEvent.PickMade(3, 8, listOf(3)))
        assertEquals(2, state.picksPerChoice)
        assertEquals(DraftPhase.COMPLETE, state.phase)
        state = state.reduce(DraftEvent.EventJoined("PremierDraft_FRA_20260929", "new-course"))
        assertEquals(1, state.picksPerChoice)
    }

    @Test fun unknownNamedModeCanBeDetectedFromLivePackCount() {
        val event = parser.parse("""DraftNotify {"EventName":"PremierDraft_FRA_20260929","SelfPack":1,"SelfPick":1,"NumCardsToPick":2,"PackCards":[1,2,3,4]}""")!!
        assertEquals(2, DraftState().reduce(event).picksPerChoice)
    }

    @Test fun regularDraftLastTwoCardBatchDoesNotSwitchModes() {
        val state = DraftState(pack = 3, packCards = listOf(1, 2))
            .reduce(DraftEvent.PickMade(3, 14, listOf(1, 2)))
        assertEquals(1, state.picksPerChoice)
    }

    @Test fun botSnapshotReadsCountEvenWithoutPickTwoEventName() {
        val line = """{"CurrentModule":"BotDraft","Payload":"{\"EventName\":\"QuickDraft_FRA_20260929\",\"NumCardsToPick\":2,\"DraftPack\":[\"1\",\"2\"],\"PickedCards\":[]}"}"""
        assertEquals(2, DraftState().reduce(parser.parse(line)!!).picksPerChoice)
    }
}
