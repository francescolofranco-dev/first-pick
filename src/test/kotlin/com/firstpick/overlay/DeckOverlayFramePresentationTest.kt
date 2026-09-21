package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckOverlayFramePresentationTest {
    private val trusted = DeckGuidanceOverlayModel(
        DeckGuidanceStatus.NEEDS_CHANGES,
        marks = listOf(DeckGuidanceVisualMark.AddCard(NormalizedRect(0.05, 0.25, 0.12, 0.30))),
        remainingAdds = 2,
    )
    private fun acquired() = DeckOverlayPresentationReducer.reduce(
        DeckOverlayPresentationState.initial(), DeckOverlayFrameAttempt.Recognized(trusted), 0,
    ).state

    @Test
    fun lostEvidenceImmediatelyClearsInstructionsIncludingObscuredRows() {
        for (attempt in listOf(
            DeckOverlayFrameAttempt.ReaderFailure,
            DeckOverlayFrameAttempt.SupportedBuilderShell,
            DeckOverlayFrameAttempt.Recognized(DeckGuidanceOverlayModel(DeckGuidanceStatus.UNABLE)),
            DeckOverlayFrameAttempt.OutsideBuilder,
        )) {
            val next = DeckOverlayPresentationReducer.reduce(acquired(), attempt, 100).state
            assertEquals(DeckGuidanceStatus.READING, next.model.status)
            assertTrue(next.model.renderableMarks().isEmpty())
            assertEquals(0, next.model.remainingAdds)
        }
    }

    @Test
    fun obscuredBuilderCannotRetainOldInstructionsIndefinitely() {
        val first = DeckOverlayPresentationReducer.reduce(acquired(), DeckOverlayFrameAttempt.SupportedBuilderShell, 100)
        val later = DeckOverlayPresentationReducer.reduce(first.state, DeckOverlayFrameAttempt.SupportedBuilderShell, 10_000)
        assertEquals(DeckGuidanceStatus.UNABLE, later.state.model.status)
        assertTrue(later.state.model.renderableMarks().isEmpty())
        assertTrue(later.state.contentVisible)
    }

    @Test
    fun alternatingFailureReasonsCannotRestartTheStalenessClock() {
        val first = DeckOverlayPresentationReducer.reduce(acquired(), DeckOverlayFrameAttempt.ReaderFailure, 100)
        val later = DeckOverlayPresentationReducer.reduce(first.state, DeckOverlayFrameAttempt.SupportedBuilderShell, 2_000)
        assertEquals(DeckGuidanceStatus.UNABLE, later.state.model.status)
    }

    @Test
    fun slowReadsExpireWithoutWaitingForThreeAttempts() {
        val first = DeckOverlayPresentationReducer.reduce(acquired(), DeckOverlayFrameAttempt.ReaderFailure, 100)
        val later = DeckOverlayPresentationReducer.reduce(first.state, DeckOverlayFrameAttempt.ReaderFailure, 10_000)
        assertEquals(DeckGuidanceStatus.UNABLE, later.state.model.status)
        val recovered = DeckOverlayPresentationReducer.reduce(later.state, DeckOverlayFrameAttempt.Recognized(trusted), 10_200)
        assertEquals(trusted, recovered.state.model)
        assertEquals(listOf(DeckOverlayPresentationTransition.Recovered), recovered.transitions)
    }

    @Test
    fun partialReadingShowsOnlyItsOwnVerifiedMarksAndNoGlobalTotals() {
        val partial = trusted.copy(status = DeckGuidanceStatus.READING, remainingAdds = 0, verifiedCards = 2, expectedCards = 10)
        val result = DeckOverlayPresentationReducer.reduce(acquired(), DeckOverlayFrameAttempt.Recognized(partial), 100)
        assertEquals(partial, result.state.model)
        assertEquals("Checking deck · 2/10 cards verified", statusPillStyle(result.state.model).label)
        assertEquals(partial.marks, result.state.model.renderableMarks())
    }

    @Test
    fun fewerRecognizedRowsCannotResurrectOldMarks() {
        val partial = trusted.copy(marks = emptyList())
        val result = DeckOverlayPresentationReducer.reduce(acquired(), DeckOverlayFrameAttempt.Recognized(partial), 100)
        assertTrue(result.state.model.renderableMarks().isEmpty())
    }

    @Test
    fun leavingTheBuilderClearsMarksThenHidesAndCanReacquire() {
        var state = acquired()
        for (time in listOf(100L, 1_000L, 1_100L)) {
            state = DeckOverlayPresentationReducer.reduce(state, DeckOverlayFrameAttempt.OutsideBuilder, time).state
            assertTrue(state.model.renderableMarks().isEmpty())
        }
        assertFalse(state.contentVisible)
        state = DeckOverlayPresentationReducer.reduce(state, DeckOverlayFrameAttempt.ReaderFailure, 1_200).state
        assertFalse(state.contentVisible)
        state = DeckOverlayPresentationReducer.reduce(state, DeckOverlayFrameAttempt.Recognized(trusted), 1_300).state
        assertTrue(state.contentVisible)
        assertEquals(trusted, state.model)
    }

    @Test
    fun pollDelayTargetsFixedFrameStartsAndAlwaysLeavesRest() {
        assertEquals(300, deckOverlayPollDelayMs(0))
        assertEquals(180, deckOverlayPollDelayMs(120))
        assertEquals(50, deckOverlayPollDelayMs(250))
        assertEquals(50, deckOverlayPollDelayMs(2_000))
        assertEquals(300, deckOverlayPollDelayMs(-100))
    }
}
