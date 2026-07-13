package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeckProjector is the shared deck model behind both the end-of-draft builder and
 * (soon) pick-time deck fit. These tests pin the contract between its entry points;
 * the projection behavior itself is covered by [DeckBuilderTest].
 */
class DeckProjectorTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(id: Int, name: String, gih: Double, color: String) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = color, everDrawnWinRate = gih, everDrawnGameCount = 2000),
    )

    private fun pool(): List<RankedCard> = buildList {
        repeat(12) { add(card(it, "W$it", 0.58, "W")) }
        repeat(12) { add(card(100 + it, "U$it", 0.58, "U")) }
        repeat(8) { add(card(200 + it, "R$it", 0.50, "R")) }
    }

    private val meta: (String) -> CardMeta? = { name ->
        CardMeta(name, cmc = 3, isCreature = true, isLand = false)
    }

    @Test
    fun projectIsTheTopOfTheFullSlate() {
        val top = DeckProjector.project(pool(), metrics, meta)
        val slate = DeckBuilder.build(pool(), metrics, meta)
        assertEquals(slate.first(), top, "pick-time projection and the builder's top option must be the same deck")
    }

    @Test
    fun projectIsNullOnAnEmptyPool() {
        assertNull(DeckProjector.project(emptyList(), metrics, meta))
    }

    @Test
    fun strongOnColorCardMakesTheDeckAndDisplacesExactlyOneFiller() {
        val fit = DeckProjector.fit(pool(), card(999, "Bomb", 0.70, "U"), metrics, meta)
        assertTrue(fit.makesDeck, "a 70% on-color card must earn a slot")
        assertEquals(1, fit.displaced.size, "one filler leaves the 23: ${fit.displaced}")
        assertTrue(fit.displaced.first() != "Bomb")
        assertTrue(!fit.baseShifted)
        assertTrue(fit.powerDelta > 0, "the projected deck got stronger")
    }

    @Test
    fun marginalOffColorCardDoesNotMakeTheDeck() {
        val pool = buildList {
            repeat(13) { add(card(it, "W$it", 0.56, "W")) }
            repeat(13) { add(card(100 + it, "U$it", 0.56, "U")) }
        }
        val fit = DeckProjector.fit(pool, card(999, "MehBlack", 0.565, "B"), metrics, meta)
        assertTrue(!fit.makesDeck, "half a point of win rate does not dent a full two-color deck")
        assertTrue(fit.displaced.isEmpty())
        assertNull(fit.splashAdded)
    }

    @Test
    fun strongOffColorCardIntoAShortBaseBecomesTheSplash() {
        val pool = buildList {
            repeat(9) { add(card(it, "W$it", 0.56, "W")) }
            repeat(9) { add(card(100 + it, "U$it", 0.56, "U")) }
        }
        val fit = DeckProjector.fit(pool, card(999, "BlackBomb", 0.62, "B"), metrics, meta)
        assertTrue(fit.makesDeck, "a short base takes the strong off-color card as its splash")
        assertEquals('B', fit.splashAdded, "the deck opened a black splash for this card")
    }

    @Test
    fun seatsTheRemovalFloorOverHigherWinRateFluff() {
        val metaMap = mutableMapOf<String, CardMeta>()
        fun mk(id: Int, name: String, gih: Double, color: String, creature: Boolean, removal: Boolean = false): RankedCard {
            metaMap[name] = CardMeta(name, cmc = 3, isCreature = creature, isLand = false, isRemoval = removal)
            return card(id, name, gih, color)
        }
        val pool = buildList {
            repeat(16) { add(mk(it, "Cre$it", 0.56, if (it % 2 == 0) "W" else "U", creature = true)) }
            repeat(8) { add(mk(100 + it, "Fluff$it", 0.60, if (it % 2 == 0) "W" else "U", creature = false)) }
            repeat(5) { add(mk(200 + it, "Kill$it", 0.55, if (it % 2 == 0) "W" else "U", creature = false, removal = true)) }
        }
        val top = DeckProjector.projectAll(pool, metrics, { metaMap[it] }).first()
        assertTrue(top.removal >= 4, "playable removal must be seated over higher-WR fluff, got ${top.removal}")
    }

    @Test
    fun neverSeatsDudRemovalToHitTheFloor() {
        val metaMap = mutableMapOf<String, CardMeta>()
        fun mk(id: Int, name: String, gih: Double, color: String, creature: Boolean, removal: Boolean = false): RankedCard {
            metaMap[name] = CardMeta(name, cmc = 3, isCreature = creature, isLand = false, isRemoval = removal)
            return card(id, name, gih, color)
        }
        val pool = buildList {
            repeat(16) { add(mk(it, "Cre$it", 0.58, if (it % 2 == 0) "W" else "U", creature = true)) }
            repeat(8) { add(mk(100 + it, "Fluff$it", 0.58, if (it % 2 == 0) "W" else "U", creature = false)) }
            // z = (0.50 - 0.55) / 0.03 = -1.67, well under the quality gate.
            repeat(5) { add(mk(200 + it, "Dud$it", 0.50, if (it % 2 == 0) "W" else "U", creature = false, removal = true)) }
        }
        val top = DeckProjector.projectAll(pool, metrics, { metaMap[it] }).first()
        assertEquals(0, top.removal, "dud removal must not be forced in to satisfy the floor")
    }

    @Test
    fun duplicateCopiesAreCountedNotConfusedByName() {
        // Pool already holds one mediocre "Dup"; adding a second must register as making
        // the deck only if the extra COPY earns a slot, not because the name is present.
        val pool = pool() + card(998, "Dup", 0.50, "W")
        val before = DeckProjector.project(pool, metrics, meta)!!
        val copiesBefore = before.spells.count { it.name == "Dup" }
        val fit = DeckProjector.fit(pool, card(999, "Dup", 0.50, "W"), metrics, meta)
        val makesAsExtraCopy = fit.makesDeck
        // A 0.50 card in a 0.58 pool doesn't make the 23 at all — neither copy.
        assertEquals(0, copiesBefore)
        assertTrue(!makesAsExtraCopy, "a below-rate duplicate must not read as 'makes the deck'")
    }
}
