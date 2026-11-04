package com.firstpick.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class MemoryCardGeometryTest {
    private val a = NormalizedRect(.1, .2, .12, .3)
    private val b = a.copy(x = .3)
    private fun placement(id: Int, zone: ArenaCardZone = ArenaCardZone.PACK, rect: NormalizedRect = a) =
        ArenaVisibleCard(id, 1, zone, rect)

    @Test fun recommendationEmphasisFollowsExactCardGeometry() {
        val cards = listOf(
            OverlayCard(80.0, null, grpId = 1, highlight = com.firstpick.ui.PickHighlight.CONTENDER),
            OverlayCard(78.0, null, grpId = 2, highlight = com.firstpick.ui.PickHighlight.CONTENDER),
            OverlayCard(60.0, null, grpId = 3),
        )
        val marks = draftScoreMarks(cards, listOf(placement(1), placement(2, rect = b), placement(3)))
        assertEquals(cards.map { it.highlight }, marks.map { it.highlight })
        assertEquals(b, marks[1].bounds)
    }

    @Test fun clientCoordinatesAccountForTitleBarAndRetinaScaling() {
        val geometry = ArenaCardGeometry(2560, 1440, listOf(placement(1)))
        val adjusted = geometry.inWindow(WindowBounds(0, 0, 1280, 748)).single().bounds
        assertEquals(.1, adjusted.x)
        assertEquals((28 + .2 * 720) / 748, adjusted.y, .000001)
        assertEquals(.3 * 720 / 748, adjusted.height, .000001)
        assertEquals(a, geometry.inWindow(WindowBounds(0, 0, 1920, 1080)).single().bounds)
        assertTrue(geometry.inWindow(WindowBounds(0, 0, 1280, 900)).isEmpty())
    }

    @Test fun sameAspectRatioResizeCannotReuseOldPixelGeometry() {
        val geometry = ArenaCardGeometry(2560, 1440, listOf(placement(1)))
        assertEquals(1, geometry.inWindow(WindowBounds(0, 0, 1280, 748, backingScale = 2.0)).size)
        assertTrue(geometry.inWindow(WindowBounds(0, 0, 1600, 928, backingScale = 2.0)).isEmpty())
        assertEquals(1, geometry.copy(screenWidth = 3200, screenHeight = 1800)
            .inWindow(WindowBounds(0, 0, 1600, 928, backingScale = 2.0)).size)
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

    @Test fun hoverPreviewHidesCoveredCardsAndRepeatsTheHoveredScore() {
        val payload = """{"status":"draft_ready","editor_session":"${"a".repeat(64)}","cards":[1,2,3],"picked":[],
            "geometry":{"screen":[1920,1080],"cards":[
                [1,1,"pack",0.05,0.2,0.12,0.3], [2,1,"pack",0.3,0.2,0.12,0.3], [3,1,"pack",0.5,0.2,0.12,0.3],
                [1,1,"preview",0.22,0.05,0.28,0.85]]}}"""
        val read = assertIs<ArenaMemoryRead.PackReady>(parseArenaMemoryRead(Json.parseToJsonElement(payload) as JsonObject))
        val geometry = assertNotNull(read.pack.geometry, "An Arena hover preview must retain usable geometry")
        val cards = listOf(OverlayCard(80.0, null, grpId = 1), OverlayCard(40.0, null, grpId = 2), OverlayCard(null, null, grpId = 3))
        val marks = draftScoreMarks(cards, geometry.cards)
        assertEquals(listOf(80.0, null, 80.0), marks.map { it.value })
        assertEquals(listOf(true, false, true), marks.map { it.isBest })
        assertEquals(NormalizedRect(.22, .05, .28, .85), marks.last().bounds)
        val restored = draftScoreMarks(cards, geometry.cards.take(3))
        assertEquals(listOf(80.0, 40.0, null), restored.map { it.value })
    }

    @Test fun tooltipOcclusionSurvivesTitleBarCoordinateConversion() {
        val geometry = ArenaCardGeometry(2560, 1440, listOf(placement(1), placement(2, rect = b)), listOf(b))
        val placements = geometry.inWindow(WindowBounds(0, 0, 1280, 748, backingScale = 2.0))
        val marks = draftScoreMarks(listOf(OverlayCard(80.0, null, grpId = 1), OverlayCard(40.0, null, grpId = 2)), placements)
        assertEquals(listOf(80.0), marks.map { it.value })
    }

    @Test fun anUnratedOrOutOfPackPreviewStillHidesTheCardsBehindIt() {
        val preview = ArenaVisibleCard(9, 1, ArenaCardZone.PREVIEW, b)
        assertTrue(draftScoreMarks(listOf(OverlayCard(40.0, null, grpId = 1)), listOf(placement(1, rect = b), preview)).isEmpty())
        val unrated = preview.copy(grpId = 1)
        val mark = draftScoreMarks(listOf(OverlayCard(null, null, grpId = 1)), listOf(placement(1), unrated)).last()
        assertNull(mark.value)
        assertEquals(b, mark.bounds)
    }

    @Test fun aHoverCoveringCardTitlesKeepsEveryUncoveredScoreBadge() {
        val cards = listOf(OverlayCard(80.0, null, grpId = 1), OverlayCard(70.0, null, grpId = 2))
        val titleTooltip = ArenaVisibleCard(9, 1, ArenaCardZone.PREVIEW, NormalizedRect(0.0, .2, 1.0, .1))
        val marks = draftScoreMarks(cards, listOf(placement(1), placement(2, rect = b), titleTooltip))
        assertEquals(listOf(80.0, 70.0), marks.map { it.value }, "Only covered badge footprints should disappear")
    }

    @Test fun capturedArenaHoverPreservesUncoveredBadgesAndAddsThePreviewScore() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/overlay/arena-hover-preview.json")!!.readText()) as JsonObject
        val read = assertIs<ArenaMemoryRead.PackReady>(parseArenaMemoryRead(Json.parseToJsonElement(
            """{"status":"draft_ready","editor_session":"${"a".repeat(64)}","cards":${fixture["cards"]},"picked":[],"geometry":${fixture["geometry"]}}"""
        ) as JsonObject))
        val geometry = assertNotNull(read.pack.geometry)
        assertEquals(2, geometry.occlusions.size)
        val cards = read.pack.cards.mapIndexed { index, id -> OverlayCard(80.0 - index, null, grpId = id) }
        val marks = draftScoreMarks(cards, geometry.inWindow(WindowBounds(0, 0, 1280, 748, backingScale = 2.0)), 1280f, 748f)
        assertEquals(listOf(80.0, 76.0, 75.0, 80.0), marks.map { it.value },
            "Geist, Emberling and Phytomedic stay visible; only covered scores disappear, and the preview repeats Geist")
    }

    @Test fun coveredInputSlotsSurviveHoverTransitionsForWindowReuse() {
        val cards = listOf(OverlayCard(80.0, null, grpId = 1), OverlayCard(70.0, null, grpId = 2))
        val pack = listOf(placement(1), placement(2, rect = b))
        val preview = ArenaVisibleCard(1, 1, ArenaCardZone.PREVIEW, b)
        val before = draftScoreMarks(cards, pack, includeCovered = true)
        val during = draftScoreMarks(cards, pack + preview, includeCovered = true)
        assertEquals(before.map { it.slot }, during.filter { (it.slot ?: -1) >= 0 }.map { it.slot },
            "Occlusion must hide the existing input panel rather than remove and recreate its native window")
        assertTrue(during.single { it.slot == 1 }.covered)
    }

    @Test fun duplicatePreviewPreservesTheHoveredCopysRecommendationAndDetails() {
        val cards = listOf(OverlayCard(80.0, null, grpId = 1, recommendedPair = false),
            OverlayCard(80.0, null, grpId = 1, recommendedPair = true))
        val preview = ArenaVisibleCard(1, 1, ArenaCardZone.PREVIEW, NormalizedRect(.6, .1, .3, .8), sourceIndex = 1)
        assertTrue(draftScoreMarks(cards, listOf(placement(1), placement(1, rect = b), preview)).last().isBest)
    }

    @Test fun horizontalHighlightsUseTheWholeVisibleEntryRatherThanItsGutter() {
        val model = DeckGuidanceOverlayModel(DeckGuidanceStatus.NEEDS_CHANGES,
            changes = listOf(CardGuidance("Cut me", 0, 1)))
        val previous = placement(2, ArenaCardZone.COLUMN, NormalizedRect(.1, .4, .1, .03))
        val current = placement(1, ArenaCardZone.COLUMN, NormalizedRect(.211, .4, .1, .03))
        val mark = assertIs<DeckGuidanceVisualMark.RemoveRow>(model.withMemoryMarks(
            listOf(previous, current), mapOf(1 to "Cut me", 2 to "Keep me")).marks.single())
        assertTrue(mark.compact)
        assertEquals(current.bounds, mark.bounds)
        val front = current.copy(bounds = current.bounds.copy(height = .3))
        assertEquals(front.bounds, model.withMemoryMarks(listOf(previous, front), mapOf(1 to "Cut me")).marks.single().bounds)
    }
}
