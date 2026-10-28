package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckGuidanceOverlayTest {
    private val bounds = NormalizedRect(0.75, 0.25, 0.20, 0.04)

    @Test
    fun statusLabelsAndSemanticColorsStayExact() {
        assertEquals(
            "Checking recent changes…" to DeckGuidanceOverlayPalette.ReadingAccent,
            statusPillStyle(DeckGuidanceStatus.READING).let { it.label to it.accent },
        )
        assertEquals(
            "Deck needs changes" to DeckGuidanceOverlayPalette.RemoveAccent,
            statusPillStyle(DeckGuidanceStatus.NEEDS_CHANGES).let { it.label to it.accent },
        )
        assertEquals(
            "Deck matches build" to DeckGuidanceOverlayPalette.AddAccent,
            statusPillStyle(DeckGuidanceStatus.MATCHES).let { it.label to it.accent },
        )
        assertEquals(
            "Unable to read deck" to DeckGuidanceOverlayPalette.UnableAccent,
            statusPillStyle(DeckGuidanceStatus.UNABLE).let { it.label to it.accent },
        )
    }

    @Test
    fun needsChangesStatusSummarizesOffscreenWorkAndBasicLandAdjustment() {
        assertEquals(
            "Deck needs changes · Cut 3 cards · Add 1 card · Remove 2 basic lands",
            statusPillStyle(
                DeckGuidanceOverlayModel(
                    status = DeckGuidanceStatus.NEEDS_CHANGES,
                    remainingAdds = 1,
                    remainingRemovals = 3,
                    basicLandDelta = -2,
                ),
            ).label,
        )
        assertEquals(
            "Deck needs changes · Cut 5 cards · Add 2 basic lands",
            statusPillStyle(
                DeckGuidanceOverlayModel(
                    status = DeckGuidanceStatus.NEEDS_CHANGES,
                    remainingRemovals = 5,
                    basicLandDelta = 2,
                ),
            ).label,
        )
        assertEquals(
            "Remove 4 basic lands",
            statusPillStyle(
                DeckGuidanceOverlayModel(
                    status = DeckGuidanceStatus.NEEDS_CHANGES,
                    basicLandDelta = -4,
                ),
            ).label,
        )
        assertEquals(
            "Add 1 basic land",
            statusPillStyle(
                DeckGuidanceOverlayModel(
                    status = DeckGuidanceStatus.NEEDS_CHANGES,
                    basicLandDelta = 1,
                ),
            ).label,
        )
    }

    @Test
    fun landOnlyGuidanceNamesTheMissingBasicInThePrimaryLabelAndDetails() {
        val model = memoryGuidanceModel(
            listOf(DeckCardCount("Spell", 23), DeckCardCount("Island", 7, true), DeckCardCount("Swamp", 7, true), DeckCardCount("Forest", 3, true)),
            DeckSnapshot(listOf(DeckCardCount("Spell", 23), DeckCardCount("Island", 7, true), DeckCardCount("Swamp", 7, true), DeckCardCount("Forest", 2, true)), 39),
        )
        assertEquals("Add 1 Forest", statusPillStyle(model).label)
        assertEquals("Add 1 Forest", model.guidanceDetails())
    }

    @Test
    fun namedLandSwapsRemainVisibleAlongsideSpellInstructionsAtFortyCards() {
        val model = memoryGuidanceModel(
            listOf(DeckCardCount("Spell", 22), DeckCardCount("Missing spell", 1), DeckCardCount("Island", 9, true), DeckCardCount("Swamp", 8, true)),
            DeckSnapshot(listOf(DeckCardCount("Spell", 22), DeckCardCount("Extra spell", 1), DeckCardCount("Island", 8, true), DeckCardCount("Swamp", 8, true), DeckCardCount("Mountain", 1, true)), 40),
        )
        assertEquals("Deck needs changes · Cut 1 card · Add 1 card · Add 1 Island · Remove 1 Mountain", statusPillStyle(model).label)
        assertTrue(model.guidanceDetails().contains("Add 1 Island"))
        assertTrue(model.guidanceDetails().contains("Remove 1 Mountain"))
        assertFalse(statusPillStyle(model).label.contains("basic land"))
    }

    @Test
    fun mapsOnlyActionableCardsToVisualMarks() {
        val add = DeckGuidanceAction.ADD.toVisualMark(bounds)
        val remove = DeckGuidanceAction.REMOVE.toVisualMark(bounds, currentCount = 4)

        assertIs<DeckGuidanceVisualMark.AddCard>(add)
        assertEquals(bounds, add.bounds)
        assertIs<DeckGuidanceVisualMark.RemoveRow>(remove)
        assertEquals(4, remove.currentCount)
        assertEquals(bounds, remove.bounds)
        assertNull(DeckGuidanceAction.OK.toVisualMark(bounds, currentCount = 1))
    }

    @Test
    fun refusesImpossibleOrInvalidMarks() {
        assertNull(DeckGuidanceAction.REMOVE.toVisualMark(bounds, currentCount = 0))
        assertNull(DeckGuidanceAction.REMOVE.toVisualMark(bounds, currentCount = 100))
        assertNull(
            DeckGuidanceAction.ADD.toVisualMark(
                NormalizedRect(x = 0.95, y = 0.1, width = 0.10, height = 0.1),
            ),
        )
    }

    @Test
    fun nonActionableStatusesFailClosedAgainstStaleMarks() {
        val stale = listOf(DeckGuidanceVisualMark.AddCard(bounds))

        assertEquals(stale, DeckGuidanceOverlayModel(DeckGuidanceStatus.NEEDS_CHANGES, stale).renderableMarks())
        assertEquals(stale, DeckGuidanceOverlayModel(DeckGuidanceStatus.READING, stale).renderableMarks())
        assertEquals(emptyList(), DeckGuidanceOverlayModel(DeckGuidanceStatus.MATCHES, stale).renderableMarks())
        assertEquals(emptyList(), DeckGuidanceOverlayModel(DeckGuidanceStatus.UNABLE, stale).renderableMarks())
    }

    @Test
    fun scalesNormalizedRectsInAnyViewportUnit() {
        assertEquals(
            GuidanceViewportRect(left = 1500f, top = 250f, width = 400f, height = 40f),
            bounds.inViewport(viewportWidth = 2000f, viewportHeight = 1000f),
        )
        assertNull(bounds.inViewport(0f, 1000f))
        assertNull(bounds.inViewport(2000f, Float.NaN))
    }
}
