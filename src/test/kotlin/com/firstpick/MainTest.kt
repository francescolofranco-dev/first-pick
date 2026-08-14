package com.firstpick

import com.firstpick.ui.PackCardUi
import com.firstpick.ui.DeckOptionUi
import com.firstpick.ui.DeckSpellUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {
    @Test
    fun overlayCardIndicesStayStableWhenRankedSuggestionsReorder() {
        val firstRanking = overlayCards(listOf(card("Top pick", originalIndex = 1, value = 67.0), card("Other", originalIndex = 0, value = 33.0)))
        val updatedRanking = overlayCards(listOf(card("Other", originalIndex = 0, value = 70.0), card("Top pick", originalIndex = 1, value = 42.0)))

        assertEquals(listOf("Other", "Top pick"), firstRanking.map { it.name })
        assertEquals(firstRanking.map { it.originalIndex }, updatedRanking.map { it.originalIndex })
        assertEquals(listOf(70.0, 42.0), updatedRanking.map { it.value })
    }

    @Test
    fun overlayCardsPropagateRoomMetadata() {
        val cards = overlayCards(
            listOf(
                card("Ordinary card", originalIndex = 1, value = 40.0),
                card("Split Room", originalIndex = 0, value = 60.0, isRoom = true),
            ),
        )

        assertEquals(listOf("Split Room", "Ordinary card"), cards.map { it.name })
        assertEquals(listOf(true, false), cards.map { it.isRoom })
    }

    @Test
    fun overlayRetainsAnUnratedBasicAtItsOriginalPosition() {
        val cards = overlayCards(
            listOf(
                card("Rated card", originalIndex = 0, value = 61.0),
                card("Island", originalIndex = 1, value = null, isBasicLand = true),
            ),
        )

        assertEquals(listOf("Rated card", "Island"), cards.map { it.name })
        assertEquals(listOf(61.0, null), cards.map { it.value })
        assertEquals(listOf(0, 1), cards.map { it.originalIndex })
    }

    @Test
    fun deckGuidanceIncludesSpellsAndDraftedNonbasicLandsButFlagsBasics() {
        val option = DeckOptionUi(
            colors = "WU",
            basePair = "WU",
            tier = "B",
            type = "Tempo",
            outlook = "Solid",
            power = 70,
            identityConfidence = "High",
            identityReasons = emptyList(),
            powerConfidence = "High",
            powerReasons = emptyList(),
            creatures = 15,
            removal = 4,
            landLine = "17 lands",
            spells = listOf(DeckSpellUi("Stock Up", 3, "U", null, count = 2)),
            lands = listOf(DeckSpellUi("Drafted Dual", 0, "WU", null, isLand = true)),
        )

        assertEquals(
            listOf("Stock Up" to 2, "Drafted Dual" to 1),
            deckGuidanceTarget(option).map { it.name to it.count },
        )

        val pool = deckGuidancePool(
            listOf(
                DeckSpellUi("Plains", 0, "W", null, count = 8, isLand = true, isBasicLand = true),
                DeckSpellUi("Drafted Dual", 0, "WU", null, isLand = true),
            ),
        )
        assertEquals(listOf(true, false), pool.map { it.isBasicLand })
    }

    @Test
    fun guideLinksOnlyOpenValidHttpsSources() {
        assertEquals("magic.wizards.com", guideSourceUri("https://magic.wizards.com/en/news")?.host)
        assertNull(guideSourceUri("http://example.com"))
        assertNull(guideSourceUri("file:///tmp/guide"))
        assertNull(guideSourceUri("not a URI"))
    }

    private fun card(
        name: String,
        originalIndex: Int,
        value: Double?,
        isRoom: Boolean = false,
        isBasicLand: Boolean = false,
    ) = PackCardUi(
        grpId = originalIndex,
        originalIndex = originalIndex,
        rank = 1,
        name = name,
        color = "",
        rarity = "",
        gihWr = null,
        alsa = null,
        ata = null,
        value = value,
        isRoom = isRoom,
        isBasicLand = isBasicLand,
    )
}
