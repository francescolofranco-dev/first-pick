package com.firstpick.overlay

/** One completed attempt to read Arena's deck-builder frame. */
internal sealed interface DeckOverlayFrameAttempt {
    data object ReaderFailure : DeckOverlayFrameAttempt
    data object SupportedBuilderShell : DeckOverlayFrameAttempt
    data object UnsupportedLayout : DeckOverlayFrameAttempt
    data object OutsideBuilder : DeckOverlayFrameAttempt
    data class Recognized(
        val model: DeckGuidanceOverlayModel,
        val layout: DeckOverlayLayoutSnapshot? = null,
    ) : DeckOverlayFrameAttempt
}

internal data class DeckOverlayRowAnchor(
    val cardName: String,
    val centerY: Double,
)

/** Row identities and positions used only to reject degraded OCR frames. */
internal data class DeckOverlayLayoutSnapshot(
    val rows: List<DeckOverlayRowAnchor>,
) {
    fun isStableSubsetOf(previous: DeckOverlayLayoutSnapshot): Boolean {
        if (rows.size >= previous.rows.size || rows.isEmpty()) return false

        val previousByName = previous.rows.associateBy { it.cardName.stableRowKey() }
        val matching = rows.mapNotNull { row ->
            previousByName[row.cardName.stableRowKey()]?.let { old -> row to old }
        }
        val requiredMatches = maxOf(MIN_STABLE_ROWS, (rows.size * 3 + 4) / 5)
        return matching.size >= requiredMatches &&
            matching.all { (current, old) -> kotlin.math.abs(current.centerY - old.centerY) <= ROW_Y_TOLERANCE }
    }

    fun isSameLayoutAs(previous: DeckOverlayLayoutSnapshot): Boolean {
        if (rows.isEmpty() || previous.rows.isEmpty()) return false
        val previousByName = previous.rows.associateBy { it.cardName.stableRowKey() }
        val matching = rows.mapNotNull { row ->
            previousByName[row.cardName.stableRowKey()]?.let { old -> row to old }
        }
        val smallerLayoutSize = minOf(rows.size, previous.rows.size)
        val requiredMatches = maxOf(MIN_STABLE_ROWS, (smallerLayoutSize * 3 + 4) / 5)
        return matching.size >= requiredMatches &&
            matching.all { (current, old) -> kotlin.math.abs(current.centerY - old.centerY) <= ROW_Y_TOLERANCE }
    }

    private companion object {
        const val MIN_STABLE_ROWS = 2
        const val ROW_Y_TOLERANCE = 0.008
    }
}

internal fun DeckBuilderScreenObservation.overlayLayoutSnapshot(): DeckOverlayLayoutSnapshot =
    DeckOverlayLayoutSnapshot(
        deckRows.map { row -> DeckOverlayRowAnchor(row.cardName, row.bounds.centerY) },
    )

private fun String.stableRowKey(): String =
    lowercase().filter(Char::isLetterOrDigit)

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
        val retainingLastGood: Boolean,
    ) : DeckOverlayPresentationTransition

    data class GraceExpired(val reason: DeckOverlayUnreadableReason) : DeckOverlayPresentationTransition
    data object Recovered : DeckOverlayPresentationTransition
    data object Lost : DeckOverlayPresentationTransition
}

internal data class DeckOverlayPresentationState(
    val model: DeckGuidanceOverlayModel,
    val contentVisible: Boolean,
    val builderAcquired: Boolean,
    val lastGoodModel: DeckGuidanceOverlayModel?,
    val lastGoodLayout: DeckOverlayLayoutSnapshot?,
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
            lastGoodModel = null,
            lastGoodLayout = null,
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

/**
 * Pure presentation state for the polling loop.
 *
 * A positively identified builder shell is stronger evidence than missing rows:
 * hover previews and animations may obscure every row, so the last trusted marks
 * remain visible for as long as that shell is present. Capture/OCR and guidance
 * failures get a bounded stale-frame grace. Only repeated, successfully read
 * frames outside the builder can hide the overlay.
 */
internal object DeckOverlayPresentationReducer {
    const val UNREADABLE_GRACE_MS = 1_200L
    const val OUTSIDE_BUILDER_GRACE_MS = 900L
    const val MIN_UNREADABLE_ATTEMPTS = 3
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
                retainIndefinitely = true,
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
            retainIndefinitely = false,
        )
    }

    private fun recognized(
        state: DeckOverlayPresentationState,
        attempt: DeckOverlayFrameAttempt.Recognized,
        nowMs: Long,
    ): DeckOverlayPresentationUpdate {
        val model = attempt.model
        if (model.status == DeckGuidanceStatus.READING || model.status == DeckGuidanceStatus.UNABLE) {
            return unreadable(
                state = state,
                reason = DeckOverlayUnreadableReason.GUIDANCE_UNAVAILABLE,
                nowMs = nowMs,
                acquiresBuilder = true,
                retainIndefinitely = false,
            )
        }

        if (shouldRetainTrustedModel(state, attempt)) {
            return unreadable(
                state = state,
                reason = DeckOverlayUnreadableReason.ROWS_OBSCURED,
                nowMs = nowMs,
                acquiresBuilder = true,
                retainIndefinitely = true,
            )
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
                lastGoodModel = model,
                lastGoodLayout = attempt.layout,
                unreadableSinceMs = null,
                unreadableReason = null,
                unreadableAttempts = 0,
                outsideBuilderSinceMs = null,
                outsideBuilderAttempts = 0,
            ),
            transitions = transitions,
        )
    }

    private fun shouldRetainTrustedModel(
        state: DeckOverlayPresentationState,
        attempt: DeckOverlayFrameAttempt.Recognized,
    ): Boolean {
        val previousModel = state.lastGoodModel ?: return false
        val previousLayout = state.lastGoodLayout ?: return false
        val currentLayout = attempt.layout ?: return false
        if (!previousModel.hasSameGuidanceSummary(attempt.model)) return false

        val previousMarks = previousModel.renderableMarks()
        val currentMarks = attempt.model.renderableMarks()
        val marksAreStableSubset = currentMarks.size <= previousMarks.size &&
            currentMarks.all { current -> previousMarks.any { old -> current.sameVisualMarkAs(old) } }
        if (currentLayout.isStableSubsetOf(previousLayout) && marksAreStableSubset) return true

        return previousMarks.isNotEmpty() && currentMarks.size < previousMarks.size &&
            currentLayout.isSameLayoutAs(previousLayout) &&
            marksAreStableSubset
    }

    private fun unreadable(
        state: DeckOverlayPresentationState,
        reason: DeckOverlayUnreadableReason,
        nowMs: Long,
        acquiresBuilder: Boolean,
        retainIndefinitely: Boolean,
    ): DeckOverlayPresentationUpdate {
        val reasonChanged = state.unreadableReason != reason
        val sinceMs = if (reasonChanged) nowMs else state.unreadableSinceMs ?: nowMs
        val attempts = if (reasonChanged) 1 else state.unreadableAttempts + 1
        val graceExpired = !retainIndefinitely &&
            attempts >= MIN_UNREADABLE_ATTEMPTS &&
            nowMs - sinceMs >= UNREADABLE_GRACE_MS
        val nextModel = when {
            retainIndefinitely -> state.lastGoodModel ?: DeckGuidanceOverlayModel(DeckGuidanceStatus.READING)
            !graceExpired -> state.lastGoodModel ?: DeckGuidanceOverlayModel(DeckGuidanceStatus.READING)
            else -> DeckGuidanceOverlayModel(DeckGuidanceStatus.UNABLE)
        }
        val transitions = buildList {
            if (acquiresBuilder && !state.builderAcquired) add(DeckOverlayPresentationTransition.Acquired)
            if (state.unreadableReason == null) {
                add(DeckOverlayPresentationTransition.Unreadable(reason, state.lastGoodModel != null))
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
                lastGoodModel = null,
                lastGoodLayout = null,
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

private fun DeckGuidanceOverlayModel.hasSameGuidanceSummary(other: DeckGuidanceOverlayModel): Boolean =
    status == other.status &&
        remainingAdds == other.remainingAdds &&
        remainingRemovals == other.remainingRemovals &&
        basicLandDelta == other.basicLandDelta

private fun DeckGuidanceVisualMark.sameVisualMarkAs(other: DeckGuidanceVisualMark): Boolean {
    if (this::class != other::class) return false
    if (this is DeckGuidanceVisualMark.RemoveRow && other is DeckGuidanceVisualMark.RemoveRow &&
        currentCount != other.currentCount
    ) {
        return false
    }
    return kotlin.math.abs(bounds.centerX - other.bounds.centerX) <= MARK_POSITION_TOLERANCE &&
        kotlin.math.abs(bounds.centerY - other.bounds.centerY) <= MARK_POSITION_TOLERANCE
}

private const val MARK_POSITION_TOLERANCE = 0.008

internal const val DECK_OVERLAY_FRAME_START_TARGET_MS = 300L
internal const val DECK_OVERLAY_MIN_REST_MS = 50L

/** Delay that targets a fixed interval between frame starts without busy looping. */
internal fun deckOverlayPollDelayMs(processingElapsedMs: Long): Long = maxOf(
    DECK_OVERLAY_MIN_REST_MS,
    DECK_OVERLAY_FRAME_START_TARGET_MS - processingElapsedMs.coerceAtLeast(0),
)
