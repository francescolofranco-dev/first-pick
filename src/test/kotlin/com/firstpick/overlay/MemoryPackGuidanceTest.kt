package com.firstpick.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class MemoryPackGuidanceTest {
    @Test fun liveThirteenCardSecondPackRecoversWithoutDroppingOtherScores() {
        val ids = listOf(106424, 106454, 106250, 106463, 106480, 106314, 106240, 106265, 106285, 106305, 106350, 106411, 75022)
        val snapshot = ArenaMemoryPack("b".repeat(64), ids, listOf(ArenaCardQuantity(106259, 1)))
        val recommendations = ids.mapIndexed { index, id ->
            OverlayCard(if (id == 75022) null else 50.0 + index, null,
                if (id == 75022) "Island" else "Card $id", originalIndex = index,
                grpId = if (id == 75022) 106528 else id)
        }.reversed()
        val session = MemoryPackGuidanceSession { mapOf(106259 to "Mindseeker Oculus", 75022 to "Island", 106528 to "Island") }
        val picked = listOf(DeckCardCount("Mindseeker Oculus", 1))
        session.observe(snapshot, recommendations, picked, 0)
        for (time in 200L..2000L step 100) {
            val ready = assertIs<MemoryPackValidation.Ready>(session.observe(snapshot, recommendations, picked, time))
            assertEquals(ids, ready.cards.map { it.grpId })
            assertEquals((0..11).map { 50.0 + it } + null, ready.cards.map { it.value })
        }
        assertIs<MemoryPackValidation.Rejected>(session.observe(snapshot.copy(cards = ids.toMutableList().apply { this[0] = 999 }), recommendations, picked, 2100))
    }
    @Test fun nextPackWithAnArenaSubstitutedBasicPrintingStillPublishesAllBadges() {
        val next = ArenaMemoryPack("b".repeat(64), listOf(106424, 75022), listOf(ArenaCardQuantity(106259, 1)))
        val recommendations = listOf(OverlayCard(65.0, null, "Countersculpt", grpId = 106424),
            OverlayCard(null, null, "Island", grpId = 106528))
        val session = MemoryPackGuidanceSession { mapOf(106259 to "Mindseeker Oculus", 75022 to "Island", 106528 to "Island") }
        val picked = listOf(DeckCardCount("Mindseeker Oculus", 1))
        session.observe(next, recommendations, picked, 0)
        val ready = assertIs<MemoryPackValidation.Ready>(session.observe(next, recommendations, picked, 200))
        assertEquals(next.cards, ready.cards.map { it.grpId })
        assertEquals(listOf(65.0, null), ready.cards.map { it.value })
        assertIs<MemoryPackValidation.Rejected>(session.observe(next.copy(cards = listOf(106424, 75023)), recommendations, picked, 400))
    }
    private val cards = listOf(
        OverlayCard(70.0, null, "One", originalIndex = 0, grpId = 1),
        OverlayCard(40.0, null, "Two", originalIndex = 1, grpId = 2),
        OverlayCard(40.0, null, "Two", originalIndex = 2, grpId = 2),
    )
    private val raw = ArenaMemoryPack("a".repeat(64), listOf(2, 1, 2), listOf(ArenaCardQuantity(3, 2)))
    private val pool = listOf(DeckCardCount("Previously drafted", 2))
    private val names = mapOf(3 to "Previously drafted", 4 to "Different draft")

    @Test fun duplicateCardsKeepTheirIdsWhenArenaSortsThePackDifferently() {
        val verified = assertIs<MemoryPackValidation.Ready>(validateMemoryPack(raw, cards, pool, names))
        assertEquals(listOf(2, 1, 2), verified.cards.map { it.grpId })
        assertEquals(listOf(40.0, 70.0, 40.0), verified.cards.map { it.value })
        assertEquals(listOf(1, 0, 2), verified.cards.map { it.originalIndex })
    }

    @Test fun equalSizePackChangesNeverReuseOldGrades() {
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw.copy(cards = listOf(2, 1, 5)), cards, pool, names))
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw.copy(cards = listOf(1, 1, 2)), cards, pool, names))
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw, cards.map { it.copy(grpId = 0) }, pool, names))
    }

    @Test fun anIdenticalPackFromADifferentDraftOrStalePickedPoolIsRejected() {
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw.copy(picked = listOf(ArenaCardQuantity(4, 2))), cards, pool, names))
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw.copy(picked = listOf(ArenaCardQuantity(3, 1))), cards, pool, names))
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw, cards, pool, emptyMap()))
    }

    @Test fun firstPickCanBeVerifiedWithoutPreviouslyDraftedCards() {
        assertIs<MemoryPackValidation.Ready>(validateMemoryPack(raw.copy(picked = emptyList()), cards, emptyList(), emptyMap()))
        assertIs<MemoryPackValidation.Rejected>(validateMemoryPack(raw, cards, emptyList(), names))
    }

    @Test fun twoStableObservationsAreRequiredAfterAnEditEditorChangeOrGap() {
        val session = MemoryPackGuidanceSession { names }
        assertIs<MemoryPackValidation.Rejected>(session.observe(raw, cards, pool, 0))
        assertIs<MemoryPackValidation.Ready>(session.observe(raw, cards, pool, 150))
        assertIs<MemoryPackValidation.Rejected>(session.observe(raw.copy(editorSession = "b".repeat(64)), cards, pool, 300))
        session.invalidate()
        assertIs<MemoryPackValidation.Rejected>(session.observe(raw, cards, pool, 450))
        assertIs<MemoryPackValidation.Ready>(session.observe(raw, cards, pool, 600))
        assertIs<MemoryPackValidation.Ready>(session.observe(raw, cards, pool, 1600))
        assertIs<MemoryPackValidation.Rejected>(session.observe(raw, cards, pool, 4000))
    }

    @Test fun movingGeometryAndFastPollingDoNotRevalidateAnUnchangedPack() {
        val session = MemoryPackGuidanceSession { names }
        session.observe(raw, cards, pool, 0)
        assertIs<MemoryPackValidation.Ready>(session.observe(raw, cards, pool, 150))
        for (time in 250L..2000L step 100) {
            val geometry = ArenaCardGeometry(2560, 1440, raw.cards.mapIndexed { i, id ->
                ArenaVisibleCard(id, 1, ArenaCardZone.PACK, NormalizedRect(.1 + i * .2, .2 + time / 100000.0, .1, .3))
            })
            assertIs<MemoryPackValidation.Ready>(session.observe(raw.copy(geometry = geometry), cards, pool, time))
        }
    }

    @Test fun boundedDraftPayloadsAllowDuplicatesButRejectPartialAndInvalidIds() {
        fun parse(value: String) = parseArenaMemoryRead(Json.parseToJsonElement(value) as JsonObject)
        val ready = """{"status":"draft_ready","editor_session":"${"a".repeat(64)}","cards":[2,1,2],"picked":[[3,2]]}"""
        assertEquals(raw, assertIs<ArenaMemoryRead.PackReady>(parse(ready)).pack)
        for (bad in listOf(ready.replace("[2,1,2]", "[]"), ready.replace("[2,1,2]", "[2,-1,2]"),
                ready.replace("[2,1,2]", "[${(1..31).joinToString()}]"), ready.replace(",\"picked\":[[3,2]]", ""),
                ready.replace("[[3,2]]", "[[3,2],[3,1]]"))) {
            assertIs<ArenaMemoryRead.Unavailable>(parse(bad))
        }
    }

    @Test fun recommendedPairsSortTogetherAndUnratedCardsDoNotBecomeBestPicks() {
        val values = cards + OverlayCard(null, null, "Unrated", grpId = 4) +
            OverlayCard(50.0, null, "Pair", recommendedPair = true, grpId = 5)
        assertEquals(listOf(5, 1, 2, 2, 4), rankedMemoryPicks(values).map { it.grpId })
    }
}
