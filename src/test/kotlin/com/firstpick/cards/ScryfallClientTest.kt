package com.firstpick.cards

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the role-classification heuristics that drive the advisor's deck-needs logic
 * (removal/fixing/finisher/evasion/draw). Pure — no network.
 */
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
        // Curated otag:removal overrides text that wouldn't match the regex.
        assertTrue(roles("Enchantment", "Some unusual removal wording.", removalTagged = true).removal)
    }

    @Test
    fun damageToAPlayerIsNotRemoval() {
        // Regression: "Lonely Arroyo" — a land that pings a PLAYER — was tagged removal
        // because the regex matched a bare "deals N damage to". Player/face damage,
        // edicts that hit players, etc. are not creature removal.
        assertFalse(roles("Land", "{T}, Sacrifice Lonely Arroyo: It deals 1 damage to target player.").removal)
        assertFalse(roles("Sorcery", "Deals 2 damage to each opponent.").removal)
        assertFalse(roles("Instant", "Target player loses 3 life.").removal)
        // Graveyard hate / land destruction aren't creature removal either.
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
        assertTrue(roles("Creature — Beast", "Flying", cmc = 5, power = 4).finisher) // 4-power evasive top-end
        assertFalse(roles("Creature — Bear", "", cmc = 2, power = 2).finisher)
        assertFalse(roles("Instant", "", cmc = 6, power = 0).finisher) // non-creature isn't a finisher
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
        // The curated otag wins even when the oracle text doesn't spell the role out.
        assertTrue(roles("Creature — Eldrazi", "It is just enormous.", evasionTagged = true).evasion)
        assertTrue(roles("Sorcery", "Investigate twice.", drawTagged = true).draw)
        // Heuristic fallback still applies when there's no tag.
        assertFalse(roles("Creature — Bear", "Vigilance.", evasionTagged = false).evasion)
        assertTrue(roles("Sorcery", "Draw a card.", drawTagged = false).draw)
    }
}
