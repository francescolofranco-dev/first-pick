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

@Serializable
private data class CardDataResponse(
    @SerialName("data") val data: List<CardRating> = emptyList(),
)

@Serializable
data class ColorRatingRow(
    @SerialName("color_name") val colorName: String = "",
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("wins") val wins: Int = 0,
    @SerialName("games") val games: Int = 0,
) {
    val winRate: Double? get() = if (games > 0) wins.toDouble() / games else null
}

enum class RatingsDataSource {
    NETWORK,
    FRESH_CACHE,
    STALE_CACHE,
}

data class RatingsFetchMetadata(
    val source: RatingsDataSource,
    val lastUpdated: Instant,
    /** Why a network refresh failed when [source] is [RatingsDataSource.STALE_CACHE]. */
    val fallbackReason: FetchFailure? = null,
)

data class RatingsFetchResult(
    val ratings: List<CardRating>,
    val metadata: RatingsFetchMetadata,
)

class SeventeenLandsClient(
    private val cacheDir: Path = AppPaths.cacheDir,
    private val staleness: Duration = Duration.ofHours(12),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(set: String, format: String, colors: String? = null): List<CardRating> =
        fetchWithMetadata(set, format, colors).ratings

    suspend fun fetchWithMetadata(
        set: String,
        format: String,
        colors: String? = null,
        forceRefresh: Boolean = false,
    ): RatingsFetchResult =
        withContext(Dispatchers.IO) {
            val suffix = colors?.let { "_$it" }.orEmpty()
            val cached = cachedBody(
                cacheName = "ratings3_${set.uppercase()}_${format}$suffix.json",
                forceRefresh = forceRefresh,
            ) {
                ratingsUrl(set, format, colors)
            }
            val ratings = runCatching { json.decodeFromString<CardDataResponse>(cached.body).data }.getOrElse {
                Log.error(TAG, "parse ratings ${set.uppercase()}/$format failed", it)
                throw DataUnavailableException(FetchFailure.BAD_DATA, it)
            }
            RatingsFetchResult(
                ratings = ratings,
                metadata = RatingsFetchMetadata(
                    source = cached.source,
                    lastUpdated = cached.lastUpdated,
                    fallbackReason = cached.fallbackReason,
                ),
            )
        }

    suspend fun colorRatings(set: String, format: String): List<ColorRatingRow> =
        withContext(Dispatchers.IO) {
            val body = runCatching {
                cachedBody("colorratings_${set.uppercase()}_${format}.json") { colorRatingsUrl(set, format) }.body
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

    private data class CachedBody(
        val body: String,
        val source: RatingsDataSource,
        val lastUpdated: Instant,
        val fallbackReason: FetchFailure? = null,
    )

    private fun cachedBody(
        cacheName: String,
        forceRefresh: Boolean = false,
        url: () -> String,
    ): CachedBody {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve(cacheName)
        if (!forceRefresh && isFresh(cache)) {
            return CachedBody(
                body = Files.readString(cache),
                source = RatingsDataSource.FRESH_CACHE,
                lastUpdated = cacheLastUpdated(cache),
            )
        }
        return when (val r = httpGet(url())) {
            is HttpResult.Ok -> {
                Files.writeString(cache, r.body)
                CachedBody(
                    body = r.body,
                    source = RatingsDataSource.NETWORK,
                    lastUpdated = cacheLastUpdated(cache),
                )
            }
            is HttpResult.Failed ->
                if (Files.exists(cache)) {
                    Log.warn(TAG, "$cacheName: ${r.reason} — serving stale cache")
                    CachedBody(
                        body = Files.readString(cache),
                        source = RatingsDataSource.STALE_CACHE,
                        lastUpdated = cacheLastUpdated(cache),
                        fallbackReason = r.reason,
                    )
                } else {
                    Log.error(TAG, "$cacheName: ${r.reason} — no cache to fall back on")
                    throw DataUnavailableException(r.reason)
                }
        }
    }

    private fun cacheLastUpdated(cache: Path): Instant =
        Files.getLastModifiedTime(cache).toInstant()

    private fun isFresh(cache: Path): Boolean {
        if (!Files.exists(cache)) return false
        val age = Duration.between(Files.getLastModifiedTime(cache).toInstant(), Instant.now())
        return age < staleness
    }

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

        internal fun failureForStatus(code: Int): FetchFailure? = when {
            code == 200 -> null
            code == 404 -> FetchFailure.NOT_FOUND
            code == 429 -> FetchFailure.RATE_LIMITED
            code in 500..599 -> FetchFailure.SERVER_ERROR
            else -> FetchFailure.SERVER_ERROR
        }

        internal fun isTransient(reason: FetchFailure): Boolean = when (reason) {
            FetchFailure.RATE_LIMITED, FetchFailure.SERVER_ERROR, FetchFailure.OFFLINE -> true
            FetchFailure.NOT_FOUND, FetchFailure.BAD_DATA -> false
        }

        const val START_DATE = "2019-01-01"

        fun ratingsUrl(
            set: String,
            format: String,
            colors: String? = null,
        ): String =
            "https://www.17lands.com/api/card_data" +
                "?expansion=${set.uppercase()}&event_type=$format&time_period=ALL_TIME" +
                (colors?.let { "&colors=$it" } ?: "")

        fun colorRatingsUrl(set: String, format: String, today: LocalDate = LocalDate.now()): String =
            "https://www.17lands.com/color_ratings/data" +
                "?expansion=${set.uppercase()}&event_type=$format" +
                "&start_date=$START_DATE&end_date=$today&combine_splash=false"
    }
}
