package com.firstpick.signals

import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalsEngineTest {

    private fun rc(id: Int, color: String, gih: Double?, ata: Double?) = RankedCard(
        grpId = id,
        name = "c$id",
        rating = CardRating(name = "c$id", color = color, everDrawnWinRate = gih, everDrawnGameCount = 2000, avgPick = ata),
    )

    @Test
    fun lateHighQualityCardMarksColorOpen() {
        // Red card (ATA 2.0) still in the pack at pick 7 -> red is flowing.
        // Blue card seen at pick 1 (earlier than its ATA) -> not a signal.
        val seen = mapOf(1 to 7 to listOf(1), 1 to 1 to listOf(2))
        val resolve: (Int) -> RankedCard = { id ->
            when (id) {
                1 -> rc(1, "R", 0.58, 2.0)
                2 -> rc(2, "U", 0.58, 2.0)
                else -> RankedCard(id, "", null)
            }
        }
        val open = SignalsEngine.openLanes(seen, resolve)
        assertTrue(open.getOrDefault('R', 0.0) > 0.0, "Red should read as open")
        assertEquals(0.0, open.getOrDefault('U', 0.0), 1e-9, "Blue seen early is not open")
    }

    @Test
    fun pack2IsIgnored() {
        val seen = mapOf(2 to 8 to listOf(1))
        val resolve: (Int) -> RankedCard = { rc(1, "G", 0.62, 1.0) }
        assertTrue(SignalsEngine.openLanes(seen, resolve).isEmpty())
    }

    @Test
    fun belowBaselineQualityContributesNothing() {
        val seen = mapOf(1 to 9 to listOf(1))
        val resolve: (Int) -> RankedCard = { rc(1, "B", 0.46, 1.0) } // below 0.50 baseline
        assertTrue(SignalsEngine.openLanes(seen, resolve).isEmpty())
    }
}
