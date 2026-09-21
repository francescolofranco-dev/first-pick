package com.firstpick.overlay

/** One completed attempt to read Arena's deck-builder frame. */
internal sealed interface DeckOverlayFrameAttempt {
    data object ReaderFailure : DeckOverlayFrameAttempt
    data object SupportedBuilderShell : DeckOverlayFrameAttempt
    data object UnsupportedLayout : DeckOverlayFrameAttempt
    data object OutsideBuilder : DeckOverlayFrameAttempt
    data class Recognized(
        val model: DeckGuidanceOverlayModel,
    ) : DeckOverlayFrameAttempt
}

internal enum class DeckOverlayUnreadableReason {
    CAPTURE_OR_OCR,
    ROWS_OBSCURED,
    GUIDANCE_UNAVAILABLE,
}

/** Sparse lifecycle events let the UI log transitions without logging every poll. */
internal sealed interface DeckOverlayPresentationTransition {
    data object Acquired : DeckOverlayPresentationTransition
    data class Unreadable(
        val reason: DeckOverlayUnreadableReason,
    ) : DeckOverlayPresentationTransition

    data class GraceExpired(val reason: DeckOverlayUnreadableReason) : DeckOverlayPresentationTransition
    data object Recovered : DeckOverlayPresentationTransition
    data object Lost : DeckOverlayPresentationTransition
}

internal data class DeckOverlayPresentationState(
    val model: DeckGuidanceOverlayModel,
    val contentVisible: Boolean,
    val builderAcquired: Boolean,
    val unreadableSinceMs: Long?,
    val unreadableReason: DeckOverlayUnreadableReason?,
    val unreadableAttempts: Int,
    val outsideBuilderSinceMs: Long?,
    val outsideBuilderAttempts: Int,
) {
    companion object {
        fun initial(): DeckOverlayPresentationState = DeckOverlayPresentationState(
            model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING),
            contentVisible = true,
            builderAcquired = false,
            unreadableSinceMs = null,
            unreadableReason = null,
            unreadableAttempts = 0,
            outsideBuilderSinceMs = null,
            outsideBuilderAttempts = 0,
        )
    }
}

internal data class DeckOverlayPresentationUpdate(
    val state: DeckOverlayPresentationState,
    val transitions: List<DeckOverlayPresentationTransition> = emptyList(),
)

/** Instructions require current evidence. Grace keeps only the status pill visible. */
internal object DeckOverlayPresentationReducer {
    const val UNREADABLE_GRACE_MS = 1_200L
    const val OUTSIDE_BUILDER_GRACE_MS = 900L
    const val MIN_OUTSIDE_BUILDER_ATTEMPTS = 3

    fun reduce(
        state: DeckOverlayPresentationState,
        attempt: DeckOverlayFrameAttempt,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate {
        return when (attempt) {
            DeckOverlayFrameAttempt.ReaderFailure -> readerFailure(state, nowMs)
            DeckOverlayFrameAttempt.SupportedBuilderShell -> unreadable(
                state = state,
                reason = DeckOverlayUnreadableReason.ROWS_OBSCURED,
                nowMs = nowMs,
                acquiresBuilder = true,
            )

            DeckOverlayFrameAttempt.UnsupportedLayout -> outsideBuilder(state, nowMs)
            DeckOverlayFrameAttempt.OutsideBuilder -> outsideBuilder(state, nowMs)
            is DeckOverlayFrameAttempt.Recognized -> recognized(state, attempt, nowMs)
        }
    }

    private fun readerFailure(
        state: DeckOverlayPresentationState,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate {
        // Once a positively lost builder is hidden, an inconclusive capture must
        // not make the overlay reappear. A shell or full observation can reacquire it.
        if (!state.contentVisible && !state.builderAcquired) {
            return DeckOverlayPresentationUpdate(
                state.copy(outsideBuilderSinceMs = null, outsideBuilderAttempts = 0),
            )
        }
        return unreadable(
            state = state,
            reason = DeckOverlayUnreadableReason.CAPTURE_OR_OCR,
            nowMs = nowMs,
            acquiresBuilder = false,
        )
    }

    private fun recognized(
        state: DeckOverlayPresentationState,
        attempt: DeckOverlayFrameAttempt.Recognized,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate {
        val model = attempt.model
        if (model.status == DeckGuidanceStatus.UNABLE) {
            return unreadable(state, DeckOverlayUnreadableReason.GUIDANCE_UNAVAILABLE, nowMs, true)
        }

        val transitions = buildList {
            if (!state.builderAcquired) add(DeckOverlayPresentationTransition.Acquired)
            if (state.unreadableReason != null) add(DeckOverlayPresentationTransition.Recovered)
        }
        return DeckOverlayPresentationUpdate(
            state = DeckOverlayPresentationState(
                model = model,
                contentVisible = true,
                builderAcquired = true,
                unreadableSinceMs = null,
                unreadableReason = null,
                unreadableAttempts = 0,
                outsideBuilderSinceMs = null,
                outsideBuilderAttempts = 0,
            ),
            transitions = transitions,
        )
    }

    private fun unreadable(
        state: DeckOverlayPresentationState,
        reason: DeckOverlayUnreadableReason,
        nowMs: Long,
        acquiresBuilder: Boolean,
    ): DeckOverlayPresentationUpdate {
        val sinceMs = state.unreadableSinceMs ?: nowMs
        val attempts = state.unreadableAttempts + 1
        val graceExpired = nowMs - sinceMs >= UNREADABLE_GRACE_MS
        // Losing evidence immediately removes instructions; grace affects only the label.
        val nextModel = DeckGuidanceOverlayModel(
            if (graceExpired) DeckGuidanceStatus.UNABLE else DeckGuidanceStatus.READING,
        )
        val transitions = buildList {
            if (acquiresBuilder && !state.builderAcquired) add(DeckOverlayPresentationTransition.Acquired)
            if (state.unreadableReason == null) {
                add(DeckOverlayPresentationTransition.Unreadable(reason))
            }
            if (graceExpired && state.model.status != DeckGuidanceStatus.UNABLE) {
                add(DeckOverlayPresentationTransition.GraceExpired(reason))
            }
        }
        return DeckOverlayPresentationUpdate(
            state = state.copy(
                model = nextModel,
                contentVisible = true,
                builderAcquired = state.builderAcquired || acquiresBuilder,
                unreadableSinceMs = sinceMs,
                unreadableReason = reason,
                unreadableAttempts = attempts,
                outsideBuilderSinceMs = null,
                outsideBuilderAttempts = 0,
            ),
            transitions = transitions,
        )
    }

    private fun outsideBuilder(
        state: DeckOverlayPresentationState,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate {
        val sinceMs = state.outsideBuilderSinceMs ?: nowMs
        val attempts = if (state.outsideBuilderSinceMs == null) 1 else state.outsideBuilderAttempts + 1
        val shouldHide = !state.contentVisible ||
            (attempts >= MIN_OUTSIDE_BUILDER_ATTEMPTS && nowMs - sinceMs >= OUTSIDE_BUILDER_GRACE_MS)
        if (!shouldHide) {
            return DeckOverlayPresentationUpdate(
                state.copy(
                    unreadableSinceMs = null,
                    unreadableReason = null,
                    unreadableAttempts = 0,
                    model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING),
                    outsideBuilderSinceMs = sinceMs,
                    outsideBuilderAttempts = attempts,
                ),
            )
        }

        val transitions = if (state.builderAcquired) {
            listOf(DeckOverlayPresentationTransition.Lost)
        } else {
            emptyList()
        }
        return DeckOverlayPresentationUpdate(
            state = DeckOverlayPresentationState(
                model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING),
                contentVisible = false,
                builderAcquired = false,
                unreadableSinceMs = null,
                unreadableReason = null,
                unreadableAttempts = 0,
                outsideBuilderSinceMs = sinceMs,
                outsideBuilderAttempts = attempts,
            ),
            transitions = transitions,
        )
    }
}

internal const val DECK_OVERLAY_FRAME_START_TARGET_MS = 300L
internal const val DECK_OVERLAY_MIN_REST_MS = 50L

/** Delay that targets a fixed interval between frame starts without busy looping. */
internal fun deckOverlayPollDelayMs(processingElapsedMs: Long): Long = maxOf(
    DECK_OVERLAY_MIN_REST_MS,
    DECK_OVERLAY_FRAME_START_TARGET_MS - processingElapsedMs.coerceAtLeast(0),
)
