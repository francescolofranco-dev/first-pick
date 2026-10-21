package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeckObservationReconcilerTest {
    private fun reconciler() = DeckObservationReconciler(listOf(
        DeckCardCount("Changed", 2), DeckCardCount("Offscreen", 1),
    )).also { it.observe(frame(40, 2, true), 0) }

    private fun frame(total: Int, count: Int, includeHidden: Boolean = false) = DeckObservationFrame(
        total, listOf(VisibleExactCardCount("Changed", count)) +
            if (includeHidden) listOf(VisibleExactCardCount("Offscreen", 1)) else emptyList(),
    )

    @Test
    fun totalBeforeRowPreservesOffscreenKnowledgeOnceTheRowCatchesUp() {
        val reader = reconciler()
        val pending = reader.observe(frame(39, 2), 100)
        assertEquals(DeckObservationStatus.READING, pending.status)
        assertEquals(emptyMap(), pending.knownCounts)
        val settled = reader.observe(frame(39, 1), 400)
        assertEquals(DeckObservationStatus.READY, settled.status)
        assertEquals(1, settled.countFor("Offscreen"))
        assertEquals(1, settled.countFor("Changed"))
    }

    @Test
    fun rowBeforeTotalPreservesOffscreenKnowledgeOnceTheTotalCatchesUp() {
        val reader = reconciler()
        assertEquals(DeckObservationStatus.READING, reader.observe(frame(40, 1), 100).status)
        val settled = reader.observe(frame(39, 1), 400)
        assertEquals(1, settled.countFor("Offscreen"))
        assertEquals(DeckObservationStatus.READY, settled.status)
    }

    @Test
    fun persistentUnexplainedEditRequiresReacquiringHiddenCards() {
        val reader = reconciler()
        reader.observe(frame(39, 2), 100)
        val settled = reader.observe(frame(39, 2), 700)
        assertEquals(DeckObservationStatus.READING, settled.status)
        assertNull(settled.countFor("Offscreen"))
        assertEquals(2, settled.countFor("Changed"))
    }

    @Test
    fun fluctuatingEvidenceCannotBeCommittedAsSettled() {
        val reader = reconciler()
        reader.observe(frame(39, 2), 100)
        reader.observe(frame(38, 2), 500)
        assertEquals(emptyMap(), reader.observe(frame(38, 2), 800).knownCounts)
        assertNull(reader.observe(frame(38, 2), 1100).countFor("Offscreen"))
    }

    @Test
    fun invalidationAndInvalidFramesCannotResurrectHiddenCounts() {
        val reader = reconciler()
        assertEquals(DeckObservationStatus.UNABLE, reader.observe(frame(40, 3), 100).status)
        reader.invalidate()
        assertNull(reader.observe(frame(40, 2), 200).countFor("Offscreen"))
    }
}
