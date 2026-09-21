package com.firstpick.log

import com.firstpick.model.DraftEvent
import com.firstpick.model.DraftPhase
import com.firstpick.model.DraftState
import com.firstpick.model.SavedDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SavedDeckTest {
    private val eventName = "PremierDraft_TMT_20260901"
    private val deck = """{"MainDeck":[{"cardId":123,"quantity":2},{"cardId":124,"quantity":38}],"Sideboard":[{"cardId":125,"quantity":1}]}"""
    private fun course(deckJson: String = deck) =
        """{"CourseId":"current","InternalEventName":"$eventName","CurrentModule":"DeckSelect","CardPool":["123","123","125"],"CourseDeck":$deckJson}"""

    @Test
    fun readsConfirmedMainDeckAndSideboardAlongsideTheDraftPool() {
        val parsed = assertIs<DraftEvent.Snapshot>(EventParser().parse(course()))
        assertEquals(listOf(123, 123, 125), parsed.pool)
        assertEquals(mapOf(123 to 2, 124 to 38), parsed.savedDeck?.mainDeck)
        assertEquals(mapOf(125 to 1), parsed.savedDeck?.sideboard)
        val state = DraftState().reduce(parsed)
        assertEquals(parsed.savedDeck, state.savedDeck)
    }

    @Test
    fun ignoresHistoricalCourseInventoriesAndUnconfirmedSubmissionRequests() {
        assertNull(EventParser().parse("""{"Courses":[${course()}]}"""))
        assertNull(EventParser().parse("""==> EventSetDeckV3 {"request":"{}"}"""))
    }

    @Test
    fun malformedCountsDoNotPoisonThePoolOrBecomeASavedDeck() {
        for (invalid in listOf(deck.replace("\"quantity\":2", "\"quantity\":-2"), deck.replace("\"cardId\":123", "\"cardId\":null"))) {
            val parsed = assertIs<DraftEvent.Snapshot>(EventParser().parse(course(invalid)))
            assertNull(parsed.savedDeck)
            assertEquals(3, parsed.pool.size)
        }
    }

    @Test
    fun savedDeckIsScopedToTheActiveCompletedEventAndClearedForANewCourse() {
        val saved = SavedDeck(mapOf(123 to 2), mapOf(125 to 1))
        val state = DraftState(phase = DraftPhase.COMPLETE, eventName = eventName, courseId = "current", pool = listOf(123, 123, 125))
        assertNull(state.reduce(DraftEvent.DeckSaved(eventName, saved, "other")).savedDeck)
        assertNull(state.reduce(DraftEvent.DeckSaved("OtherDraft", saved)).savedDeck)
        assertNull(state.copy(phase = DraftPhase.DRAFTING).reduce(DraftEvent.DeckSaved(eventName, saved)).savedDeck)
        val confirmed = state.reduce(DraftEvent.DeckSaved(eventName, saved, "current"))
        assertEquals(saved, confirmed.savedDeck)
        assertNull(confirmed.reduce(DraftEvent.EventJoined(eventName, "next")).savedDeck)
    }
}
