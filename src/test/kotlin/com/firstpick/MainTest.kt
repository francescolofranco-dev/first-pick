package com.firstpick

import com.firstpick.ui.PackCardUi
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun overlayCardIndicesStayStableWhenRankedSuggestionsReorder() {
        val firstRanking = overlayCards(listOf(card("Top pick", originalIndex = 1, value = 67.0), card("Other", originalIndex = 0, value = 33.0)))
        val updatedRanking = overlayCards(listOf(card("Other", originalIndex = 0, value = 70.0), card("Top pick", originalIndex = 1, value = 42.0)))

        assertEquals(listOf("Other", "Top pick"), firstRanking.map { it.name })
        assertEquals(firstRanking.map { it.originalIndex }, updatedRanking.map { it.originalIndex })
        assertEquals(listOf(70.0, 42.0), updatedRanking.map { it.value })
    }

    private fun card(name: String, originalIndex: Int, value: Double) = PackCardUi(
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
    )
}
