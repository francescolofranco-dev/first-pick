package com.firstpick.cards

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
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
     * Card ratings for a set/format (ESSENTIAL data). [colors] (e.g. "WU") narrows the
     * stats to a specific archetype; null gives the global "All Decks" data. Throws
     * [DataUnavailableException] if neither a fresh fetch nor a stale cache is available.
     */
    suspend fun fetch(set: String, format: String, colors: String? = null): List<CardRating> =
        withContext(Dispatchers.IO) {
            val suffix = colors?.let { "_$it" }.orEmpty()
            val body = cachedBody("ratings2_${set.uppercase()}_${format}$suffix.json") {
                ratingsUrl(set, format, colors)
            }
            runCatching { json.decodeFromString<List<CardRating>>(body) }.getOrElse {
                Log.error(TAG, "parse ratings ${set.uppercase()}/$format failed", it)
                throw DataUnavailableException(FetchFailure.BAD_DATA, it)
            }
        }

    /** Color-pair (archetype) win rates for a set (OPTIONAL — degrades to empty on failure). */
    suspend fun colorRatings(set: String, format: String): List<ColorRatingRow> =
        withContext(Dispatchers.IO) {
            val body = runCatching {
                cachedBody("colorratings_${set.uppercase()}_${format}.json") { colorRatingsUrl(set, format) }
            }.getOrElse {
                Log.warn(TAG, "color ratings ${set.uppercase()}/$format unavailable", it)
                return@withContext emptyList()
            }
            runCatching { json.decodeFromString<List<ColorRatingRow>>(body) }.getOrDefault(emptyList())
        }

    private sealed interface HttpResult {
        data class Ok(val body: String) : HttpResult
        data class Failed(val reason: FetchFailure) : HttpResult
    }

    /** Fresh cache → download (with retry) → stale cache → throw. */
    private fun cachedBody(cacheName: String, url: () -> String): String {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve(cacheName)
        if (isFresh(cache)) return Files.readString(cache)
        return when (val r = httpGet(url())) {
            is HttpResult.Ok -> { Files.writeString(cache, r.body); r.body }
            is HttpResult.Failed ->
                if (Files.exists(cache)) {
                    Log.warn(TAG, "$cacheName: ${r.reason} — serving stale cache")
                    Files.readString(cache)
                } else {
                    Log.error(TAG, "$cacheName: ${r.reason} — no cache to fall back on")
                    throw DataUnavailableException(r.reason)
                }
        }
    }

    private fun isFresh(cache: Path): Boolean {
        if (!Files.exists(cache)) return false
        val age = Duration.between(Files.getLastModifiedTime(cache).toInstant(), Instant.now())
        return age < staleness
    }

    /** GET with exponential backoff on transient failures (429 / 5xx / offline). */
    private fun httpGet(url: String): HttpResult {
        var last: HttpResult.Failed = HttpResult.Failed(FetchFailure.OFFLINE)
        for (attempt in 1..MAX_ATTEMPTS) {
            when (val once = httpGetOnce(url)) {
                is HttpResult.Ok -> return once
                is HttpResult.Failed -> {
                    last = once
                    if (!isTransient(once.reason) || attempt == MAX_ATTEMPTS) return once
                    val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1))
                    Log.warn(TAG, "GET $url ${once.reason} — retry $attempt/$MAX_ATTEMPTS in ${backoff}ms")
                    runCatching { Thread.sleep(backoff) }
                }
            }
        }
        return last
    }

    private fun httpGetOnce(url: String): HttpResult {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return runCatching {
            val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (val failure = failureForStatus(resp.statusCode())) {
                null -> HttpResult.Ok(resp.body())
                else -> HttpResult.Failed(failure)
            }
        }.getOrElse { HttpResult.Failed(FetchFailure.OFFLINE) }
    }

    companion object {
        private const val TAG = "17Lands"
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 500L

        const val USER_AGENT =
            "FirstPick/0.1 (personal use; +https://github.com/francescolofranco-dev)"

        /** Map an HTTP status to a failure reason, or null if it's a success. */
        internal fun failureForStatus(code: Int): FetchFailure? = when {
            code == 200 -> null
            code == 404 -> FetchFailure.NOT_FOUND
            code == 429 -> FetchFailure.RATE_LIMITED
            code in 500..599 -> FetchFailure.SERVER_ERROR
            else -> FetchFailure.SERVER_ERROR
        }

        /** Whether a failure is worth retrying (vs. a definitive 404). */
        internal fun isTransient(reason: FetchFailure): Boolean = when (reason) {
            FetchFailure.RATE_LIMITED, FetchFailure.SERVER_ERROR, FetchFailure.OFFLINE -> true
            FetchFailure.NOT_FOUND, FetchFailure.BAD_DATA -> false
        }

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
