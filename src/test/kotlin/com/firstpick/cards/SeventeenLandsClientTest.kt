package com.firstpick.cards

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeventeenLandsClientTest {

    @Test
    fun ratingsUrlHitsTheLiveCardDataApi() {
        val url = SeventeenLandsClient.ratingsUrl("mkm", "PremierDraft")
        assertTrue("/api/card_data" in url, url)
        assertTrue("expansion=MKM" in url, url)
        assertTrue("event_type=PremierDraft" in url, url)
        assertTrue("time_period=ALL_TIME" in url, url)

        val wu = SeventeenLandsClient.ratingsUrl("mkm", "PremierDraft", colors = "WU")
        assertTrue("colors=WU" in wu, wu)
    }

    @Test
    fun httpStatusMapsToFailureReason() {
        assertNull(SeventeenLandsClient.failureForStatus(200))
        assertEquals(FetchFailure.NOT_FOUND, SeventeenLandsClient.failureForStatus(404))
        assertEquals(FetchFailure.RATE_LIMITED, SeventeenLandsClient.failureForStatus(429))
        assertEquals(FetchFailure.SERVER_ERROR, SeventeenLandsClient.failureForStatus(503))
        assertEquals(FetchFailure.SERVER_ERROR, SeventeenLandsClient.failureForStatus(418))
    }

    @Test
    fun onlyTransientFailuresRetry() {
        assertTrue(SeventeenLandsClient.isTransient(FetchFailure.RATE_LIMITED))
        assertTrue(SeventeenLandsClient.isTransient(FetchFailure.SERVER_ERROR))
        assertTrue(SeventeenLandsClient.isTransient(FetchFailure.OFFLINE))
        assertFalse(SeventeenLandsClient.isTransient(FetchFailure.NOT_FOUND))
        assertFalse(SeventeenLandsClient.isTransient(FetchFailure.BAD_DATA))
    }

    @Test
    fun fetchParsesAFreshCacheWithoutNetwork() = runBlocking {
        val cache = createTempDirectory("fp-17l")
        Files.writeString(
            cache.resolve("ratings3_SOS_PremierDraft.json"),
            """
            {"copyright":"(c) 17Lands","notes":"","data":[
              {"name":"Bolt","mtga_id":42,"color":"R","rarity":"common",
               "ever_drawn_win_rate":0.585,"ever_drawn_game_count":4200,
               "drawn_improvement_win_rate":0.061,"avg_seen":2.3,"avg_pick":1.7},
              {"name":"Filler","mtga_id":7,"color":"U","rarity":"common",
               "ever_drawn_win_rate":0.49,"ever_drawn_game_count":900}
            ]}
            """.trimIndent(),
        )
        val client = SeventeenLandsClient(cacheDir = cache)
        val ratings = client.fetch("SOS", "PremierDraft")

        assertEquals(2, ratings.size)
        val bolt = ratings.first { it.name == "Bolt" }
        assertEquals(42, bolt.mtgaId)
        assertEquals(0.585, bolt.gihWr)
        assertEquals(0.061, bolt.iwd)
        assertEquals(2.3, bolt.alsa)
        assertTrue(bolt.hasReliableWinRate)
    }

    @Test
    fun colorRatingsParsesPairWinRates() = runBlocking {
        val cache = createTempDirectory("fp-17l")
        Files.writeString(
            cache.resolve("colorratings_SOS_PremierDraft.json"),
            """[{"color_name":"Azorius (WU)","wins":550,"games":1000}]""",
        )
        val rows = SeventeenLandsClient(cacheDir = cache).colorRatings("SOS", "PremierDraft")
        assertEquals(1, rows.size)
        assertEquals(0.55, rows.first().winRate)
    }
}
