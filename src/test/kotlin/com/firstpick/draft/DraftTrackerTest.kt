package com.firstpick.draft

import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftTrackerTest {

    private fun snapshotLine(
        pack: Int,
        pick: Int,
        packCards: List<Int>,
        pickedCards: List<Int>,
        status: String = "PickNext",
        eventName: String = "QuickDraftEmblem_SOS_20260611",
    ): String {
        fun ids(xs: List<Int>) = xs.joinToString(",") { "\\\"$it\\\"" }
        val payload = "{\\\"Result\\\":\\\"Success\\\",\\\"EventName\\\":\\\"$eventName\\\"," +
            "\\\"DraftStatus\\\":\\\"$status\\\",\\\"PackNumber\\\":$pack,\\\"PickNumber\\\":$pick," +
            "\\\"NumCardsToPick\\\":1,\\\"DraftPack\\\":[${ids(packCards)}]," +
            "\\\"PackStyles\\\":[],\\\"PickedCards\\\":[${ids(pickedCards)}],\\\"PickedStyles\\\":[]}"
        return "{\"CurrentModule\":\"BotDraft\",\"Payload\":\"$payload\"}"
    }

    @Test
    fun reconstructsQuickDraftFromSnapshots() {
        val tracker = DraftTracker()
        // P1P1: 3-card pack, empty pool
        tracker.onLine(snapshotLine(0, 0, listOf(11, 12, 13), emptyList()))
        // P1P2: picked 11, 2-card pack
        tracker.onLine(snapshotLine(0, 1, listOf(12, 13), listOf(11)))
        // P1P3: picked 12, 1-card pack
        tracker.onLine(snapshotLine(0, 2, listOf(13), listOf(11, 12)))

        val s = tracker.state.value
        assertEquals(DraftPhase.DRAFTING, s.phase)
        assertEquals(DraftFormat.QUICK, s.format)
        assertEquals("SOS", s.setCode)
        assertEquals(1, s.pack)
        assertEquals(3, s.pick)
        assertEquals(listOf(13), s.packCards)
        assertEquals(listOf(11, 12), s.pool)
    }

    @Test
    fun marksComplete() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(2, 0, emptyList(), listOf(1, 2, 3), status = "Complete"))
        assertEquals(DraftPhase.COMPLETE, tracker.state.value.phase)
        assertEquals(listOf(1, 2, 3), tracker.state.value.pool)
    }

    @Test
    fun resetsWhenANewEventStarts() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(2, 5, listOf(50), listOf(1, 2, 3, 4, 5)))
        // A brand new draft event begins — pool must not carry over.
        tracker.onLine(
            snapshotLine(0, 0, listOf(80, 81), emptyList(), eventName = "QuickDraftEmblem_TLA_20260701"),
        )
        val s = tracker.state.value
        assertEquals("TLA", s.setCode)
        assertEquals(1, s.pack)
        assertEquals(1, s.pick)
        assertTrue(s.pool.isEmpty())
    }

    @Test
    fun accumulatesSeenPacksAcrossPicks() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(0, 0, listOf(11, 12, 13), emptyList()))
        tracker.onLine(snapshotLine(0, 1, listOf(12, 13), listOf(11)))
        val seen = tracker.state.value.seen
        assertEquals(2, seen.size)
        assertEquals(listOf(11, 12, 13), seen[1 to 1])
        assertEquals(listOf(12, 13), seen[1 to 2])
    }

    @Test
    fun ignoresLinesWithoutDraftEvents() {
        val tracker = DraftTracker()
        assertTrue(!tracker.onLine("just a log line"))
        assertEquals(DraftPhase.IDLE, tracker.state.value.phase)
    }

    @Test
    fun eventJoinedSetsFormatAndDoesNotResetEmptyPacks() {
        val tracker = DraftTracker()
        // Join PremiumDraft event
        tracker.onLine("[UnityCrossThreadLogger]==> Event.Join {\"EventName\":\"PremiumDraft_MKM_20240206\"}")
        var s = tracker.state.value
        assertEquals(DraftFormat.PREMIER, s.format)
        assertEquals("MKM", s.setCode)
        assertEquals("PremiumDraft_MKM_20240206", s.eventName)

        // Receive PackSeen without EventName (Draft.Notify simulation)
        tracker.onLine("[UnityCrossThreadLogger]==> Draft.Notify {\"SelfPack\":1,\"SelfPick\":2,\"PackCards\":\"100,200,300\"}")
        s = tracker.state.value
        assertEquals(DraftFormat.PREMIER, s.format) // retained!
        assertEquals("MKM", s.setCode) // retained!
        assertEquals("PremiumDraft_MKM_20240206", s.eventName) // retained!
        assertEquals(1, s.pack) // SelfPack=1 -> pack=1
        assertEquals(2, s.pick) // SelfPick=2 -> pick=2
        assertEquals(listOf(100, 200, 300), s.packCards)
    }
}
