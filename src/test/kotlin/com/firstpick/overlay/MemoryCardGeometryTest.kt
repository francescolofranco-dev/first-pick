package com.firstpick.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class MemoryCardGeometryTest {
    private val a = NormalizedRect(.1, .2, .12, .3)
    private val b = a.copy(x = .3)
    private fun placement(id: Int, zone: ArenaCardZone = ArenaCardZone.PACK, rect: NormalizedRect = a) =
        ArenaVisibleCard(id, 1, zone, rect)

    @Test fun clientCoordinatesAccountForTitleBarAndRetinaScaling() {
        val geometry = ArenaCardGeometry(2560, 1440, listOf(placement(1)))
        val adjusted = geometry.inWindow(WindowBounds(0, 0, 1280, 748)).single().bounds
        assertEquals(.1, adjusted.x)
        assertEquals((28 + .2 * 720) / 748, adjusted.y, .000001)
        assertEquals(.3 * 720 / 748, adjusted.height, .000001)
        assertEquals(a, geometry.inWindow(WindowBounds(0, 0, 1920, 1080)).single().bounds)
        assertTrue(geometry.inWindow(WindowBounds(0, 0, 1280, 900)).isEmpty())
    }

    @Test fun everyDuplicateCardKeepsItsOwnScoreRectangleAndBestPick() {
        val cards = listOf(OverlayCard(40.0, null, grpId = 2), OverlayCard(80.0, null, grpId = 1), OverlayCard(40.0, null, grpId = 2))
        val placements = listOf(placement(2), placement(1, rect = b), placement(2, rect = a.copy(x = .5)))
        val marks = draftScoreMarks(cards, placements)
        assertEquals(listOf(40.0, 80.0, 40.0), marks.map { it.value })
        assertEquals(listOf(false, true, false), marks.map { it.isBest })
        assertEquals(listOf(.1, .3, .5), marks.map { it.bounds.x })
        assertTrue(draftScoreMarks(cards, placements.reversed()).map { it.bounds.x } == listOf(.5, .3, .1))
        assertTrue(draftScoreMarks(cards, placements.drop(1)).isEmpty())
        assertTrue(draftScoreMarks(cards, placements.map { it.copy(grpId = 3) }).isEmpty())
    }

    @Test fun deckLabelsOnlyMarkTheCorrectActionAndVisibleArea() {
        val model = DeckGuidanceOverlayModel(DeckGuidanceStatus.NEEDS_CHANGES,
            changes = listOf(CardGuidance("Cut me", 0, 1), CardGuidance("Add me", 1, 0)),
            basicLandChanges = listOf(CardGuidance("Forest", 2, 1)))
        val names = mapOf(1 to "Cut me", 2 to "Add me", 3 to "Snow-Covered Forest", 4 to "Unknown")
        val positions = listOf(placement(1, ArenaCardZone.DECK), placement(1, ArenaCardZone.POOL),
            placement(2, ArenaCardZone.POOL), placement(3, ArenaCardZone.DECK), placement(4, ArenaCardZone.DECK))
        val marks = model.withMemoryMarks(positions, names).marks
        assertEquals(3, marks.size)
        assertIs<DeckGuidanceVisualMark.RemoveRow>(marks[0])
        assertFalse(assertIs<DeckGuidanceVisualMark.AddCard>(marks[1]).inDeckList)
        assertTrue(assertIs<DeckGuidanceVisualMark.AddCard>(marks[2]).inDeckList)
        assertTrue(model.copy(status = DeckGuidanceStatus.MATCHES).withMemoryMarks(positions, names).marks.isEmpty())
    }

    @Test fun inconsistentGeometryIsDiscardedWithoutLosingTheExactPackReading() {
        fun read(rows: String): ArenaMemoryRead.PackReady {
            val payload = """{"status":"draft_ready","editor_session":"${"a".repeat(64)}","cards":[1],"picked":[],"geometry":{"screen":[2560,1440],"cards":$rows}}"""
            return assertIs<ArenaMemoryRead.PackReady>(parseArenaMemoryRead(Json.parseToJsonElement(payload) as JsonObject))
        }
        assertNotNull(read("[[1,1,\"pack\",0.1,0.2,0.1,0.3]]").pack.geometry)
        for (rows in listOf("[[2,1,\"pack\",0.1,0.2,0.1,0.3]]", "[[1,1,\"deck\",0.1,0.2,0.1,0.3]]",
            "[[1,1,\"pack\",0.95,0.2,0.1,0.3]]", "[[1,0,\"pack\",0.1,0.2,0.1,0.3]]")) {
            val value = read(rows)
            assertEquals(listOf(1), value.pack.cards)
            assertNull(value.pack.geometry)
        }
    }
}
