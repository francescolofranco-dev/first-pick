package com.firstpick.draft

import com.firstpick.model.*
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class DraftHistoryTest {
    private fun completed(id: String? = "course-1") = DraftState(
        phase = DraftPhase.COMPLETE, format = DraftFormat.QUICK, setCode = "DSK",
        eventName = "QuickDraft_DSK", courseId = id, pool = listOf(1, 2, 2),
        savedDeck = SavedDeck(mapOf(2 to 2), mapOf(1 to 1)), picksPerChoice = 2,
        seen = mapOf((1 to 1) to listOf(1, 2, 3)),
    )

    @Test fun completedDraftsSurviveRestartAndReplayWithoutDuplicates() {
        val file = Files.createTempDirectory("fp-history").resolve("history.json")
        val store = DraftHistory(file)
        store.record(completed())
        val entry = store.history.value.single()
        val restarted = DraftHistory(file)
        assertEquals(entry, restarted.history.value.single())
        restarted.record(completed())
        restarted.record(completed().copy(savedDeck = SavedDeck(mapOf(1 to 1), mapOf(2 to 2))))
        assertEquals(entry.id, restarted.history.value.single().id)
        assertEquals(entry.completedAt, restarted.history.value.single().completedAt)
        assertEquals(mapOf(1 to 1), DraftHistory(file).history.value.single().state.savedDeck?.mainDeck)
        restarted.record(completed("course-2"))
        assertEquals(2, restarted.history.value.size)
    }

    @Test fun ignoresUnfinishedAndEmptyDraftsAndDeduplicatesUnidentifiedPools() {
        val store = DraftHistory(Files.createTempDirectory("fp-history").resolve("history.json"))
        store.record(completed().copy(phase = DraftPhase.DRAFTING))
        store.record(completed().copy(pool = emptyList()))
        assertTrue(store.history.value.isEmpty())
        store.record(completed(null))
        store.record(completed(null).copy(pool = listOf(2, 1, 2)))
        assertEquals(1, store.history.value.size)
    }

    @Test fun capturesCompletedDraftsDuringFastLogReplayBeforeAnotherEventReplacesThem() {
        val store = DraftHistory(Files.createTempDirectory("fp-history").resolve("history.json"))
        val tracker = DraftTracker().apply { onCompleted = store::record }
        for (card in listOf(1, 3)) {
            val payload = """{"EventName":"QuickDraft_DSK","DraftStatus":"Completed","PackNumber":2,"PickNumber":14,"DraftPack":[],"PickedCards":["$card","2"]}"""
            tracker.onLine("""{"CurrentModule":"BotDraft","Payload":${Json.encodeToString(payload)}}""")
        }
        assertEquals(2, store.history.value.size)
    }
}
