package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CardsClipboardTest {
    @Test
    fun exportsDetailsAndMissingValuesWithoutBreakingRows() {
        val cards = listOf(
            DeckSpellUi("Test card", 2, "U", .567, "https://example.com/card.jpg", 3,
                "Creature", listOf("Draw", "Flying"), manaCost = "{1}{U}"),
            DeckSpellUi("Unknown\ncard", 0, "", null),
        )
        assertEquals(
            "Quantity\tCard\tGIH win rate\tMana cost\tMana value\tType\tRoles\tImage URL\n" +
                "3\tTest card\t56.7%\t{1}{U}\t2\tCreature\tDraw, Flying\thttps://example.com/card.jpg\n" +
                "1\tUnknown card\tN/A\tN/A\t0\tN/A\tN/A\tN/A",
            cardsClipboardText(cards),
        )
    }
}
