package com.firstpick.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DraftStateTest {

    @Test
    fun reachingFortyTwoCardsDoesNotCompleteWithCardsStillInThePack() {
        val before = draftingState(
            pool = (1..41).toList(),
            packCards = listOf(900, 901, 902, 903),
        )

        val after = before.reduce(DraftEvent.PickMade(pack = 3, pick = 13, cardIds = listOf(900)))

        assertEquals(42, after.pool.size)
        assertEquals(DraftPhase.DRAFTING, after.phase)
    }

    @Test
    fun confirmedLastCardOfPackThreeCompletesTheDraft() {
        val before = draftingState(packCards = listOf(900))

        val after = before.reduce(DraftEvent.PickMade(pack = 3, pick = 14, cardIds = listOf(900)))

        assertEquals(DraftPhase.COMPLETE, after.phase)
        assertEquals(900, after.pool.last())
    }

    @Test
    fun pickWithoutAnObservedPackCannotCompleteTheDraft() {
        val before = draftingState(pool = (1..44).toList(), packCards = emptyList())

        val after = before.reduce(DraftEvent.PickMade(pack = 3, pick = 14, cardIds = listOf(900)))

        assertEquals(DraftPhase.DRAFTING, after.phase)
    }

    @Test
    fun multiPickFinalConfirmationStillCompletesTraditionalDraft() {
        val before = draftingState(
            format = DraftFormat.TRADITIONAL,
            packCards = listOf(900, 901),
        )

        val after = before.reduce(DraftEvent.PickMade(pack = 3, pick = 14, cardIds = listOf(900, 901)))

        assertEquals(DraftPhase.COMPLETE, after.phase)
    }

    @Test
    fun explicitQuickDraftCompletionSnapshotRemainsSupported() {
        val before = draftingState(format = DraftFormat.QUICK, packCards = listOf(900))

        val after = before.reduce(
            DraftEvent.Snapshot(
                eventName = "QuickDraftEmblem_SOS_20260611",
                pack = 3,
                pick = 14,
                packCards = emptyList(),
                pool = (1..42).toList(),
                complete = true,
            ),
        )

        assertEquals(DraftPhase.COMPLETE, after.phase)
        assertEquals(DraftFormat.QUICK, after.format)
    }

    private fun draftingState(
        format: DraftFormat = DraftFormat.PREMIER,
        pool: List<Int> = (1..40).toList(),
        packCards: List<Int>,
    ) = DraftState(
        phase = DraftPhase.DRAFTING,
        format = format,
        setCode = "SOS",
        eventName = when (format) {
            DraftFormat.QUICK -> "QuickDraftEmblem_SOS_20260611"
            DraftFormat.TRADITIONAL -> "TraditionalDraft_SOS_20260611"
            else -> "PremierDraft_SOS_20260611"
        },
        pack = 3,
        pick = 13,
        packCards = packCards,
        pool = pool,
    )
}
