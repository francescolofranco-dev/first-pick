package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeckObservationReducerTest {
    private fun baseline(vararg cards: Pair<String, Int>) = cards.map { (name, count) -> DeckCardCount(name, count) }

    private fun visible(name: String, count: Int, basic: Boolean = false) =
        VisibleExactCardCount(name, count, isBasicLand = basic)

    @Test
    fun startsWithEveryDraftedNonbasicInTheDeckAndReadsUntilTheTotalArrives() {
        val reducer = DeckObservationReducer(baseline("Alpha" to 2, "Beta" to 1))
        val initial = reducer.initialState()

        assertEquals(DeckObservationStatus.READING, initial.status)
        assertEquals(mapOf("Alpha" to 2, "Beta" to 1), initial.knownCounts)
        assertIs<DeckReadState.Reading>(initial.asDeckReadState())

        val observed = reducer.reduce(initial, DeckObservationFrame(totalCards = 42))

        assertEquals(DeckObservationStatus.READY, observed.status)
        assertEquals(2, observed.countFor("Alpha"))
        assertEquals(1, observed.countFor("Beta"))
        val ready = assertIs<DeckReadState.Ready>(observed.asDeckReadState())
        assertEquals(42, ready.deck.totalCards)
    }

    @Test
    fun combinesExactRowsAndPoolCountsIntoAReadySnapshot() {
        val reducer = DeckObservationReducer(baseline("Alpha" to 2, "Beta" to 1))
        val state = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(
                totalCards = 40,
                visibleRows = listOf(visible("alpha", 2)),
                visiblePool = listOf(visible("BETA!", 0)),
            ),
        )

        assertEquals(DeckObservationStatus.READY, state.status)
        assertEquals(mapOf("Alpha" to 2, "Beta" to 0), state.knownCounts)
        val ready = assertIs<DeckReadState.Ready>(state.asDeckReadState())
        assertEquals(40, ready.deck.totalCards)
        assertEquals(mapOf("Alpha" to 2, "Beta" to 0), ready.deck.cards.associate { it.name to it.count })
    }

    @Test
    fun sequentialDuplicateChangesReplaceThePreviousExactCount() {
        val reducer = DeckObservationReducer(baseline("Stock Up" to 4, "Anchor" to 1))
        var state = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Stock Up", 4), visible("Anchor", 1))),
        )

        for ((total, copies) in listOf(39 to 3, 38 to 2, 37 to 1)) {
            state = reducer.reduce(
                state,
                DeckObservationFrame(total, listOf(visible("Stock Up", copies), visible("Anchor", 1))),
            )
            assertEquals(copies, state.countFor("Stock Up"))
            assertEquals(DeckObservationStatus.READY, state.status)
        }
    }

    @Test
    fun retainsTrustedCountsWhenCardsMoveOffscreen() {
        val reducer = DeckObservationReducer(baseline("Alpha" to 2, "Beta" to 1, "Gamma" to 1))
        val first = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Alpha", 2), visible("Beta", 1), visible("Gamma", 1))),
        )
        val scrolled = reducer.reduce(
            first,
            DeckObservationFrame(40, listOf(visible("Gamma", 1))),
        )

        assertEquals(2, scrolled.countFor("Alpha"))
        assertEquals(1, scrolled.countFor("Beta"))
        assertEquals(DeckObservationStatus.READY, scrolled.status)
    }

    @Test
    fun infersAVisibleOneCopyRowWasRemovedWhenTheDeckDropsByOne() {
        val reducer = DeckObservationReducer(baseline("Cut Me" to 1, "Anchor" to 2))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(41, listOf(visible("Cut Me", 1), visible("Anchor", 2))),
        )
        val after = reducer.reduce(
            before,
            DeckObservationFrame(40, listOf(visible("Anchor", 2))),
        )

        assertEquals(0, after.countFor("Cut Me"))
        assertEquals(DeckObservationStatus.READY, after.status)
        assertNull(after.failure)
    }

    @Test
    fun infersAUniqueVanishedDuplicateRowAcrossSeveralClicks() {
        val reducer = DeckObservationReducer(baseline("Cut Me" to 4, "Anchor" to 2))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(44, listOf(visible("Cut Me", 4), visible("Anchor", 2))),
        )
        val after = reducer.reduce(
            before,
            DeckObservationFrame(42, listOf(visible("Anchor", 2))),
        )

        assertEquals(2, after.countFor("Cut Me"))
        assertEquals(42, after.totalCards)
        assertEquals(DeckObservationStatus.READY, after.status)
        assertNull(after.failure)
    }

    @Test
    fun anExactPoolZeroWinsWithoutNeedingDisappearanceInference() {
        val reducer = DeckObservationReducer(baseline("Cut Me" to 1, "Anchor" to 2))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(41, listOf(visible("Cut Me", 1), visible("Anchor", 2))),
        )
        val after = reducer.reduce(
            before,
            DeckObservationFrame(
                totalCards = 40,
                visibleRows = listOf(visible("Anchor", 2)),
                visiblePool = listOf(visible("Cut Me", 0)),
            ),
        )

        assertEquals(0, after.countFor("Cut Me"))
        assertEquals(DeckObservationStatus.READY, after.status)
    }

    @Test
    fun doesNotTreatAnOffscreenRowAsRemovedWhenAnotherVisibleChangeExplainsTheDecrease() {
        val reducer = DeckObservationReducer(baseline("Offscreen" to 1, "Changed" to 2, "Anchor" to 1))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Offscreen", 1), visible("Changed", 2), visible("Anchor", 1))),
        )
        val after = reducer.reduce(
            before,
            DeckObservationFrame(39, listOf(visible("Changed", 1), visible("Anchor", 1))),
        )

        assertEquals(1, after.countFor("Offscreen"))
        assertEquals(1, after.countFor("Changed"))
        assertEquals(DeckObservationStatus.READY, after.status)
    }

    @Test
    fun retainsTrustedCountsWhenSeveralRowsCouldHaveVanished() {
        val reducer = DeckObservationReducer(baseline("Alpha" to 1, "Beta" to 1, "Anchor" to 1))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(41, listOf(visible("Alpha", 1), visible("Beta", 1), visible("Anchor", 1))),
        )
        val ambiguous = reducer.reduce(
            before,
            DeckObservationFrame(40, listOf(visible("Anchor", 1))),
        )

        assertEquals(DeckObservationStatus.READY, ambiguous.status)
        assertNull(ambiguous.failure)
        assertEquals(1, ambiguous.countFor("Alpha"))
        assertEquals(1, ambiguous.countFor("Beta"))
        assertEquals(40, ambiguous.totalCards)
        assertIs<DeckReadState.Ready>(ambiguous.asDeckReadState())
    }

    @Test
    fun retainsTrustedCountWithoutARetainedRowAnchor() {
        val reducer = DeckObservationReducer(baseline("Only Row" to 1))
        val before = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(41, listOf(visible("Only Row", 1))),
        )
        val ambiguous = reducer.reduce(before, DeckObservationFrame(40))

        assertEquals(DeckObservationStatus.READY, ambiguous.status)
        assertEquals(1, ambiguous.countFor("Only Row"))
        assertEquals(40, ambiguous.totalCards)
        assertNull(ambiguous.failure)
    }

    @Test
    fun aVisibleDeckRowWinsOverTransientlyStalePoolEvidence() {
        val reducer = DeckObservationReducer(baseline("Stock Up" to 4, "Anchor" to 1))
        val trusted = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Stock Up", 3), visible("Anchor", 1))),
        )
        val conflict = reducer.reduce(
            trusted,
            DeckObservationFrame(
                39,
                visibleRows = listOf(visible("Stock Up", 2), visible("Anchor", 1)),
                visiblePool = listOf(visible("Stock Up", 3)),
            ),
        )

        assertEquals(DeckObservationStatus.READY, conflict.status)
        assertNull(conflict.failure)
        assertEquals(2, conflict.countFor("Stock Up"))
        assertEquals(39, conflict.totalCards)
    }

    @Test
    fun rejectsUnknownAmbiguousInvalidAndImpossibleObservations() {
        val collisionReducer = DeckObservationReducer(baseline("A-B" to 1, "A B" to 1))
        val ambiguous = collisionReducer.reduce(
            collisionReducer.initialState(),
            DeckObservationFrame(40, listOf(visible("ab", 1))),
        )
        assertEquals(DeckObservationFailureKind.AMBIGUOUS_NAME, ambiguous.failure?.kind)

        val reducer = DeckObservationReducer(baseline("Known" to 2))
        val unknown = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Unknown", 1))),
        )
        val invalid = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Known", 0))),
        )
        val impossible = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(40, listOf(visible("Known", 3))),
        )

        assertEquals(DeckObservationFailureKind.UNKNOWN_NAME, unknown.failure?.kind)
        assertEquals(DeckObservationFailureKind.INVALID_COUNT, invalid.failure?.kind)
        assertEquals(DeckObservationFailureKind.COUNT_EXCEEDS_DRAFT_POOL, impossible.failure?.kind)
    }

    @Test
    fun ignoresBasicLandsButTracksDraftedNonbasicLands() {
        val reducer = DeckObservationReducer(
            listOf(
                DeckCardCount("Plains", 8),
                DeckCardCount("Custom Basic", 1, isBasicLand = true),
                DeckCardCount("Dual Land", 1),
            ),
        )
        val state = reducer.reduce(
            reducer.initialState(),
            DeckObservationFrame(
                totalCards = 40,
                visibleRows = listOf(visible("P l a i n s", 8), visible("Custom Basic", 1, basic = true)),
                visiblePool = listOf(visible("Dual-Land", 1)),
            ),
        )

        assertEquals(DeckObservationStatus.READY, state.status)
        assertEquals(mapOf("Dual Land" to 1), state.knownCounts)
        assertNull(state.countFor("Plains"))
    }

    @Test
    fun initialObservationSetsUnseenDraftedCardsToZeroInDeck() {
        val reducer = DeckObservationReducer(baseline("Target Card" to 2, "Cut Card 1" to 1, "Cut Card 2" to 1))
        val initial = reducer.initialState()

        val observed = reducer.reduce(
            initial,
            DeckObservationFrame(totalCards = 40, visibleRows = listOf(visible("Target Card", 2))),
        )

        assertEquals(2, observed.countFor("Target Card"))
        assertEquals(0, observed.countFor("Cut Card 1"))
        assertEquals(0, observed.countFor("Cut Card 2"))
        assertEquals(DeckObservationStatus.READY, observed.status)
    }
}
