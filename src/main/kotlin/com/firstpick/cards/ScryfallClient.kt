package com.firstpick.cards

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Fetches a set's cards from Scryfall to recover mana values, type lines, and the
 * functional roles (removal/fixing/finisher/evasion/draw) the advisor's deck-needs
 * logic depends on. Removal uses Scryfall's curated `otag:removal` (accurate); the
 * rest are derived from oracle text / produced mana / power. Cached per set for a
 * week. Polite: descriptive User-Agent and a short delay between pages.
 */
class ScryfallClient(
    private val cacheDir: Path = AppPaths.cacheDir,
    private val staleness: Duration = Duration.ofDays(7),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class SearchPage(
        @SerialName("has_more") val hasMore: Boolean = false,
        @SerialName("next_page") val nextPage: String? = null,
        @SerialName("data") val data: List<Card> = emptyList(),
    )

    @Serializable
    private data class Card(
        @SerialName("name") val name: String = "",
        @SerialName("cmc") val cmc: Double = 0.0,
        @SerialName("type_line") val typeLine: String = "",
        @SerialName("oracle_text") val oracleText: String = "",
        @SerialName("power") val power: String? = null,
        @SerialName("produced_mana") val producedMana: List<String> = emptyList(),
    )

    /** Cached, role-annotated card facts. */
    @Serializable
    private data class MetaDto(
        val name: String,
        val cmc: Int,
        val creature: Boolean,
        val land: Boolean,
        val removal: Boolean,
        val fixing: Boolean,
        val finisher: Boolean,
        val evasion: Boolean,
        val draw: Boolean,
    )

    /** Map of normalized card name -> [CardMeta] for the given set. */
    suspend fun setMeta(set: String): Map<String, CardMeta> = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve("scryfall2_${set.uppercase()}.json")
        val dtos: List<MetaDto> = if (isFresh(cache)) {
            runCatching { json.decodeFromString(LIST_SERIALIZER, Files.readString(cache)) }.getOrDefault(emptyList())
        } else {
            val built = build(set)
            if (built != null) {
                Files.writeString(cache, json.encodeToString(LIST_SERIALIZER, built))
                built
            } else if (Files.exists(cache)) {
                runCatching { json.decodeFromString(LIST_SERIALIZER, Files.readString(cache)) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        dtos.associate { normalize(it.name) to it.toMeta() }
    }

    private fun MetaDto.toMeta() = CardMeta(
        name = name, cmc = cmc, isCreature = creature, isLand = land,
        isRemoval = removal, isFixing = fixing, isFinisher = finisher,
        isEvasion = evasion, isCardDraw = draw,
    )

    private fun build(set: String): List<MetaDto>? {
        val cards = searchCards("set:${set.lowercase()} game:arena unique:cards") ?: return null
        // Scryfall's curated removal tag — more accurate than an oracle-text guess.
        val removalNames = searchCards("set:${set.lowercase()} otag:removal").orEmpty()
            .mapTo(mutableSetOf()) { frontName(it.name) }
        return cards.map { dtoOf(it, removalNames) }
    }

    private fun dtoOf(c: Card, removalNames: Set<String>): MetaDto {
        val cmc = c.cmc.roundToInt()
        val power = c.power?.substringBefore("+")?.toIntOrNull() ?: 0
        val r = classifyRoles(c.typeLine, c.oracleText, cmc, power, c.producedMana, frontName(c.name) in removalNames)
        return MetaDto(frontName(c.name), cmc, r.creature, r.land, r.removal, r.fixing, r.finisher, r.evasion, r.draw)
    }

    private fun searchCards(query: String): List<Card>? {
        var url: String? = "https://api.scryfall.com/cards/search?q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8)
        val out = mutableListOf<Card>()
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val page = getPage(url) ?: return out.ifEmpty { null }
            out += page.data
            url = page.nextPage?.takeIf { page.hasMore }
            pages++
            if (url != null) runCatching { Thread.sleep(PAGE_DELAY_MS) }
        }
        return out
    }

    /** GET one search page with retry/backoff + logging (Scryfall data is optional). */
    private fun getPage(url: String): SearchPage? {
        for (attempt in 1..SeventeenLandsClient.MAX_ATTEMPTS) {
            val (page, failure) = getPageOnce(url)
            if (page != null) return page
            if (failure == null || !SeventeenLandsClient.isTransient(failure) || attempt == SeventeenLandsClient.MAX_ATTEMPTS) {
                if (failure != null) Log.warn(TAG, "GET page failed ($failure) after $attempt attempt(s)")
                return null
            }
            val backoff = SeventeenLandsClient.BASE_BACKOFF_MS * (1L shl (attempt - 1))
            Log.warn(TAG, "GET page $failure — retry $attempt/${SeventeenLandsClient.MAX_ATTEMPTS} in ${backoff}ms")
            runCatching { Thread.sleep(backoff) }
        }
        return null
    }

    private fun getPageOnce(url: String): Pair<SearchPage?, FetchFailure?> = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", SeventeenLandsClient.USER_AGENT)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        // A search with zero matches returns 404; treat as an empty page, not an error.
        when (resp.statusCode()) {
            200 -> json.decodeFromString<SearchPage>(resp.body()) to null
            404 -> SearchPage() to null
            else -> null to SeventeenLandsClient.failureForStatus(resp.statusCode())
        }
    }.getOrElse { null to FetchFailure.OFFLINE }

    private fun isFresh(cache: Path): Boolean {
        if (!Files.exists(cache)) return false
        val age = Duration.between(Files.getLastModifiedTime(cache).toInstant(), Instant.now())
        return age < staleness
    }

    private fun frontName(name: String): String = name.substringBefore(" //").trim()
    private fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()

    companion object {
        private const val TAG = "Scryfall"
        private const val MAX_PAGES = 6
        private const val PAGE_DELAY_MS = 120L
        private val LIST_SERIALIZER = ListSerializer(MetaDto.serializer())
        private val EVASION_RE = Regex("flying|menace|can't be blocked|skulk|shadow|intimidate|horsemanship")
        private val REMOVAL_RE = Regex(
            "destroy target|exile target|deals \\d+ damage to|fights?|gets -\\d|each (opponent|player) sacrifices",
        )

        /**
         * Derive the advisor's functional roles from a card's facts. Extracted (and
         * `internal`) so the heuristics that drive deck-needs can be unit-tested
         * without the network. [removalTagged] = Scryfall's curated `otag:removal`.
         */
        internal fun classifyRoles(
            typeLine: String,
            oracleText: String,
            cmc: Int,
            power: Int,
            producedMana: List<String>,
            removalTagged: Boolean,
        ): CardRoleFlags {
            val type = typeLine.lowercase()
            val text = oracleText.lowercase()
            val isLand = "land" in type
            val isCreature = "creature" in type
            val producedColors = producedMana.filter { it.length == 1 && it[0] in "WUBRG" }.toSet()
            val evasion = isCreature && EVASION_RE.containsMatchIn(text)
            val fixing = (producedColors.size >= 2) ||
                ("any color" in text) ||
                ("treasure" in text && "create" in text)
            val finisher = isCreature && (power >= 5 || (power >= 4 && (cmc >= 5 || evasion)))
            val removal = removalTagged || REMOVAL_RE.containsMatchIn(text)
            val draw = "draw a card" in text || "draw two cards" in text || "draws a card" in text
            return CardRoleFlags(isCreature, isLand, removal, fixing, finisher, evasion, draw)
        }
    }
}

/** Functional roles derived from a card's facts (see [ScryfallClient.classifyRoles]). */
internal data class CardRoleFlags(
    val creature: Boolean,
    val land: Boolean,
    val removal: Boolean,
    val fixing: Boolean,
    val finisher: Boolean,
    val evasion: Boolean,
    val draw: Boolean,
)
