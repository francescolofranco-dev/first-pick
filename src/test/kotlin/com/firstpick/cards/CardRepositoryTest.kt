package com.firstpick.cards

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        val repo = CardRepository(basicLandResolver = BasicLandResolver { null })
        repo.index(listOf(rating(1, "Alpha", 0.60)))
        val resolved = repo.resolve(42)
        assertNull(resolved.rating)
        assertEquals("Unknown #42", resolved.displayName)
    }

    @Test
    fun resolvesUnratedBasicLandIdentityWithoutInventingAStatisticalRating() {
        val image = "https://api.scryfall.com/cards/arena/92374?format=image&version=normal"
        val repo = CardRepository(
            basicLandResolver = BasicLandResolver { id ->
                if (id == 92374) BasicLandIdentity("Island", image) else null
            },
        )
        repo.index(listOf(rating(1, "Alpha", 0.60)))

        val resolved = repo.resolve(92374)

        assertEquals("Island", resolved.displayName)
        assertTrue(resolved.isBasicLand)
        assertNull(resolved.rating)
        assertNull(resolved.gihWr)
        assertEquals(image, resolved.imageUrl)
    }

    @Test
    fun loadExposesDatasetAndSampleMetadata() = runBlocking {
        val cache = createTempDirectory("fp-repo-metadata")
        Files.writeString(
            cache.resolve("ratings3_SOS_PremierDraft.json"),
            """{"data":[
                {"name":"A","mtga_id":1,"ever_drawn_win_rate":0.55,"ever_drawn_game_count":100},
                {"name":"B","mtga_id":2,"ever_drawn_win_rate":0.56,"ever_drawn_game_count":300},
                {"name":"C","mtga_id":3,"ever_drawn_win_rate":0.57,"ever_drawn_game_count":500}
            ]}""".trimIndent(),
        )
        val repo = CardRepository(SeventeenLandsClient(cacheDir = cache))

        val info = repo.load("SOS", "PremierDraft")

        assertTrue(repo.isLoadedFor("sos", "PremierDraft"))
        assertEquals(RatingsDataSource.FRESH_CACHE, info.source)
        assertEquals(3, info.cardCount)
        assertEquals(2, info.reliableCardCount)
        assertEquals(300, info.medianGamesPerCard)
        assertSame(info, repo.ratingsInfo)
    }
}
