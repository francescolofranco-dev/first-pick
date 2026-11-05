package com.firstpick.overlay

import kotlin.test.*

class HoverAnimationTest {
    private val cards = listOf(OverlayCard(80.0, null, grpId = 1), OverlayCard(70.0, null, grpId = 2), OverlayCard(60.0, null, grpId = 3))
    private val pack = listOf(.05, .3, .75).mapIndexed { index, x ->
        ArenaVisibleCard(index + 1, 1, ArenaCardZone.PACK, NormalizedRect(x, .2, .12, .3))
    }
    private val finalBounds = NormalizedRect(.22, .08, .30, .82)
    private fun geometry(bounds: NormalizedRect?, id: Int = 1) = ArenaCardGeometry(1920, 1080,
        pack + listOfNotNull(bounds?.let { ArenaVisibleCard(id, 1, ArenaCardZone.PREVIEW, it, sourceIndex = id - 1) }))
    private fun marks(frame: MemoryOverlayFrame<List<OverlayCard>>) = draftScoreMarks(cards, frame.geometry!!.cards)

    @Test fun hideCoveredMarksAtAnimationStartAndDrawOneScoreOnlyAfterTheCardSettles() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        val moving = listOf(
            0L to NormalizedRect(.26, .12, .20, .60),
            33L to NormalizedRect(.24, .10, .25, .72),
            66L to finalBounds,
            99L to finalBounds,
        )
        for ((time, bounds) in moving) {
            val frame = presenter.accepted("pack", "editor", cards, geometry(bounds), time)
            assertEquals(listOf(80.0, 60.0), marks(frame).map { it.value },
                "A moving preview must mask covered originals immediately without painting a traveling score at $time ms")
        }
        val settled = presenter.accepted("pack", "editor", cards, geometry(finalBounds), 132)
        assertEquals(listOf(80.0, 60.0, 80.0), marks(settled).map { it.value })
        assertEquals(finalBounds, marks(settled).single { it.slot == -1 }.bounds)
    }
    private fun settle(presenter: MemoryOverlayPresentation<List<OverlayCard>>, bounds: NormalizedRect = finalBounds, start: Long = 0, id: Int = 1): MemoryOverlayFrame<List<OverlayCard>> {
        presenter.accepted("pack", "editor", cards, geometry(bounds, id), start)
        presenter.accepted("pack", "editor", cards, geometry(bounds, id), start + 33)
        return presenter.accepted("pack", "editor", cards, geometry(bounds, id), start + 66)
    }

    @Test fun subPixelJitterDoesNotPublishMultipleSettledPositions() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        for ((index, delta) in listOf(.25, -.25, .5, 0.0).withIndex()) {
            val jitter = finalBounds.copy(x = finalBounds.x + delta / 1920)
            val frame = presenter.accepted("pack", "editor", cards, geometry(jitter), 99L + index * 33)
            assertEquals(finalBounds, marks(frame).single { it.slot == -1 }.bounds)
        }
    }

    @Test fun renewedMotionHidesTheScoreAndKeepsTheWholeObservedPathMasked() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val moved = finalBounds.copy(x = .64, width = .28)
        val moving = presenter.accepted("pack", "editor", cards, geometry(moved), 99)
        assertEquals(listOf(80.0), marks(moving).map { it.value })
        val sweep = finalBounds.union(moved)
        assertEquals(sweep, moving.geometry!!.cards.last().occlusionBounds)
        val windowMask = moving.geometry!!.inWindow(WindowBounds(0, 0, 960, 568, backingScale = 2.0)).last().occlusionBounds!!
        assertEquals((28 + sweep.y * 540) / 568, windowMask.y, .000001)
        presenter.accepted("pack", "editor", cards, geometry(moved), 132)
        val settled = presenter.accepted("pack", "editor", cards, geometry(moved), 165)
        assertEquals(listOf(80.0, 70.0, 80.0), marks(settled).map { it.value })
        assertEquals(moved, marks(settled).single { it.slot == -1 }.bounds)
    }

    @Test fun interruptedReadCannotPaintTheOldPreviewOrConfirmSettlement() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val missed = presenter.accepted("pack", "editor", cards, null, 99)
        assertEquals(listOf(80.0, 60.0), marks(missed).map { it.value })
        assertTrue(marks(presenter.expire(132)).none { it.slot == -1 })
        assertTrue(marks(presenter.accepted("pack", "editor", cards, geometry(finalBounds), 165)).none { it.slot == -1 })
        assertEquals(finalBounds, marks(settle(presenter, start = 165)).single { it.slot == -1 }.bounds)
    }

    @Test fun switchingCardsAtTheSamePositionStartsANewAnimation() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val switched = presenter.accepted("pack", "editor", cards, geometry(finalBounds, id = 2), 99)
        assertTrue(marks(switched).none { it.slot == -1 })
        val settled = settle(presenter, start = 99, id = 2)
        assertEquals(70.0, marks(settled).single { it.slot == -1 }.value)
    }

    @Test fun closingThePreviewRestoresOriginalsAndReopeningNeedsFreshSettlement() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val closed = presenter.accepted("pack", "editor", cards, geometry(null), 99)
        assertEquals(listOf(80.0, 70.0, 60.0), marks(closed).map { it.value })
        val reopened = presenter.accepted("pack", "editor", cards, geometry(finalBounds), 132)
        assertTrue(marks(reopened).none { it.slot == -1 })
    }

    @Test fun aViewportChangeOrLongReadGapCannotReuseThePreviousSettledAnchor() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val resumed = presenter.accepted("pack", "editor", cards, geometry(finalBounds), 300)
        assertTrue(marks(resumed).none { it.slot == -1 })
        settle(presenter, start = 300)
        val resized = presenter.accepted("pack", "editor", cards,
            geometry(finalBounds).copy(screenWidth = 3840, screenHeight = 2160), 399)
        assertTrue(marks(resized).none { it.slot == -1 })
    }

    @Test fun overlappingRolloverAndEnlargedObjectsNeverPublishTwoPreviewRatings() {
        val presenter = MemoryOverlayPresentation<List<OverlayCard>>()
        settle(presenter)
        val front = finalBounds.copy(x = .60, width = .35)
        val both = ArenaCardGeometry(1920, 1080, geometry(finalBounds).cards +
            ArenaVisibleCard(1, 1, ArenaCardZone.PREVIEW, front, sourceIndex = 0))
        val opening = presenter.accepted("pack", "editor", cards, both, 99)
        assertTrue(marks(opening).none { (it.slot ?: 0) < 0 }, "The old preview rating must vanish when the foremost preview starts")
        presenter.accepted("pack", "editor", cards, both, 132)
        val settled = presenter.accepted("pack", "editor", cards, both, 165)
        val previews = marks(settled).filter { (it.slot ?: 0) < 0 }
        assertEquals(1, previews.size)
        assertEquals(front, previews.single().bounds)
    }

}
