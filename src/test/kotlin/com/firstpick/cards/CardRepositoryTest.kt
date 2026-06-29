package com.firstpick.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CardRepositoryTest {

    private fun rating(id: Int, name: String, gih: Double?, games: Int = 1000) =
        CardRating(name = name, mtgaId = id, everDrawnWinRate = gih, everDrawnGameCount = games)

    @Test
    fun ranksByGihWinRateBestFirst() {
        val repo = CardRepository()
        repo.index(
            listOf(
                rating(1, "Alpha", 0.60),
                rating(2, "Beta", 0.55),
                rating(3, "Gamma", null),
            ),
        )
        val ranked = repo.rankPack(listOf(3, 2, 1))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), ranked.map { it.displayName })
    }

    @Test
    fun lowSampleSinksBelowReliableCards() {
        val repo = CardRepository()
        repo.index(
            listOf(
                rating(1, "Solid", 0.56, games = 5000),
                rating(2, "Mirage", 0.99, games = 10),
            ),
        )
        val ranked = repo.rankPack(listOf(2, 1))
        assertEquals(listOf("Solid", "Mirage"), ranked.map { it.displayName })
    }

    @Test
    fun resolvesUnmatchedGrpIdByNameFallback() {
        val alpha = rating(1, "Alpha", 0.60)
        val repo = CardRepository(nameResolver = { if (it == 999) "Alpha" else null })
        repo.index(listOf(alpha))
        val resolved = repo.resolve(999)
        assertEquals("Alpha", resolved.displayName)
        assertSame(alpha, resolved.rating)
    }

    @Test
    fun unknownGrpIdHasNoRating() {
        val repo = CardRepository()
        repo.index(listOf(rating(1, "Alpha", 0.60)))
        val resolved = repo.resolve(42)
        assertNull(resolved.rating)
        assertEquals("Unknown #42", resolved.displayName)
    }
}
