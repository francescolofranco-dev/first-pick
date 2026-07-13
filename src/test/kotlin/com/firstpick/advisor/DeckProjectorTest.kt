package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
