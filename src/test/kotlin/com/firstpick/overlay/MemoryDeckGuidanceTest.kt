package com.firstpick.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryDeckGuidanceTest {
    private val frost = "Hapatra, the Desert Frost"
    private val fang = "Hapatra, the Desert Fang"
    private val protege = "Protege's Awakening"
    private val names = mapOf(106460 to frost, 106516 to fang, 106263 to protege, 75022 to "Island", 75023 to "Swamp")
    private val pool = listOf(DeckCardCount(frost, 1), DeckCardCount(fang, 1), DeckCardCount(protege, 2))
    private fun raw() = ArenaMemoryDeck("a".repeat(64),
        listOf(ArenaCardQuantity(106460, 1), ArenaCardQuantity(106263, 2), ArenaCardQuantity(75022, 37)),
        listOf(ArenaCardQuantity(106460, 1), ArenaCardQuantity(106516, 1), ArenaCardQuantity(106263, 2),
            ArenaCardQuantity(75022, 37), ArenaCardQuantity(75023, 1)), 40)

    @Test fun exactIdsDisambiguateHapatraAndVerifyAbsentCopiesWithoutScrolling() {
        val current = assertIs<MemoryDeckValidation.Ready>(validateMemoryDeck(raw(), pool, names))
        val target = listOf(DeckCardCount(fang, 1), DeckCardCount(protege, 2))
        val result = memoryGuidanceModel(target, current.deck)
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, result.status)
        assertEquals(1, result.remainingAdds)
        assertEquals(1, result.remainingRemovals)
        assertEquals(0, result.basicLandDelta)
        assertEquals(DeckGuidanceAction.REMOVE, result.changes.single { it.name == frost }.action)
        assertEquals(DeckGuidanceAction.ADD, result.changes.single { it.name == fang }.action)
    }

    @Test fun anotherDraftWithTheSameMainCardsButDifferentPoolIsRejected() {
        val extra = raw().copy(pool = raw().pool + ArenaCardQuantity(42, 1))
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(extra, pool, names + (42 to "Another Card")))
        val missingDuplicate = raw().copy(pool = raw().pool.map { if (it.grpId == 106263) it.copy(count = 1) else it })
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(missingDuplicate, pool, names))
    }

    @Test fun incompleteCatalogAndTruncatedCatalogIdentityNeverCountAsExact() {
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(raw(), pool, names - 75022))
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(raw(), pool, names + (106460 to "Hapatra, the Dese…")))
    }

    @Test fun overDraftedCopiesAndInconsistentTotalsAreRejected() {
        val extra = raw().copy(main = raw().main.map { if (it.grpId == 106263) it.copy(count = 3) else it }, total = 41)
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(extra, pool, names))
        assertIs<MemoryDeckValidation.Rejected>(validateMemoryDeck(raw().copy(total = 39), pool, names))
    }

    @Test fun differentBasicPrintingsDoNotChangeTheDraftPoolIdentity() {
        val value = raw().copy(pool = raw().pool + ArenaCardQuantity(92374, 1))
        assertIs<MemoryDeckValidation.Ready>(validateMemoryDeck(value, pool, names + (92374 to "Island")))
    }

    @Test fun aSecondPollIsRequiredAndEqualTotalSwapsInvalidateOldInstructions() {
        val session = MemoryDeckGuidanceSession { names }
        assertIs<MemoryDeckValidation.Rejected>(session.observe(raw(), pool, 0))
        assertIs<MemoryDeckValidation.Rejected>(session.observe(raw(), pool, 100))
        assertIs<MemoryDeckValidation.Ready>(session.observe(raw(), pool, 150))
        val swapped = raw().copy(main = raw().main.map { if (it.grpId == 106460) it.copy(grpId = 106516) else it })
        assertIs<MemoryDeckValidation.Rejected>(session.observe(swapped, pool, 300))
        val stable = assertIs<MemoryDeckValidation.Ready>(session.observe(swapped, pool, 450))
        assertEquals(fang, stable.deck.cards.first().name)
    }

    @Test fun editorSwitchesAndCaptureGapsRequireReverification() {
        val session = MemoryDeckGuidanceSession { names }
        session.observe(raw(), pool, 0)
        assertIs<MemoryDeckValidation.Ready>(session.observe(raw(), pool, 200))
        assertIs<MemoryDeckValidation.Rejected>(session.observe(raw().copy(editorSession = "b".repeat(64)), pool, 400))
        session.invalidate()
        assertIs<MemoryDeckValidation.Rejected>(session.observe(raw(), pool, 600))
        assertIs<MemoryDeckValidation.Ready>(session.observe(raw(), pool, 800))
        assertIs<MemoryDeckValidation.Rejected>(session.observe(raw(), pool, 4000))
    }

    @Test fun structuredResponsesRejectMalformedAndPartialCounts() {
        fun parse(value: String) = parseArenaMemoryRead(Json.parseToJsonElement(value) as JsonObject)
        val ready = """{"status":"ready","editor_session":"${"a".repeat(64)}","main":[[75022,40]],"sideboard":[],"pool":[[75022,40]],"total":40}"""
        assertIs<ArenaMemoryRead.Ready>(parse(ready))
        for (bad in listOf(ready.replace("\"total\":40", "\"total\":39"), ready.replace("[75022,40]", "[75022,-1]"),
                ready.replace("\"sideboard\":[],", ""), ready.replace("[[75022,40]]", "[[75022,20],[75022,20]]"))) {
            assertIs<ArenaMemoryRead.Unavailable>(parse(bad))
        }
        assertIs<ArenaMemoryRead.Unavailable>(parse("""{"status":"unavailable"}"""))
        assertEquals(MemoryUnavailableKind.ACCESS_REQUIRED, assertIs<ArenaMemoryRead.Unavailable>(parse("""{"status":"access_required"}""")).kind)
        assertEquals(MemoryUnavailableKind.UNSUPPORTED, assertIs<ArenaMemoryRead.Unavailable>(parse("""{"status":"unsupported_build"}""")).kind)
        assertIs<ArenaMemoryRead.Paused>(parse("""{"status":"blocked","reason":"Close dialog"}"""))
    }
}
