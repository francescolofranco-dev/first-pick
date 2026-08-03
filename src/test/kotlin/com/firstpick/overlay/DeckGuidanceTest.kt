package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeckGuidanceTest {
    private fun card(name: String, count: Int, basic: Boolean = false) =
        DeckCardCount(name, count, isBasicLand = basic)

    private fun ready(total: Int = 40, vararg cards: DeckCardCount) =
        DeckReadState.Ready(DeckSnapshot(cards.toList(), total))

    @Test
    fun exposesReadingAndUnableWithoutCardAdvice() {
        val target = listOf(card("Target", 1))

        val reading = DeckGuidance.evaluate(target, DeckReadState.Reading)
        val unable = DeckGuidance.evaluate(target, DeckReadState.Unable)

        assertEquals(DeckGuidanceStatus.READING, reading.status)
        assertEquals(emptyList(), reading.cards)
        assertEquals(DeckGuidanceStatus.UNABLE, unable.status)
        assertEquals(emptyList(), unable.cards)
    }

    @Test
    fun matchesOnlyWhenEveryNonbasicCountMatchesAndTheDeckHasFortyCards() {
        val target = listOf(card("Alpha", 2), card("Beta", 1))

        val exact = DeckGuidance.evaluate(target, ready(40, card("Alpha", 2), card("Beta", 1)))
        val short = DeckGuidance.evaluate(target, ready(39, card("Alpha", 2), card("Beta", 1)))
        val large = DeckGuidance.evaluate(target, ready(41, card("Alpha", 2), card("Beta", 1)))

        assertEquals(DeckGuidanceStatus.MATCHES, exact.status)
        assertEquals(listOf(DeckGuidanceAction.OK, DeckGuidanceAction.OK), exact.cards.map { it.action })
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, short.status)
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, large.status)
    }

    @Test
    fun sequentialCopiesKeepTheSameActionUntilTheTargetIsReached() {
        val target = listOf(card("Stock Up", 2))

        fun actionAt(count: Int) = DeckGuidance
            .evaluate(target, ready(40, card("Stock Up", count)))
            .forCard("Stock Up")
            ?.action

        assertEquals(DeckGuidanceAction.ADD, actionAt(0))
        assertEquals(DeckGuidanceAction.ADD, actionAt(1))
        assertEquals(DeckGuidanceAction.OK, actionAt(2))
        assertEquals(DeckGuidanceAction.REMOVE, actionAt(3))
        assertEquals(DeckGuidanceAction.REMOVE, actionAt(4))
    }

    @Test
    fun cardsAbsentFromTheTargetAreRemovals() {
        val result = DeckGuidance.evaluate(
            target = listOf(card("Wanted", 1), card("Extra", 0)),
            current = ready(40, card("Wanted", 1), card("Extra", 2)),
        )

        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, result.status)
        assertEquals(DeckGuidanceAction.OK, result.forCard("Wanted")?.action)
        assertEquals(DeckGuidanceAction.REMOVE, result.forCard("Extra")?.action)
    }

    @Test
    fun missingTargetCardsAreAdditionsEvenWhenCurrentlyAbsent() {
        val result = DeckGuidance.evaluate(
            target = listOf(card("Missing", 2), card("Present", 1)),
            current = ready(40, card("Present", 1)),
        )

        assertEquals(DeckGuidanceAction.ADD, result.forCard("Missing")?.action)
        assertEquals(2, result.forCard("Missing")?.difference)
    }

    @Test
    fun basicLandsAreIgnoredButStillCountTowardTheDeckTotal() {
        val target = listOf(card("Spell", 23), card("Plains", 17, basic = true))
        val result = DeckGuidance.evaluate(
            target,
            ready(40, card("Spell", 23), card("Plains", 9, basic = true), card("Island", 8, basic = true)),
        )

        assertEquals(DeckGuidanceStatus.MATCHES, result.status)
        assertEquals(DeckGuidanceAction.OK, result.forCard("Spell")?.action)
        assertNull(result.forCard("Plains"))
        assertNull(result.forCard("Island"))
    }

    @Test
    fun basicLandDifferencesCannotMakeANonFortyCardDeckMatch() {
        val target = listOf(card("Spell", 23))
        val short = DeckGuidance.evaluate(
            target,
            ready(39, card("Spell", 23), card("Plains", 16, basic = true)),
        )
        val large = DeckGuidance.evaluate(
            target,
            ready(44, card("Spell", 23), card("Plains", 21, basic = true)),
        )

        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, short.status)
        assertEquals(listOf(DeckGuidanceAction.OK), short.cards.map { it.action })
        assertEquals(1, short.basicLandDelta)
        assertEquals(-4, large.basicLandDelta)
        assertEquals(0, large.remainingAdds)
        assertEquals(0, large.remainingRemovals)
    }

    @Test
    fun reportsNamedChangesSeparatelyFromTheEventualBasicLandAdjustment() {
        val result = DeckGuidance.evaluate(
            target = listOf(card("Add", 2), card("Cut", 0), card("Exact", 1)),
            current = ready(44, card("Add", 0), card("Cut", 3), card("Exact", 1), card("Swamp", 40, basic = true)),
        )

        assertEquals(2, result.remainingAdds)
        assertEquals(3, result.remainingRemovals)
        assertEquals(-3, result.basicLandDelta)
    }

    @Test
    fun distinguishesNamedCutsFromNetDeckSizeAtFortyThreeCards() {
        val result = DeckGuidance.evaluate(
            target = listOf(card("Named cards", 24)),
            current = ready(43, card("Named cards", 29), card("Basic lands", 14, basic = true)),
        )

        assertEquals(5, result.remainingRemovals)
        assertEquals(2, result.basicLandDelta)
        assertEquals(-3, result.netCardDelta)
    }

    @Test
    fun draftedNonbasicLandsAreComparedLikeOtherCards() {
        val target = listOf(card("Dual Land", 1))

        val missing = DeckGuidance.evaluate(target, ready(40))
        val excess = DeckGuidance.evaluate(target, ready(40, card("Dual Land", 2)))

        assertEquals(DeckGuidanceAction.ADD, missing.forCard("Dual Land")?.action)
        assertEquals(DeckGuidanceAction.REMOVE, excess.forCard("Dual Land")?.action)
    }

    @Test
    fun duplicateRowsAreAggregatedByName() {
        val result = DeckGuidance.evaluate(
            target = listOf(card("Duplicate", 1), card("Duplicate", 2)),
            current = ready(40, card("Duplicate", 3)),
        )

        assertEquals(DeckGuidanceStatus.MATCHES, result.status)
        assertEquals(1, result.cards.size)
        assertEquals(3, result.forCard("Duplicate")?.targetCount)
        assertEquals(DeckGuidanceAction.OK, result.forCard("Duplicate")?.action)
    }

    @Test
    fun invalidCountsAreRejectedAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> { card("Bad", -1) }
        assertFailsWith<IllegalArgumentException> { DeckSnapshot(emptyList(), -1) }
    }
}
