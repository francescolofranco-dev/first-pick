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
 * logic depends on. Removal, evasion, and draw use Scryfall's curated oracle tags
 * (`otag:`), with an oracle-text heuristic only as a day-one fallback; fixing keys off
 * Scryfall's produced-mana data; finisher is a curve heuristic (no curated tag exists).
 * Cached per set for a week. Polite: descriptive User-Agent and a short delay between pages.
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
    private data class CollectionResponse(@SerialName("data") val data: List<Card> = emptyList())

    @Serializable
    private data class Identifier(val name: String)

    @Serializable
    private data class CollectionRequest(val identifiers: List<Identifier>)

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

    /**
     * Map of normalized card name -> [CardMeta] for the given set. When [names] is given
     * (the 17Lands card list), metadata is fetched by name via Scryfall's collection
     * endpoint so bonus-sheet cards a `set:` query misses (e.g. OTJ's *The Big Score*) are
     * still covered; otherwise it falls back to a `set:` search.
     */
    suspend fun setMeta(set: String, names: Collection<String> = emptyList()): Map<String, CardMeta> = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        // Cache version bumped (v5) so the by-name fetch (which adds bonus-sheet cards and
        // their mana values) re-derives immediately rather than waiting out staleness.
        val cache = cacheDir.resolve("scryfall5_${set.uppercase()}.json")
        val dtos: List<MetaDto> = if (isFresh(cache)) {
            runCatching { json.decodeFromString(LIST_SERIALIZER, Files.readString(cache)) }.getOrDefault(emptyList())
        } else {
            val built = build(set, names)
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

    private fun build(set: String, names: Collection<String>): List<MetaDto>? {
        // Prefer the authoritative 17Lands card list (covers bonus sheets); fall back to a
        // `set:` search when we have no names (e.g. a meta-only load before ratings arrive).
        val cards = (if (names.isNotEmpty()) fetchByNames(names) else null)
            ?: searchCards("set:${set.lowercase()} game:arena unique:cards")
            ?: return null
        val q = "set:${set.lowercase()}"
        // Scryfall's curated functional (oracle) tags — the opinionated source for these
        // roles, more accurate than an oracle-text guess. Scoped to the main set; bonus-sheet
        // cards fall back to the oracle-text heuristic in [classifyRoles]. (Fixing keys off
        // Scryfall's produced-mana data; finisher has no curated tag, so stays heuristic.)
        val removalNames = taggedNames("$q otag:removal")
        val evasionNames = taggedNames("$q otag:evasion")
        val drawNames = taggedNames("$q otag:draw")
        return cards.map { dtoOf(it, removalNames, evasionNames, drawNames) }
    }

    /** Fetch full card objects by name via Scryfall's collection endpoint (batched ≤75). */
    private fun fetchByNames(names: Collection<String>): List<Card>? {
        val ids = names.map { frontName(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return null
        val out = mutableListOf<Card>()
        var anySucceeded = false
        for ((i, batch) in ids.chunked(COLLECTION_BATCH).withIndex()) {
            val cards = postCollection(batch) ?: continue
            out += cards
            anySucceeded = true
            if (i < ids.size / COLLECTION_BATCH) runCatching { Thread.sleep(PAGE_DELAY_MS) }
        }
        return out.takeIf { anySucceeded }
    }

    /** POST one batch of name identifiers; returns the resolved cards (missing names dropped). */
    private fun postCollection(names: List<String>): List<Card>? {
        val body = json.encodeToString(CollectionRequest.serializer(), CollectionRequest(names.map { Identifier(it) }))
        for (attempt in 1..SeventeenLandsClient.MAX_ATTEMPTS) {
            val (cards, failure) = postCollectionOnce(body)
            if (cards != null) return cards
            if (failure == null || !SeventeenLandsClient.isTransient(failure) || attempt == SeventeenLandsClient.MAX_ATTEMPTS) {
                if (failure != null) Log.warn(TAG, "POST collection failed ($failure) after $attempt attempt(s)")
                return null
            }
            val backoff = SeventeenLandsClient.BASE_BACKOFF_MS * (1L shl (attempt - 1))
            runCatching { Thread.sleep(backoff) }
        }
        return null
    }

    private fun postCollectionOnce(body: String): Pair<List<Card>?, FetchFailure?> = runCatching {
        val request = HttpRequest.newBuilder(URI.create("https://api.scryfall.com/cards/collection"))
            .header("User-Agent", SeventeenLandsClient.USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (resp.statusCode()) {
            200 -> json.decodeFromString<CollectionResponse>(resp.body()).data to null
            else -> null to SeventeenLandsClient.failureForStatus(resp.statusCode())
        }
    }.getOrElse { null to FetchFailure.OFFLINE }

    private fun taggedNames(query: String): Set<String> =
        searchCards(query).orEmpty().mapTo(mutableSetOf()) { frontName(it.name) }

    private fun dtoOf(c: Card, removalNames: Set<String>, evasionNames: Set<String>, drawNames: Set<String>): MetaDto {
        val cmc = c.cmc.roundToInt()
        val power = c.power?.substringBefore("+")?.toIntOrNull() ?: 0
        val name = frontName(c.name)
        val r = classifyRoles(
            c.typeLine, c.oracleText, cmc, power, c.producedMana,
            removalTagged = name in removalNames,
            evasionTagged = name in evasionNames,
            drawTagged = name in drawNames,
        )
        return MetaDto(name, cmc, r.creature, r.land, r.removal, r.fixing, r.finisher, r.evasion, r.draw)
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
        private const val COLLECTION_BATCH = 75 // Scryfall's max identifiers per collection request
        private val LIST_SERIALIZER = ListSerializer(MetaDto.serializer())
        private val EVASION_RE = Regex("flying|menace|can't be blocked|skulk|shadow|intimidate|horsemanship")

        // Interaction that answers a CREATURE/permanent — deliberately NOT matching
        // damage/effects aimed only at a player (e.g. "deals 1 damage to target player",
        // "to each opponent"), which is reach/burn-to-face, not removal. The curated
        // otag:removal tag remains the primary signal; this is the fallback.
        private val REMOVAL_RE = Regex(
            "(destroy|exile) target (creature|permanent|artifact|enchantment|planeswalker|nonland)" +
                "|deals \\d+ damage to (any target|target creature|each creature|all creatures|target permanent|target planeswalker|that creature)" +
                "|fights?" +
                "|gets -\\d" +
                "|each (opponent|player) sacrifices",
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
            removalTagged: Boolean = false,
            evasionTagged: Boolean = false,
            drawTagged: Boolean = false,
        ): CardRoleFlags {
            val type = typeLine.lowercase()
            val text = oracleText.lowercase()
            val isLand = "land" in type
            val isCreature = "creature" in type
            val producedColors = producedMana.filter { it.length == 1 && it[0] in "WUBRG" }.toSet()
            // Curated Scryfall otag first, oracle-text heuristic as a fallback (for cards
            // the tagger hasn't reached yet, e.g. a brand-new set on release day).
            val evasion = evasionTagged || (isCreature && EVASION_RE.containsMatchIn(text))
            val removal = removalTagged || REMOVAL_RE.containsMatchIn(text)
            val draw = drawTagged ||
                ("draw a card" in text || "draw two cards" in text || "draws a card" in text)
            // Fixing already keys off Scryfall's produced-mana data (≈ produces>=2);
            // finisher has no curated tag, so it's an intentional curve/board heuristic.
            val fixing = (producedColors.size >= 2) ||
                ("any color" in text) ||
                ("treasure" in text && "create" in text)
            val finisher = isCreature && (power >= 5 || (power >= 4 && (cmc >= 5 || evasion)))
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
