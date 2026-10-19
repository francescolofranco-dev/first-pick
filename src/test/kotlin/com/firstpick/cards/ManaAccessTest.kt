package com.firstpick.cards

import kotlin.test.*

class ManaAccessTest {
    @Test fun recognizesBasicAndTypedLandcyclingWithItsOwnCost() {
        val basic = ManaAccess.landcycling("Basic landcycling {2} ({2}, Discard this card: Search your library.)")!!
        assertEquals("WUBRG".toSet(), basic.colors)
        assertEquals("{2}", basic.cost)
        assertEquals(setOf('U'), ManaAccess.landcycling("Islandcycling {1}{G}")!!.colors)
        assertNull(ManaAccess.landcycling("Other cards have basic landcycling {2}."))
        assertNull(ManaAccess.landcycling("Cycling {2}"))
    }

    @Test fun drawLootAndFilteringCountSeenCardsOnce() {
        assertEquals(2, ManaAccess.cardsSeen("Draw two cards, then discard a card."))
        assertEquals(2, ManaAccess.cardsSeen("Discard a card, then draw two cards."))
        assertEquals(2, ManaAccess.cardsSeen("Scry 2, then draw a card."))
        assertEquals(1, ManaAccess.cardsSeen("When this creature enters, draw a card."))
        assertEquals(0, ManaAccess.cardsSeen("Whenever this creature deals combat damage, draw two cards."))
        assertEquals(0, ManaAccess.cardsSeen("If you control an artifact, draw two cards."))
        assertEquals(0, ManaAccess.cardsSeen("{2}: Draw a card."))
    }

    @Test fun conditionalFixingDoesNotPromiseGuaranteedMana() {
        assertTrue(ManaAccess.conditionalFixing("When this creature dies, create a Treasure token."))
        assertTrue(ManaAccess.conditionalFixing("Whenever this creature attacks, create a Treasure token."))
        assertFalse(ManaAccess.conditionalFixing("When this creature enters, create a Treasure token."))
        assertFalse(ManaAccess.conditionalFixing("{T}: Add one mana of any color."))
    }

    @Test fun abilityCostsMustBeKnownAndBasePayable() {
        assertEquals(2, ManaAccess.costValue("{2}"))
        assertTrue(ManaAccess.payable("{1}{G}", setOf('G', 'U')))
        assertFalse(ManaAccess.payable("{B}", setOf('W', 'U')))
        assertNull(ManaAccess.costValue("{X}"))
    }
}
