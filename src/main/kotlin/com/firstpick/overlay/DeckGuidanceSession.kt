package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage

/**
 * Stateful bridge from one recognized Arena frame to the domain guidance and
 * its visible marks. Unobserved counts stay unknown until verified.
 */
internal class DeckGuidanceSession(
    target: List<DeckCardCount>,
    draftPool: List<DeckCardCount>,
) {
    private var target = target.toList()
    private val baseline = draftPool.filterNot { it.isBasicLand || isBasicLandName(it.name) }
    private val baselineCounts = baseline.groupingBy(DeckCardCount::name)
        .fold(0) { total, card -> total + card.count }
    private val matcher = CanonicalCardNameMatcher(baselineCounts.keys)
    private val reducer = DeckObservationReducer(baseline)
    private var observationState = reducer.initialState()
    private var unreadableSinceMs: Long? = null
    private var lastLoggedTotal: Int? = null
    private var lastLoggedStatus: DeckGuidanceStatus? = null
    private var lastLoggedFailure: DeckObservationFailureKind? = null

    @Synchronized
    fun updateTarget(target: List<DeckCardCount>) {
        if (this.target != target) Log.info(TAG, "confirmed build changed; retaining the current Arena deck reading")
        this.target = target.toList()
    }

    @Synchronized
    fun observe(
        image: BufferedImage,
        screen: DeckBuilderScreenObservation,
    ): DeckGuidanceOverlayModel {
        unreadableSinceMs = null
        val visibleRows = screen.deckRows.mapNotNull { row ->
            val basic = BASIC_LANDS.match(row.cardName) as? CanonicalCardNameMatch.Matched
            if (basic != null) VisibleExactCardCount(basic.name, row.count, isBasicLand = true)
            else canonicalName(row.cardName)?.let { VisibleExactCardCount(it, row.count) }
        }
        val visibleRowNames = visibleRows.mapTo(HashSet(), VisibleExactCardCount::name)
        val visiblePool = screen.poolCardTitles.mapNotNull { card ->
            val name = canonicalName(card.cardName) ?: return@mapNotNull null
            // A deck-list row directly labels the current count and wins over
            // pool chrome that can still be animating after a click.
            if (name in visibleRowNames) return@mapNotNull null
            val outsideDeck = DeckPoolCopyDetector.count(image, card) ?: return@mapNotNull null
            val drafted = baselineCounts[name] ?: return@mapNotNull null
            if (outsideDeck > drafted) return@mapNotNull null
            // Arena exposes at most four diamonds, so four is not exact for the
            // rare Limited pool containing five or more copies.
            if (outsideDeck == 4 && drafted > 4) return@mapNotNull null
            VisibleExactCardCount(name, drafted - outsideDeck)
        }

        observationState = reducer.reduce(
            observationState,
            DeckObservationFrame(
                totalCards = screen.deckSize,
                visibleRows = visibleRows,
                visiblePool = visiblePool,
            ),
        )
        val guidance = DeckGuidance.evaluate(target, observationState.asDeckReadState())
        if (guidance.status == DeckGuidanceStatus.UNABLE) {
            return DeckGuidanceOverlayModel(guidance.status).also { logTransition(screen, guidance, it) }
        }

        // Partial readings may mark verified visible cards, but never report exact totals.
        val targets = target.filterNot { it.isBasicLand || isBasicLandName(it.name) }.groupingBy { it.name }
            .fold(0) { count, card -> count + card.count }
        val cards = observationState.knownCounts.mapValues { (name, count) ->
            CardGuidance(name, targets[name] ?: 0, count)
        }
        val marks = buildList {
            for (row in screen.deckRows) {
                val name = canonicalName(row.cardName) ?: continue
                val card = cards[name] ?: continue
                card.action.toVisualMark(row.bounds, currentCount = row.count)?.let(::add)
            }
            for (poolCard in screen.poolCardTitles) {
                val name = canonicalName(poolCard.cardName) ?: continue
                val card = cards[name] ?: continue
                if (card.action != DeckGuidanceAction.ADD) continue
                val bounds = DeckPoolGeometry.cardBounds(
                    poolCard,
                    imageAspectRatio = image.width.toDouble() / image.height,
                ) ?: continue
                card.action.toVisualMark(bounds, currentCount = card.currentCount)?.let(::add)
            }
        }
        return DeckGuidanceOverlayModel(
            status = guidance.status,
            marks = marks,
            remainingAdds = guidance.remainingAdds,
            remainingRemovals = guidance.remainingRemovals,
            basicLandDelta = guidance.basicLandDelta,
            verifiedCards = targets.entries.sumOf { (name, count) ->
                minOf(count, observationState.knownCounts[name] ?: 0)
            },
            expectedCards = targets.values.sum(),
        ).also { logTransition(screen, guidance, it) }
    }

    /** Preserve scroll progress across brief OCR gaps, independently of hiding stale marks. */
    @Synchronized
    fun unreadableFrame(nowMs: Long) {
        val since = unreadableSinceMs ?: nowMs.also { unreadableSinceMs = it }
        if (nowMs - since >= 3_000) observationState = reducer.initialState()
    }

    @Synchronized
    fun invalidate() {
        observationState = reducer.initialState()
        unreadableSinceMs = null
    }

    @Synchronized
    internal fun currentCount(name: String): Int? = observationState.countFor(name)

    private fun canonicalName(observed: String): String? = when (val match = matcher.match(observed)) {
        is CanonicalCardNameMatch.Matched -> match.name
        CanonicalCardNameMatch.NoMatch, is CanonicalCardNameMatch.Ambiguous -> null
    }

    private fun logTransition(
        screen: DeckBuilderScreenObservation,
        guidance: DeckGuidanceResult,
        model: DeckGuidanceOverlayModel,
    ) {
        val failure = observationState.failure?.kind
        if (failure != null && failure != lastLoggedFailure) {
            Log.warn(TAG, "discarded an untrusted card observation ($failure); keeping the last trusted counts")
        }
        if (screen.deckSize != lastLoggedTotal || guidance.status != lastLoggedStatus) {
            val removals = model.marks.count { it is DeckGuidanceVisualMark.RemoveRow }
            val additions = model.marks.count { it is DeckGuidanceVisualMark.AddCard }
            Log.info(
                TAG,
                "deck ${screen.deckSize}/${DeckGuidance.LIMITED_DECK_SIZE}: ${guidance.status}; " +
                    "remaining=${guidance.remainingRemovals} cuts, ${guidance.remainingAdds} adds, " +
                    "basicDelta=${guidance.basicLandDelta}; visibleMarks=$removals red/$additions green",
            )
        }
        lastLoggedTotal = screen.deckSize
        lastLoggedStatus = guidance.status
        lastLoggedFailure = failure
    }

    private companion object {
        const val TAG = "DeckGuidance"
        val BASIC_LANDS = CanonicalCardNameMatcher(
            listOf(
                "Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes",
                "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp",
                "Snow-Covered Mountain", "Snow-Covered Forest",
            ),
        )

        fun isBasicLandName(name: String): Boolean = BASIC_LANDS.match(name) is CanonicalCardNameMatch.Matched
    }
}
