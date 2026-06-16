package com.firstpick.log

import com.firstpick.model.DraftEvent
import com.firstpick.model.DraftFormat
import com.firstpick.model.setCodeFromEventName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventParserTest {
    private val parser = EventParser()

    /** Builds a real-shaped Quick/Bot draft snapshot line (nested escaped JSON). */
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
    fun parsesRealFirstSnapshotFixture() {
        val line = javaClass.getResourceAsStream("/quickdraft_first_snapshot.log")!!
            .bufferedReader().readText().trim()
        val event = parser.parse(line)
        assertNotNull(event)
        val snap = event as DraftEvent.Snapshot
        assertEquals("QuickDraftEmblem_SOS_20260611", snap.eventName)
        assertEquals(1, snap.pack)  // 0-indexed -> 1
        assertEquals(1, snap.pick)
        assertEquals(14, snap.packCards.size)
        assertEquals(102490, snap.packCards.first())
        assertEquals(102721, snap.packCards.last())
        assertTrue(snap.pool.isEmpty())
        assertTrue(!snap.complete)
    }

    @Test
    fun deriveSetAndFormatFromEventName() {
        assertEquals("SOS", setCodeFromEventName("QuickDraftEmblem_SOS_20260611"))
        assertEquals(DraftFormat.QUICK, DraftFormat.fromEventName("QuickDraftEmblem_SOS_20260611"))
        assertEquals(DraftFormat.PREMIER, DraftFormat.fromEventName("PremierDraft_OTJ_20240416"))
        assertEquals(DraftFormat.TRADITIONAL, DraftFormat.fromEventName("TradDraft_DSK_20240924"))
    }

    @Test
    fun convertsZeroIndexedPackPick() {
        val snap = parser.parse(snapshotLine(pack = 2, pick = 5, packCards = listOf(1, 2, 3), pickedCards = listOf(9)))
        assertNotNull(snap)
        snap as DraftEvent.Snapshot
        assertEquals(3, snap.pack) // 2 -> 3
        assertEquals(6, snap.pick) // 5 -> 6
        assertEquals(listOf(1, 2, 3), snap.packCards)
        assertEquals(listOf(9), snap.pool)
    }

    @Test
    fun completeWhenStatusNotPickNext() {
        val snap = parser.parse(
            snapshotLine(pack = 2, pick = 0, packCards = emptyList(), pickedCards = listOf(1, 2, 3), status = "Complete"),
        ) as DraftEvent.Snapshot
        assertTrue(snap.complete)
    }

    @Test
    fun parsesBotDraftPick() {
        val line = "[UnityCrossThreadLogger]==> BotDraftDraftPick {\"id\":\"abc\"," +
            "\"request\":\"{\\\"EventName\\\":\\\"QuickDraftEmblem_SOS_20260611\\\"," +
            "\\\"PickInfo\\\":{\\\"EventName\\\":\\\"QuickDraftEmblem_SOS_20260611\\\"," +
            "\\\"CardIds\\\":[\\\"102579\\\"],\\\"PackNumber\\\":0,\\\"PickNumber\\\":0}}\"}"
        val event = parser.parse(line)
        assertNotNull(event)
        event as DraftEvent.PickMade
        assertEquals(listOf(102579), event.cardIds)
        assertEquals(1, event.pack)
        assertEquals(1, event.pick)
    }

    @Test
    fun ignoresNoiseAndOtherModules() {
        assertNull(parser.parse("Wotc.Mtga.Wrapper.Draft.DraftContentController:ReserveCardAndLockIn(DraftPack)"))
        assertNull(parser.parse("[UnityCrossThreadLogger] some plain text without json"))
        // A DeckSelect module line that merely mentions DraftPack must not be treated as a pack.
        assertNull(
            parser.parse("{\"CurrentModule\":\"DeckSelect\",\"Payload\":\"{\\\"DraftPack\\\":[]}\"}"),
        )
    }
}
