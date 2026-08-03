package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeckOverlayFramePresentationTest {
    private val cardBounds = NormalizedRect(0.05, 0.25, 0.12, 0.30)
    private val trustedModel = DeckGuidanceOverlayModel(
        status = DeckGuidanceStatus.NEEDS_CHANGES,
        marks = listOf(DeckGuidanceVisualMark.AddCard(cardBounds)),
    )

    @Test
    fun readerFailureRetainsTheLastGoodMarksUntilElapsedGraceExpires() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        assertEquals(listOf(DeckOverlayPresentationTransition.Acquired), acquired.transitions)

        val firstFailure = reduce(acquired.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 100)
        assertEquals(trustedModel, firstFailure.state.model)
        assertTrue(firstFailure.state.contentVisible)
        assertEquals(
            listOf(
                DeckOverlayPresentationTransition.Unreadable(
                    DeckOverlayUnreadableReason.CAPTURE_OR_OCR,
                    retainingLastGood = true,
                ),
            ),
            firstFailure.transitions,
        )

        val stillInGrace = reduce(
            firstFailure.state,
            DeckOverlayFrameAttempt.ReaderFailure,
            nowMs = 100 + DeckOverlayPresentationReducer.UNREADABLE_GRACE_MS - 1,
        )
        assertEquals(trustedModel, stillInGrace.state.model)
        assertTrue(stillInGrace.transitions.isEmpty())

        val expired = reduce(
            stillInGrace.state,
            DeckOverlayFrameAttempt.ReaderFailure,
            nowMs = 100 + DeckOverlayPresentationReducer.UNREADABLE_GRACE_MS,
        )
        assertEquals(DeckGuidanceStatus.UNABLE, expired.state.model.status)
        assertTrue(expired.state.model.marks.isEmpty())
        assertEquals(
            listOf(DeckOverlayPresentationTransition.GraceExpired(DeckOverlayUnreadableReason.CAPTURE_OR_OCR)),
            expired.transitions,
        )

        val repeated = reduce(expired.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 10_000)
        assertTrue(repeated.transitions.isEmpty())
    }

    @Test
    fun reducerUnableAlsoRetainsTheLastGoodMarksAndRecoversCleanly() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val unableModel = DeckGuidanceOverlayModel(DeckGuidanceStatus.UNABLE)
        val ambiguous = reduce(
            acquired.state,
            DeckOverlayFrameAttempt.Recognized(unableModel),
            nowMs = 200,
        )

        assertEquals(trustedModel, ambiguous.state.model)
        assertEquals(DeckOverlayUnreadableReason.GUIDANCE_UNAVAILABLE, ambiguous.state.unreadableReason)

        val stillInGrace = reduce(
            ambiguous.state,
            DeckOverlayFrameAttempt.Recognized(unableModel),
            nowMs = 200 + DeckOverlayPresentationReducer.UNREADABLE_GRACE_MS - 1,
        )
        assertEquals(trustedModel, stillInGrace.state.model)

        val expired = reduce(
            stillInGrace.state,
            DeckOverlayFrameAttempt.Recognized(unableModel),
            nowMs = 200 + DeckOverlayPresentationReducer.UNREADABLE_GRACE_MS,
        )
        assertEquals(DeckGuidanceStatus.UNABLE, expired.state.model.status)
        assertEquals(
            listOf(DeckOverlayPresentationTransition.GraceExpired(DeckOverlayUnreadableReason.GUIDANCE_UNAVAILABLE)),
            expired.transitions,
        )

        val matching = DeckGuidanceOverlayModel(DeckGuidanceStatus.MATCHES)
        val recovered = reduce(
            expired.state,
            DeckOverlayFrameAttempt.Recognized(matching),
            nowMs = 2_000,
        )
        assertEquals(matching, recovered.state.model)
        assertEquals(matching, recovered.state.lastGoodModel)
        assertEquals(listOf(DeckOverlayPresentationTransition.Recovered), recovered.transitions)
        assertEquals(null, recovered.state.unreadableSinceMs)
    }

    @Test
    fun supportedBuilderShellRetainsMarksIndefinitelyWhenRowsAreObscured() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val hovered = reduce(
            acquired.state,
            DeckOverlayFrameAttempt.SupportedBuilderShell,
            nowMs = 100,
        )
        val muchLater = reduce(
            hovered.state,
            DeckOverlayFrameAttempt.SupportedBuilderShell,
            nowMs = 100 + DeckOverlayPresentationReducer.UNREADABLE_GRACE_MS * 100,
        )

        assertEquals(trustedModel, hovered.state.model)
        assertEquals(trustedModel, muchLater.state.model)
        assertTrue(muchLater.state.contentVisible)
        assertTrue(muchLater.state.builderAcquired)
        assertTrue(muchLater.transitions.isEmpty())
        assertEquals(
            listOf(
                DeckOverlayPresentationTransition.Unreadable(
                    DeckOverlayUnreadableReason.ROWS_OBSCURED,
                    retainingLastGood = true,
                ),
            ),
            hovered.transitions,
        )
    }

    @Test
    fun degradedRecognizedSubsetDoesNotReplaceTrustedMarksAtTheSameScrollPosition() {
        val rowMark = DeckGuidanceVisualMark.RemoveRow(
            bounds = NormalizedRect(0.785, 0.30, 0.207, 0.036),
            currentCount = 1,
        )
        val completeModel = DeckGuidanceOverlayModel(
            status = DeckGuidanceStatus.NEEDS_CHANGES,
            marks = listOf(rowMark),
            remainingRemovals = 5,
        )
        val degradedModel = completeModel.copy(marks = emptyList())
        val completeLayout = layout(
            "Go Nuts!" to 0.22,
            "Rapid Rescue" to 0.27,
            "Serpent Specialist" to 0.31,
            "Agents of HYDRA" to 0.35,
        )
        val degradedLayout = layout(
            "Go Nuts!" to 0.22,
            "Serpent Specialist" to 0.31,
        )
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(completeModel, completeLayout),
            nowMs = 0,
        )
        val obscured = reduce(acquired.state, DeckOverlayFrameAttempt.SupportedBuilderShell, nowMs = 2_000)
        val sparseRecovery = reduce(
            obscured.state,
            DeckOverlayFrameAttempt.Recognized(degradedModel, degradedLayout),
            nowMs = 4_000,
        )

        assertEquals(completeModel, sparseRecovery.state.model)
        assertEquals(completeModel, sparseRecovery.state.lastGoodModel)
        assertEquals(DeckOverlayUnreadableReason.ROWS_OBSCURED, sparseRecovery.state.unreadableReason)
        assertTrue(sparseRecovery.transitions.isEmpty())

        val recovered = reduce(
            sparseRecovery.state,
            DeckOverlayFrameAttempt.Recognized(completeModel, completeLayout),
            nowMs = 6_000,
        )
        assertEquals(completeModel, recovered.state.model)
        assertEquals(listOf(DeckOverlayPresentationTransition.Recovered), recovered.transitions)
    }

    @Test
    fun aScrolledRecognizedFrameReplacesMarksInsteadOfRetainingStaleRowPositions() {
        val completeModel = DeckGuidanceOverlayModel(
            status = DeckGuidanceStatus.NEEDS_CHANGES,
            marks = listOf(
                DeckGuidanceVisualMark.RemoveRow(
                    bounds = NormalizedRect(0.785, 0.30, 0.207, 0.036),
                    currentCount = 1,
                ),
            ),
            remainingRemovals = 5,
        )
        val scrolledModel = completeModel.copy(marks = emptyList())
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(
                completeModel,
                layout("Rapid Rescue" to 0.27, "Serpent Specialist" to 0.31, "Agents of HYDRA" to 0.35),
            ),
            nowMs = 0,
        )
        val scrolled = reduce(
            acquired.state,
            DeckOverlayFrameAttempt.Recognized(
                scrolledModel,
                layout("Serpent Specialist" to 0.22, "Agents of HYDRA" to 0.27),
            ),
            nowMs = 2_000,
        )

        assertEquals(scrolledModel, scrolled.state.model)
        assertEquals(scrolledModel, scrolled.state.lastGoodModel)
        assertEquals(null, scrolled.state.unreadableReason)
    }

    @Test
    fun aChangedGuidanceSummaryIsAcceptedEvenWhenFewerRowsWereRead() {
        val oldModel = DeckGuidanceOverlayModel(
            status = DeckGuidanceStatus.NEEDS_CHANGES,
            marks = listOf(
                DeckGuidanceVisualMark.RemoveRow(
                    bounds = NormalizedRect(0.785, 0.30, 0.207, 0.036),
                    currentCount = 1,
                ),
            ),
            remainingRemovals = 5,
        )
        val updatedModel = oldModel.copy(marks = emptyList(), remainingRemovals = 4)
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(
                oldModel,
                layout("Go Nuts!" to 0.22, "Rapid Rescue" to 0.27, "Serpent Specialist" to 0.31),
            ),
            nowMs = 0,
        )
        val updated = reduce(
            acquired.state,
            DeckOverlayFrameAttempt.Recognized(
                updatedModel,
                layout("Go Nuts!" to 0.22, "Serpent Specialist" to 0.31),
            ),
            nowMs = 2_000,
        )

        assertEquals(updatedModel, updated.state.model)
        assertEquals(updatedModel, updated.state.lastGoodModel)
        assertEquals(null, updated.state.unreadableReason)
    }

    @Test
    fun onlySustainedPositiveEvidenceOfLeavingTheBuilderHidesGuidance() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val firstOutside = reduce(acquired.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 100)
        val almostLost = reduce(
            firstOutside.state,
            DeckOverlayFrameAttempt.OutsideBuilder,
            nowMs = 100 + DeckOverlayPresentationReducer.OUTSIDE_BUILDER_GRACE_MS - 1,
        )
        assertEquals(trustedModel, almostLost.state.model)
        assertTrue(almostLost.state.contentVisible)

        val lost = reduce(
            almostLost.state,
            DeckOverlayFrameAttempt.OutsideBuilder,
            nowMs = 100 + DeckOverlayPresentationReducer.OUTSIDE_BUILDER_GRACE_MS,
        )
        assertFalse(lost.state.contentVisible)
        assertFalse(lost.state.builderAcquired)
        assertEquals(listOf(DeckOverlayPresentationTransition.Lost), lost.transitions)

        val inconclusiveAfterLoss = reduce(lost.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 5_000)
        assertFalse(inconclusiveAfterLoss.state.contentVisible)
        assertTrue(inconclusiveAfterLoss.transitions.isEmpty())

        val reacquiredShell = reduce(
            inconclusiveAfterLoss.state,
            DeckOverlayFrameAttempt.SupportedBuilderShell,
            nowMs = 5_100,
        )
        assertTrue(reacquiredShell.state.contentVisible)
        assertTrue(reacquiredShell.state.builderAcquired)
        assertEquals(DeckGuidanceStatus.READING, reacquiredShell.state.model.status)
        assertIs<DeckOverlayPresentationTransition.Acquired>(reacquiredShell.transitions.first())
    }

    @Test
    fun inconclusiveFrameBreaksASequenceOfOutsideBuilderEvidence() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val outside = reduce(acquired.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 100)
        val readerFailure = reduce(outside.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 2_000)
        val outsideAgain = reduce(readerFailure.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 2_100)

        assertTrue(outsideAgain.state.contentVisible)
        assertEquals(2_100, outsideAgain.state.outsideBuilderSinceMs)
    }

    @Test
    fun elapsedWallTimeAloneCannotClearGuidanceAfterOnlyTwoFailedReads() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val firstFailure = reduce(acquired.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 100)
        val slowSecondFailure = reduce(firstFailure.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 10_000)

        assertEquals(trustedModel, slowSecondFailure.state.model)
        assertEquals(2, slowSecondFailure.state.unreadableAttempts)

        val thirdFailure = reduce(slowSecondFailure.state, DeckOverlayFrameAttempt.ReaderFailure, nowMs = 12_000)
        assertEquals(DeckGuidanceStatus.UNABLE, thirdFailure.state.model.status)
        assertEquals(
            listOf(DeckOverlayPresentationTransition.GraceExpired(DeckOverlayUnreadableReason.CAPTURE_OR_OCR)),
            thirdFailure.transitions,
        )
    }

    @Test
    fun elapsedWallTimeAloneCannotHideGuidanceAfterOnlyTwoOutsideReads() {
        val acquired = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.Recognized(trustedModel),
            nowMs = 0,
        )
        val firstOutside = reduce(acquired.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 100)
        val slowSecondOutside = reduce(firstOutside.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 10_000)

        assertTrue(slowSecondOutside.state.contentVisible)
        assertEquals(trustedModel, slowSecondOutside.state.model)
        assertEquals(2, slowSecondOutside.state.outsideBuilderAttempts)

        val thirdOutside = reduce(slowSecondOutside.state, DeckOverlayFrameAttempt.OutsideBuilder, nowMs = 12_000)
        assertFalse(thirdOutside.state.contentVisible)
        assertEquals(listOf(DeckOverlayPresentationTransition.Lost), thirdOutside.transitions)
    }

    @Test
    fun initialAcquisitionCanShowReadingThenHideWithoutAFalseLostTransition() {
        val initialFailure = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.ReaderFailure,
            nowMs = 0,
        )
        assertEquals(DeckGuidanceStatus.READING, initialFailure.state.model.status)
        assertTrue(initialFailure.state.contentVisible)

        val firstOutside = reduce(
            DeckOverlayPresentationState.initial(),
            DeckOverlayFrameAttempt.OutsideBuilder,
            nowMs = 0,
        )
        val secondOutside = reduce(
            firstOutside.state,
            DeckOverlayFrameAttempt.OutsideBuilder,
            nowMs = DeckOverlayPresentationReducer.OUTSIDE_BUILDER_GRACE_MS,
        )
        assertTrue(secondOutside.state.contentVisible)
        val hidden = reduce(
            secondOutside.state,
            DeckOverlayFrameAttempt.OutsideBuilder,
            nowMs = DeckOverlayPresentationReducer.OUTSIDE_BUILDER_GRACE_MS + 1,
        )
        assertFalse(hidden.state.contentVisible)
        assertTrue(hidden.transitions.isEmpty())
    }

    @Test
    fun pollDelayTargetsFixedFrameStartsAndAlwaysLeavesRest() {
        assertEquals(300, deckOverlayPollDelayMs(0))
        assertEquals(180, deckOverlayPollDelayMs(120))
        assertEquals(50, deckOverlayPollDelayMs(250))
        assertEquals(50, deckOverlayPollDelayMs(2_000))
        assertEquals(300, deckOverlayPollDelayMs(-100))
    }

    private fun reduce(
        state: DeckOverlayPresentationState,
        attempt: DeckOverlayFrameAttempt,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate = DeckOverlayPresentationReducer.reduce(state, attempt, nowMs)

    private fun layout(vararg rows: Pair<String, Double>): DeckOverlayLayoutSnapshot =
        DeckOverlayLayoutSnapshot(rows.map { (name, centerY) -> DeckOverlayRowAnchor(name, centerY) })
}
