package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckNeedsTest {

    private fun meta(
        name: String,
        cmc: Int = 3,
        creature: Boolean = false,
        land: Boolean = false,
        removal: Boolean = false,
        fixing: Boolean = false,
        finisher: Boolean = false,
    ) = CardMeta(name, cmc, isCreature = creature, isLand = land, isRemoval = removal, isFixing = fixing, isFinisher = finisher)

    @Test
    fun analyzeCountsRoles() {
        val metas = listOf(
            meta("c1", cmc = 2, creature = true),
            meta("r1", removal = true),
            meta("dual", land = true, fixing = true),
            meta("fin", cmc = 6, creature = true, finisher = true),
        )
        val n = PoolNeeds.analyze(metas, poolSize = 4)
        assertEquals(2, n.creatures)
        assertEquals(1, n.twoDrops)
        assertEquals(1, n.removal)
        assertEquals(1, n.fixing)
        assertEquals(1, n.finishers)
        assertEquals(1, n.topEnd)
    }

    @Test
    fun removalBoostedWhenShortDampenedWhenSaturated() {
        val short = PoolNeeds(creatures = 10, twoDrops = 5, removal = 0, fixing = 3, finishers = 2, topEnd = 1, poolSize = 20)
        val saturated = short.copy(removal = 8)
        val a = DeckNeeds.evaluateCard(meta("kill", removal = true), short, totalPicks = 45)
        val b = DeckNeeds.evaluateCard(meta("kill", removal = true), saturated, totalPicks = 45)
        assertTrue(a.points > 0.0 && "Needs removal" in a.reasons)
        assertTrue(b.points < 0.0)
    }

    @Test
    fun fixingNeededOnlyOnceThePoolIsBigEnough() {
        val dual = meta("dual", land = true, fixing = true)
        val early = PoolNeeds(creatures = 2, twoDrops = 1, removal = 0, fixing = 0, finishers = 0, topEnd = 0, poolSize = 5)
        val later = early.copy(poolSize = 20)
        assertEquals(0.0, DeckNeeds.evaluateCard(dual, early, 45).points, 1e-9)
        assertTrue(DeckNeeds.evaluateCard(dual, later, 45).points > 0.0)
    }

    @Test
    fun activeNeedsLabelsReflectGaps() {
        val needs = PoolNeeds(creatures = 0, twoDrops = 0, removal = 0, fixing = 0, finishers = 0, topEnd = 0, poolSize = 16)
        val labels = needs.activeNeeds(45)
        assertTrue("Removal" in labels)
        assertTrue("2-drops" in labels)
        assertTrue("Fixing" in labels)
        assertTrue("Finisher" in labels)
    }
}
