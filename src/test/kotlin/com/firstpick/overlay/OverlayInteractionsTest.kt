package com.firstpick.overlay

import com.firstpick.advisor.ValueBreakdown
import com.firstpick.ui.ModelExplain
import kotlin.test.*

class OverlayInteractionsTest {
    @Test fun verifiedCardKeepsItsOwnExplanationAcrossDuplicateIds() {
        val a = ValueBreakdown(50.0, 2.0, 3.0, -1.0, 1.0, 55.0)
        val b = a.copy(baseScore = 60.0, finalScore = 65.0)
        val model = ModelExplain(1, 2, 50.0)
        val cards = listOf(OverlayCard(55.0, null, grpId = 7, breakdown = a, model = model, gihWr = .55),
            OverlayCard(65.0, null, grpId = 7, breakdown = b, gihWr = .60))
        val placements = listOf(.1, .4).map { ArenaVisibleCard(7, 1, ArenaCardZone.PACK, NormalizedRect(it, .2, .2, .4)) }
        val marks = draftScoreMarks(cards, placements)
        assertEquals(listOf(a, b), marks.map { it.breakdown })
        assertEquals(listOf(.55, .60), marks.map { it.gihWr })
        assertEquals(listOf(model, null), marks.map { it.model })
        assertTrue(draftScoreMarks(cards, placements.drop(1)).isEmpty())
    }

    @Test fun accessActionStaysVisibleWhileConnectingAndDisappearsWhenConnected() {
        val required = OverlayHealth(OverlayHealthState.ACCESS_REQUIRED, "Grant access")
        val reading = OverlayHealth(OverlayHealthState.READING, "Reading")
        assertTrue(showOverlayAccessNotice(required, LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED)))
        assertTrue(showOverlayAccessNotice(required, LiveDeckAccess(LiveDeckAccessStatus.FAILED)))
        assertTrue(showOverlayAccessNotice(reading, LiveDeckAccess(LiveDeckAccessStatus.GRANTING)))
        assertFalse(showOverlayAccessNotice(reading, LiveDeckAccess(LiveDeckAccessStatus.CONNECTED)))
    }

    @Test fun badgeInputAreaIsSmallAndTracksViewportResizing() {
        val mark = DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 70.0, true)
        val small = assertNotNull(scoreBadgeBounds(mark, 800f, 600f))
        val large = assertNotNull(scoreBadgeBounds(mark, 1600f, 1200f))
        assertTrue(small.width < 160f)
        assertTrue(large.width <= 118f)
        assertEquals(240f, small.left + small.width / 2f, .001f)
        assertEquals(480f, large.left + large.width / 2f, .001f)
        assertNull(scoreBadgeBounds(mark, 0f, 600f))
    }
}
