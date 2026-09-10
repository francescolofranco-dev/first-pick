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

    private fun pickLine(
        cardIds: List<Int>,
        pack: Int,
        pick: Int,
        eventName: String = "QuickDraftEmblem_SOS_20260611",
    ): String {
        val ids = cardIds.joinToString(",") { "\\\"$it\\\"" }
        val request = "{\\\"EventName\\\":\\\"$eventName\\\",\\\"PickInfo\\\":" +
            "{\\\"EventName\\\":\\\"$eventName\\\",\\\"CardIds\\\":[$ids]," +
            "\\\"PackNumber\\\":$pack,\\\"PickNumber\\\":$pick}}"
        return "[UnityCrossThreadLogger]==> BotDraftDraftPick {\"id\":\"test\",\"request\":\"$request\"}"
    }

    @Test
    fun reconstructsQuickDraftFromSnapshots() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(0, 0, listOf(11, 12, 13), emptyList()))
        tracker.onLine(snapshotLine(0, 1, listOf(12, 13), listOf(11)))
        tracker.onLine(snapshotLine(0, 2, listOf(13), listOf(11, 12)))

        val s = tracker.state.value
        assertEquals(DraftPhase.DRAFTING, s.phase)
        assertEquals(DraftFormat.QUICK, s.format)
        assertEquals("SOS", s.setCode)
        assertEquals(1, s.pack)
        assertEquals(3, s.pick)
        assertEquals(listOf(13), s.packCards)
        assertEquals(listOf(11, 12), s.pool)
        assertTrue(s.poolOrderKnown, "single-card snapshot deltas reconstruct exact chronology")
    }

    @Test
    fun reorderedSnapshotsDoNotRewriteKnownPickChronology() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(0, 0, listOf(10, 20, 30), emptyList()))
        tracker.onLine(pickLine(listOf(30), pack = 0, pick = 0))
        tracker.onLine(snapshotLine(0, 1, listOf(10, 20), listOf(30)))
        tracker.onLine(pickLine(listOf(10), pack = 0, pick = 1))

        tracker.onLine(snapshotLine(0, 2, listOf(20), listOf(10, 30)))
        assertEquals(listOf(30, 10), tracker.state.value.pool)
        assertTrue(tracker.state.value.poolOrderKnown)

        tracker.onLine(snapshotLine(0, 2, listOf(20), listOf(30, 10)))
        assertEquals(listOf(30, 10), tracker.state.value.pool)
    }

    @Test
    fun reorderedSnapshotsPreserveDuplicatePickMultiplicityAndChronology() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(0, 0, listOf(10, 30), emptyList()))
        tracker.onLine(pickLine(listOf(30), pack = 0, pick = 0))
        tracker.onLine(snapshotLine(0, 1, listOf(10, 30), listOf(30)))
        tracker.onLine(pickLine(listOf(10), pack = 0, pick = 1))
        tracker.onLine(snapshotLine(0, 2, listOf(30), listOf(10, 30)))
        tracker.onLine(pickLine(listOf(30), pack = 0, pick = 2))

        tracker.onLine(snapshotLine(0, 3, emptyList(), listOf(30, 30, 10)))

        assertEquals(listOf(30, 10, 30), tracker.state.value.pool)
    }

    @Test
    fun coldSnapshotAndLaterReconciliationUseExactAuthoritativeMultiset() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(1, 4, listOf(60), listOf(40, 20, 20, 10)))
        assertEquals(listOf(10, 20, 20, 40), tracker.state.value.pool)
        assertTrue(!tracker.state.value.poolOrderKnown)

        tracker.onLine(pickLine(listOf(30), pack = 1, pick = 4))
        tracker.onLine(snapshotLine(1, 5, listOf(61), listOf(50, 20, 30, 10, 20)))

        assertEquals(listOf(10, 20, 20, 30, 50), tracker.state.value.pool)
    }

    @Test
    fun resetReturnsToIdle() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(0, 0, listOf(11, 12, 13), emptyList()))
        assertEquals(DraftPhase.DRAFTING, tracker.state.value.phase)

        tracker.reset()

        val s = tracker.state.value
        assertEquals(DraftPhase.IDLE, s.phase)
        assertTrue(s.setCode == null)
        assertTrue(s.pool.isEmpty() && s.packCards.isEmpty())
    }

    @Test
    fun marksComplete() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(2, 0, emptyList(), listOf(1, 2, 3), status = "Complete"))
        assertEquals(DraftPhase.COMPLETE, tracker.state.value.phase)
        assertEquals(listOf(1, 2, 3), tracker.state.value.pool)
    }

    @Test
    fun premierDraftCompletesWhenTheFinalPickLogIsConfirmed() {
        val tracker = DraftTracker()
        tracker.onLine("[UnityCrossThreadLogger]==> Event.Join {\"EventName\":\"PremierDraft_MKM_20240206\"}")
        tracker.onLine("[UnityCrossThreadLogger]==> Draft.Notify {\"SelfPack\":3,\"SelfPick\":14,\"PackCards\":\"900\"}")
        assertEquals(DraftPhase.DRAFTING, tracker.state.value.phase)

        tracker.onLine(
            "[UnityCrossThreadLogger]==> PlayerDraftMakePick " +
                "{\"request\":\"{\\\"GrpIds\\\":[\\\"900\\\"],\\\"PackNumber\\\":2,\\\"PickNumber\\\":13}\"}",
        )

        assertEquals(DraftPhase.COMPLETE, tracker.state.value.phase)
        assertEquals(listOf(900), tracker.state.value.pool)
    }

    @Test
    fun resetsWhenANewEventStarts() {
        val tracker = DraftTracker()
        tracker.onLine(snapshotLine(2, 5, listOf(50), listOf(1, 2, 3, 4, 5)))
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
    fun aPickClearsThePackUntilTheNextOneArrives() {


        val tracker = DraftTracker()
        tracker.onLine("[UnityCrossThreadLogger]==> Event.Join {\"EventName\":\"PremierDraft_MKM_20240206\"}")
        tracker.onLine("[UnityCrossThreadLogger]==> Draft.Notify {\"SelfPack\":1,\"SelfPick\":1,\"PackCards\":\"100,200,300\"}")
        assertEquals(listOf(100, 200, 300), tracker.state.value.packCards)

        tracker.onLine("[UnityCrossThreadLogger]==> PlayerDraftMakePick {\"request\":\"{\\\"GrpIds\\\":[\\\"200\\\"],\\\"PackNumber\\\":0,\\\"PickNumber\\\":0}\"}")
        val s = tracker.state.value
        assertTrue(s.packCards.isEmpty(), "pack must clear on pick, was ${s.packCards}")
        assertEquals(listOf(200), s.pool)
        assertEquals(DraftPhase.DRAFTING, s.phase)

        tracker.onLine("[UnityCrossThreadLogger]==> Draft.Notify {\"SelfPack\":1,\"SelfPick\":2,\"PackCards\":\"101,201\"}")
        assertEquals(listOf(101, 201), tracker.state.value.packCards)
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
        tracker.onLine("[UnityCrossThreadLogger]==> Event.Join {\"EventName\":\"PremiumDraft_MKM_20240206\"}")
        var s = tracker.state.value
        assertEquals(DraftFormat.PREMIER, s.format)
        assertEquals("MKM", s.setCode)
        assertEquals("PremiumDraft_MKM_20240206", s.eventName)

        tracker.onLine("[UnityCrossThreadLogger]==> Draft.Notify {\"SelfPack\":1,\"SelfPick\":2,\"PackCards\":\"100,200,300\"}")
        s = tracker.state.value
        assertEquals(DraftFormat.PREMIER, s.format)
        assertEquals("MKM", s.setCode)
        assertEquals("PremiumDraft_MKM_20240206", s.eventName)
        assertEquals(1, s.pack)
        assertEquals(2, s.pick)
        assertEquals(listOf(100, 200, 300), s.packCards)
    }
}
