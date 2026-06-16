package com.firstpick.cards

import com.firstpick.core.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One color-pair (archetype) row from the 17Lands color_ratings endpoint. */
@Serializable
data class ColorRatingRow(
    @SerialName("color_name") val colorName: String = "",
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("wins") val wins: Int = 0,
    @SerialName("games") val games: Int = 0,
) {
    val winRate: Double? get() = if (games > 0) wins.toDouble() / games else null
}

/**
 * Fetches 17Lands data, caching each response on disk and skipping the network
 * while the cache is fresh. Uses the JDK HTTP client (no extra dependency) and a
 * descriptive User-Agent per 17Lands' usage guidelines. Data is CC-BY-4.0.
 */
class SeventeenLandsClient(
    private val cacheDir: Path = AppPaths.cacheDir,
    private val staleness: Duration = Duration.ofHours(12),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Card ratings for a set/format. [colors] (e.g. "WU") narrows the stats to a
     * specific archetype; null gives the global "All Decks" data.
     */
    suspend fun fetch(set: String, format: String, colors: String? = null): List<CardRating> =
        withContext(Dispatchers.IO) {
            val suffix = colors?.let { "_$it" }.orEmpty()
            val body = cachedBody("ratings2_${set.uppercase()}_${format}$suffix.json") {
                ratingsUrl(set, format, colors)
            } ?: return@withContext emptyList()
            runCatching { json.decodeFromString<List<CardRating>>(body) }.getOrDefault(emptyList())
        }

    /** Color-pair (archetype) win rates for a set. */
    suspend fun colorRatings(set: String, format: String): List<ColorRatingRow> =
        withContext(Dispatchers.IO) {
            val body = cachedBody("colorratings_${set.uppercase()}_${format}.json") {
                colorRatingsUrl(set, format)
            } ?: return@withContext emptyList()
            runCatching { json.decodeFromString<List<ColorRatingRow>>(body) }.getOrDefault(emptyList())
        }

    /** Read a fresh cache, else download + cache, else serve stale, else null. */
    private fun cachedBody(cacheName: String, url: () -> String): String? {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve(cacheName)
        if (isFresh(cache)) return Files.readString(cache)
        val fetched = httpGet(url())
        return when {
            fetched != null -> { Files.writeString(cache, fetched); fetched }
            Files.exists(cache) -> Files.readString(cache) // serve stale on failure
            else -> null
        }
    }

    private fun isFresh(cache: Path): Boolean {
        if (!Files.exists(cache)) return false
        val age = Duration.between(Files.getLastModifiedTime(cache).toInstant(), Instant.now())
        return age < staleness
    }

    private fun httpGet(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return runCatching {
            val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 200) resp.body() else null
        }.getOrNull()
    }

    companion object {
        const val USER_AGENT =
            "FirstPick/0.1 (personal use; +https://github.com/francescolofranco-dev)"

        /** Earliest plausible Arena draft-data date; gives all-time aggregates per set. */
        const val START_DATE = "2019-01-01"

        /**
         * Card-ratings URL. A wide date window is REQUIRED: without it the API
         * returns the card list with null win rates for any non-current set.
         */
        fun ratingsUrl(
            set: String,
            format: String,
            colors: String? = null,
            today: LocalDate = LocalDate.now(),
        ): String =
            "https://www.17lands.com/card_ratings/data" +
                "?expansion=${set.uppercase()}&format=$format" +
                "&start_date=$START_DATE&end_date=$today" +
                (colors?.let { "&colors=$it" } ?: "")

        fun colorRatingsUrl(set: String, format: String, today: LocalDate = LocalDate.now()): String =
            "https://www.17lands.com/color_ratings/data" +
                "?expansion=${set.uppercase()}&event_type=$format" +
                "&start_date=$START_DATE&end_date=$today&combine_splash=false"
    }
}
