package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertTrue

class DeckBuilderTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(id: Int, name: String, gih: Double, color: String) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = color, everDrawnWinRate = gih, everDrawnGameCount = 2000),
    )

    /** A pool: 24 strong UW cards plus 8 weak red cards. */
    private fun pool(): List<RankedCard> = buildList {
        repeat(12) { add(card(it, "W$it", 0.58, "W")) }
        repeat(12) { add(card(100 + it, "U$it", 0.58, "U")) }
        repeat(8) { add(card(200 + it, "R$it", 0.50, "R")) }
    }

    private val meta: (String) -> CardMeta? = { name ->
        CardMeta(name, cmc = 3, isCreature = true, isLand = false)
    }

    @Test
    fun buildsTheStrongestPairFirst() {
        val options = DeckBuilder.build(pool(), metrics, meta)
        assertTrue(options.isNotEmpty())
        // WU is the deep, strong pair; it should be the top option.
        assertTrue(options.first().pair == "WU")
        assertTrue(options.first().powerScore > options.last().powerScore)
    }

    @Test
    fun deckHasAFullSpellCountAndLands() {
        val top = DeckBuilder.build(pool(), metrics, meta).first()
        assertTrue(top.spells.size in 18..23)
        assertTrue(top.basics.values.sum() in 1..17)
        // Lands split across the two pair colors.
        assertTrue(top.basics.keys.all { it in setOf('W', 'U') })
    }

    @Test
    fun skipsPairsWithoutEnoughPlayables() {
        // Only red cards (8) — no pair reaches the 18-playable minimum.
        val redOnly = (0 until 8).map { card(it, "R$it", 0.5, "R") }
        assertTrue(DeckBuilder.build(redOnly, metrics, meta).isEmpty())
    }
}
