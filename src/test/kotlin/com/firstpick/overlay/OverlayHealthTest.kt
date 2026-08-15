package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayHealthTest {
    private val active = OverlayHealth(OverlayHealthState.ACTIVE, "Synced")

    @Test
    fun resolverPrioritizesConnectionWindowSafetyAndForeground() {
        assertEquals(
            OverlayHealthState.WAITING_FOR_ARENA,
            resolveOverlayHealth(
                arenaAvailable = false,
                arenaFrontmost = false,
                clickThroughFailed = true,
                work = active,
            ).state,
        )
        assertEquals(
            OverlayHealthState.CLICK_THROUGH_FAILED,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = true,
                clickThroughFailed = true,
                work = active,
            ).state,
        )
        assertEquals(
            OverlayHealthState.INACTIVE,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = false,
                clickThroughFailed = false,
                work = active,
            ).state,
        )
        assertEquals(
            active,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = false,
                clickThroughFailed = false,
                work = active,
                allowInBackground = true,
            ),
        )
    }

    @Test
    fun failedPackRecognitionDistinguishesCaptureFromLayout() {
        assertEquals(
            OverlayHealthState.CAPTURE_UNAVAILABLE,
            packRecognitionFailureHealth(capturedFrames = 0).state,
        )
        assertEquals(
            OverlayHealthState.UNSUPPORTED_LAYOUT,
            packRecognitionFailureHealth(capturedFrames = 1).state,
        )
    }

    @Test
    fun deckFrameHealthCoversReadingActiveUnsupportedAndInactiveStates() {
        val initial = DeckOverlayPresentationState.initial()
        assertEquals(
            OverlayHealthState.CAPTURE_UNAVAILABLE,
            deckOverlayWorkHealth(DeckOverlayFrameAttempt.ReaderFailure, initial).state,
        )
        assertEquals(
            OverlayHealthState.READING,
            deckOverlayWorkHealth(DeckOverlayFrameAttempt.SupportedBuilderShell, initial).state,
        )
        assertEquals(
            OverlayHealthState.UNSUPPORTED_LAYOUT,
            deckOverlayWorkHealth(DeckOverlayFrameAttempt.UnsupportedLayout, initial).state,
        )
        assertEquals(
            OverlayHealthState.READING,
            deckOverlayWorkHealth(DeckOverlayFrameAttempt.OutsideBuilder, initial).state,
        )
        assertEquals(
            OverlayHealthState.INACTIVE,
            deckOverlayWorkHealth(
                DeckOverlayFrameAttempt.OutsideBuilder,
                initial.copy(contentVisible = false),
            ).state,
        )

        val recognized = DeckOverlayFrameAttempt.Recognized(
            DeckGuidanceOverlayModel(DeckGuidanceStatus.MATCHES),
        )
        val acquired = DeckOverlayPresentationReducer.reduce(initial, recognized, nowMs = 0).state
        assertEquals(OverlayHealthState.ACTIVE, deckOverlayWorkHealth(recognized, acquired).state)
    }

    @Test
    fun onlyActionableFailuresNeedAttention() {
        assertFalse(active.needsAttention)
        assertFalse(OverlayHealth(OverlayHealthState.READING).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.CAPTURE_UNAVAILABLE).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.UNSUPPORTED_LAYOUT).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.CLICK_THROUGH_FAILED).needsAttention)
    }
}
