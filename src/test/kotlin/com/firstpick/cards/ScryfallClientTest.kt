package com.firstpick.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScryfallClientTest {

    private fun roles(
        type: String,
        text: String = "",
        cmc: Int = 3,
        power: Int = 0,
        produced: List<String> = emptyList(),
        removalTagged: Boolean = false,
        evasionTagged: Boolean = false,
        drawTagged: Boolean = false,
    ) = ScryfallClient.classifyRoles(type, text, cmc, power, produced, removalTagged, evasionTagged, drawTagged)

    @Test
    fun creatureAndLandComeFromTheTypeLine() {
        assertTrue(roles("Creature — Goblin").creature)
        assertFalse(roles("Instant").creature)
        assertTrue(roles("Basic Land — Mountain").land)
        assertFalse(roles("Artifact").land)
    }

    @Test
    fun removalFromOracleTextOrCuratedTag() {
        assertTrue(roles("Instant", "Destroy target creature.").removal)
        assertTrue(roles("Sorcery", "Exile target creature or planeswalker.").removal)
        assertTrue(roles("Instant", "Lightning Bolt deals 3 damage to any target.").removal)
        assertTrue(roles("Instant", "Deals 2 damage to target creature.").removal)
        assertFalse(roles("Instant", "Draw two cards.").removal)
        assertTrue(roles("Enchantment", "Some unusual removal wording.", removalTagged = true).removal)
    }

    @Test
    fun damageToAPlayerIsNotRemoval() {
        assertFalse(roles("Land", "{T}, Sacrifice Lonely Arroyo: It deals 1 damage to target player.").removal)
        assertFalse(roles("Sorcery", "Deals 2 damage to each opponent.").removal)
        assertFalse(roles("Instant", "Target player loses 3 life.").removal)
        assertFalse(roles("Instant", "Exile target card from a graveyard.").removal)
        assertFalse(roles("Sorcery", "Destroy target land.").removal)
    }

    @Test
    fun fixingFromDualManaOrAnyColor() {
        assertTrue(roles("Land", "{T}: Add W or U.", cmc = 0, produced = listOf("W", "U")).fixing)
        assertTrue(roles("Artifact", "{T}: Add one mana of any color.", cmc = 2).fixing)
        assertTrue(roles("Artifact", "When this dies, create a Treasure token.", cmc = 2).fixing)
        assertFalse(roles("Land", "{T}: Add G.", cmc = 0, produced = listOf("G")).fixing)
    }

    @Test
    fun finisherNeedsRealPower() {
        assertTrue(roles("Creature — Dragon", "Flying", cmc = 6, power = 5).finisher)
        assertTrue(roles("Creature — Beast", "Flying", cmc = 5, power = 4).finisher)
        assertFalse(roles("Creature — Bear", "", cmc = 2, power = 2).finisher)
        assertFalse(roles("Instant", "", cmc = 6, power = 0).finisher)
    }

    @Test
    fun evasionAndDrawDetected() {
        assertTrue(roles("Creature — Bird", "Flying").evasion)
        assertFalse(roles("Creature — Bear", "Vigilance").evasion)
        assertTrue(roles("Sorcery", "Draw a card.").draw)
        assertFalse(roles("Sorcery", "Gain 3 life.").draw)
    }

    @Test
    fun curatedTagsDriveEvasionAndDraw() {
        assertTrue(roles("Creature — Eldrazi", "It is just enormous.", evasionTagged = true).evasion)
        assertTrue(roles("Sorcery", "Investigate twice.", drawTagged = true).draw)
        assertFalse(roles("Creature — Bear", "Vigilance.", evasionTagged = false).evasion)
        assertTrue(roles("Sorcery", "Draw a card.", drawTagged = false).draw)
    }

    @Test
    fun hybridPipsAreExtractedAndCanonicallyOrdered() {
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{4}{U/W}"))
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{4}{W/U}"))
        assertEquals(listOf("WU", "BR"), ScryfallClient.hybridGroupsOf("{W/U}{1}{B/R}"))
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{U/W}{U/W}"))
    }

    @Test
    fun nonColorHybridAndPhyrexianPipsAreNotHybridGroups() {
        assertTrue(ScryfallClient.hybridGroupsOf("{2/W}").isEmpty())
        assertTrue(ScryfallClient.hybridGroupsOf("{U/P}").isEmpty())
        assertTrue(ScryfallClient.hybridGroupsOf("{3}{U}").isEmpty())
    }
}
