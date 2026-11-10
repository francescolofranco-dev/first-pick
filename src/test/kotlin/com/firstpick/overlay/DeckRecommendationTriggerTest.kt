package com.firstpick.overlay

import com.firstpick.model.DraftPhase.*
import kotlin.test.*

class DeckRecommendationTriggerTest {
    @Test fun opensAfterFinalPickWhenBuildsArriveAndOnlyOnce() {
        val trigger = DeckRecommendationTrigger()
        assertFalse(trigger.observe(DRAFTING, "one", false, true, false))
        assertFalse(trigger.observe(COMPLETE, "one", false, true, false))
        assertTrue(trigger.observe(COMPLETE, "one", true, true, false))
        assertFalse(trigger.observe(COMPLETE, "one", true, true, false))
        trigger.observe(DRAFTING, "two", false, true, false)
        assertTrue(trigger.observe(COMPLETE, "two", true, true, false))
        // Older logs may identify consecutive drafts only by the same event name.
        trigger.observe(DRAFTING, "two", false, true, false)
        assertTrue(trigger.observe(COMPLETE, "two", true, true, false))
    }

    @Test fun recoveredDraftsAndSimulationsNeverOpenAutomatically() {
        val trigger = DeckRecommendationTrigger()
        assertFalse(trigger.observe(COMPLETE, "old", true, true, false))
        trigger.observe(DRAFTING, "sim", false, true, true)
        assertFalse(trigger.observe(COMPLETE, "sim", true, true, true))
        assertFalse(trigger.observe(COMPLETE, "sim", true, true, false))
    }

    @Test fun disabledPreferenceConsumesTheOfferWithoutOpeningLater() {
        val trigger = DeckRecommendationTrigger()
        trigger.observe(DRAFTING, "one", false, false, false)
        assertFalse(trigger.observe(COMPLETE, "one", true, false, false))
        assertFalse(trigger.observe(COMPLETE, "one", true, true, false))
    }

    @Test fun resettingOrChangingSessionCannotOfferAnUnobservedDraft() {
        val trigger = DeckRecommendationTrigger()
        trigger.observe(DRAFTING, "one", false, true, false)
        assertFalse(trigger.observe(COMPLETE, "two", true, true, false))
        trigger.observe(IDLE, null, false, true, false)
        assertFalse(trigger.observe(COMPLETE, "one", true, true, false))
    }
}
