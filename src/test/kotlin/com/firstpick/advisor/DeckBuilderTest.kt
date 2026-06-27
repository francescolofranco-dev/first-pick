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

    @Test
    fun offersThreeColorDeckOnlyWhenFixingSupportsIt() {
        // Equally deep W/U/B (so each 2-color pair is a clean, splash-free deck) where
        // the best 23 cards span all three colors — a 3-color deck has a real card-quality
        // edge, but should only be offered when the pool also has the fixing to cast it.
        val colors = listOf("W", "U", "B")
        val base = buildList {
            colors.forEachIndexed { ci, col ->
                repeat(12) { i -> add(card(ci * 100 + i, "$col$i", 0.62 - 0.01 * i, col)) }
            }
        }
        val fixingLands = (0 until 3).map { card(900 + it, "Fixer$it", 0.0, "") }
        val metaFix: (String) -> CardMeta? = { name ->
            if (name.startsWith("Fixer")) CardMeta(name, cmc = 0, isCreature = false, isLand = true, isFixing = true)
            else CardMeta(name, cmc = 3, isCreature = true, isLand = false)
        }

        val withFixing = DeckBuilder.build(base + fixingLands, metrics, metaFix)
        val withoutFixing = DeckBuilder.build(base, metrics, metaFix)

        assertTrue(
            withFixing.any { it.pair.length == 3 },
            "3-color should be offered with fixing: ${withFixing.map { it.pair to it.powerScore.toInt() }}",
        )
        assertTrue(
            withoutFixing.none { it.pair.length == 3 },
            "3-color should NOT be offered without fixing: ${withoutFixing.map { it.pair to it.powerScore.toInt() }}",
        )
    }
}
