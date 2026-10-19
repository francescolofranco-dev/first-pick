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
        @SerialName("mana_cost") val manaCost: String = "",
        @SerialName("arena_id") val arenaId: Int? = null,
        @SerialName("colors") val colors: List<String> = emptyList(),
        @SerialName("rarity") val rarity: String = "",
        @SerialName("image_uris") val imageUris: Map<String, String> = emptyMap(),
        @SerialName("layout") val layout: String = "normal",
        @SerialName("card_faces") val faces: List<CardFace> = emptyList(),
    )

    @Serializable
    private data class CardFace(
        @SerialName("mana_cost") val manaCost: String = "",
        @SerialName("type_line") val typeLine: String = "",
        @SerialName("oracle_text") val oracleText: String = "",
        @SerialName("power") val power: String? = null,
        @SerialName("colors") val colors: List<String> = emptyList(),
        @SerialName("image_uris") val imageUris: Map<String, String> = emptyMap(),
    )

    /** Card identities only: no win rates, pick rates, or invented Arena IDs. */
    suspend fun cardCatalog(set: String, forceRefresh: Boolean = false): CardCatalog = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve("scryfall_catalog1_${set.uppercase()}.json")
        val cached = if (Files.exists(cache)) {
            runCatching { json.decodeFromString(ListSerializer(Card.serializer()), Files.readString(cache)) }
                .getOrDefault(emptyList())
        } else emptyList()
        val cards = if (!forceRefresh && cached.isNotEmpty() && isFresh(cache)) cached else {
            val fetched = searchCards("${limitedSetQuery(set)} unique:cards")
            if (!fetched.isNullOrEmpty()) {
                Files.writeString(cache, json.encodeToString(ListSerializer(Card.serializer()), fetched))
                fetched
            } else cached
        }
        CardCatalog(
            cards = cards.map { card ->
                val face = card.faces.firstOrNull()
                CardRating(
                    name = card.name,
                    mtgaId = card.arenaId,
                    color = "WUBRG".filter { it.toString() in card.colors.ifEmpty { face?.colors.orEmpty() } },
                    rarity = card.rarity,
                    types = listOf(card.typeLine.ifBlank { face?.typeLine.orEmpty() }),
                    imageUrl = card.imageUris["normal"] ?: face?.imageUris?.get("normal").orEmpty(),
                )
            },
            lastUpdated = if (Files.exists(cache)) Files.getLastModifiedTime(cache).toInstant() else Instant.now(),
        )
    }

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
        val hybridGroups: List<String> = emptyList(),
        val produced: String = "",
        val heavyPips: String = "",
        val coloredPips: String = "",
        val hybridPips: List<String> = emptyList(),
        val fixingRequiresAdditionalSpell: Boolean = false,
        val manaCost: String? = null,
        val planeswalker: Boolean = false,
        val massRemoval: Boolean = false,
        val repeatableAdvantage: Boolean = false,
        val impactKnown: Boolean = false,
    )

    suspend fun setMeta(set: String, names: Collection<String> = emptyList()): Map<String, CardMeta> = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve("scryfall8_${set.uppercase()}.json")
        val cached = if (Files.exists(cache)) {
            runCatching { json.decodeFromString(LIST_SERIALIZER, Files.readString(cache)) }.getOrDefault(emptyList())
        } else emptyList()
        val dtos: List<MetaDto> = if (isFresh(cache) && cached.all { it.manaCost != null && it.impactKnown }) {
            cached
        } else {
            val built = build(set, names)
            if (built != null) {
                Files.writeString(cache, json.encodeToString(LIST_SERIALIZER, built))
                built
            } else {
                cached
            }
        }
        dtos.associate { normalize(it.name) to it.toMeta() }
    }

    private fun MetaDto.toMeta() = CardMeta(
        name = name, cmc = cmc, isCreature = creature, isLand = land,
        isRemoval = removal, isFixing = fixing, isFinisher = finisher,
        isEvasion = evasion, isCardDraw = draw,
        hybridColorGroups = hybridGroups.map { it.toSet() },
        producedColors = produced.toSet(),
        heavyPipColors = heavyPips.toSet(),
        coloredPips = coloredPips.groupingBy { it }.eachCount(),
        hybridPips = hybridPips.map { it.toSet() },
        fixingRequiresAdditionalSpell = fixingRequiresAdditionalSpell,
        manaCost = manaCost,
        isPlaneswalker = planeswalker,
        isMassRemoval = massRemoval,
        isRepeatableAdvantage = repeatableAdvantage,
    )

    private fun build(set: String, names: Collection<String>): List<MetaDto>? {
        val cards = (if (names.isNotEmpty()) fetchByNames(names) else null)
            ?: searchCards("${limitedSetQuery(set)} unique:cards")
            ?: return null
        val q = limitedSetQuery(set)
        val removalNames = taggedNames("$q otag:removal")
        val evasionNames = taggedNames("$q otag:evasion")
        val drawNames = taggedNames("$q otag:draw")
        return cards.map { dtoOf(it, removalNames, evasionNames, drawNames) }
    }

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
        val face = c.faces.firstOrNull()
        val power = (c.power ?: face?.power)?.substringBefore("+")?.toIntOrNull() ?: 0
        val name = frontName(c.name)
        val oracleText = c.oracleText.ifBlank { c.faces.joinToString("\n") { it.oracleText } }
        val producedMana = if (createsHeartwood(oracleText)) c.producedMana + listOf("R", "G") else c.producedMana
        // Prepare and Adventure spells are optional: their pips are not a cost to cast the creature.
        val manaCost = if (c.layout in setOf("prepare", "adventure")) face?.manaCost ?: c.manaCost
            else c.manaCost.ifBlank { face?.manaCost.orEmpty() }
        val r = classifyRoles(
            c.typeLine.ifBlank { face?.typeLine.orEmpty() }, oracleText, cmc, power, producedMana,
            removalTagged = name in removalNames,
            evasionTagged = name in evasionNames,
            drawTagged = name in drawNames,
        )
        val produced = "WUBRG".filter { ch -> producedMana.any { it.length == 1 && it[0] == ch } }
        val extraSpellFixing = c.layout in setOf("prepare", "adventure") && c.faces.drop(1).any {
            classifyRoles(it.typeLine, it.oracleText, 0, 0, emptyList()).fixing
        }
        return MetaDto(
            name, cmc, r.creature, r.land, r.removal, r.fixing, r.finisher, r.evasion, r.draw,
            hybridGroupsOf(manaCost), produced, heavyPipsOf(manaCost),
            coloredPipsOf(manaCost), hybridPipsOf(manaCost),
            fixingRequiresAdditionalSpell = extraSpellFixing,
            manaCost = manaCost,
            planeswalker = "planeswalker" in c.typeLine.ifBlank { face?.typeLine.orEmpty() }.lowercase(),
            massRemoval = isMassRemoval(oracleText),
            repeatableAdvantage = isRepeatableAdvantage(oracleText),
            impactKnown = true,
        )
    }

    private fun searchCards(query: String): List<Card>? {
        var url: String? = "https://api.scryfall.com/cards/search?q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8)
        val out = mutableListOf<Card>()
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val page = getPage(url) ?: return null
            if (pages > 0 && page.data.isEmpty()) return null
            if (page.hasMore && page.nextPage == null) return null
            out += page.data
            url = page.nextPage?.takeIf { page.hasMore }
            pages++
            if (url != null) runCatching { Thread.sleep(PAGE_DELAY_MS) }
        }
        return out.takeIf { url == null }
    }

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
        private const val COLLECTION_BATCH = 75
        private val LIST_SERIALIZER = ListSerializer(MetaDto.serializer())
        private val EVASION_RE = Regex("flying|menace|can't be blocked|skulk|shadow|intimidate|horsemanship")
        private val DRAW_RE = Regex("draws? (?:a|one|two|three|four|five|six|seven|eight|nine|ten|x|\\d+) cards?")
        private val HEARTWOOD_CREATE_RE = Regex("\\bcreate (?:a|one|two|three|four|five|x|\\d+) (?:tapped )?heartwood tokens?\\b", RegexOption.IGNORE_CASE)
        private fun createsHeartwood(text: String): Boolean = HEARTWOOD_CREATE_RE.containsMatchIn(text)

        internal fun limitedSetQuery(set: String): String = when (set.uppercase()) {
            // Wizards' Collecting Reality Fracture: ten Special Guests, collector numbers 159–168.
            "FRA" -> "(set:fra or (set:spg cn>=159 cn<=168))"
            else -> "set:${set.lowercase()} game:arena"
        }

        private val HYBRID_RE = Regex("\\{([WUBRG])/([WUBRG])\\}")
        private val PURE_PIP_RE = Regex("\\{([WUBRG])\\}")


        internal fun heavyPipsOf(manaCost: String): String {
            val counts = mutableMapOf<Char, Int>()
            for (m in PURE_PIP_RE.findAll(manaCost)) counts.merge(m.groupValues[1][0], 1, Int::plus)
            return "WUBRG".filter { (counts[it] ?: 0) >= 2 }
        }

        internal fun coloredPipsOf(manaCost: String): String = buildString {
            val counts = PURE_PIP_RE.findAll(manaCost)
                .map { it.groupValues[1][0] }
                .groupingBy { it }
                .eachCount()
            for (color in "WUBRG") repeat(counts[color] ?: 0) { append(color) }
        }

        internal fun hybridPipsOf(manaCost: String): List<String> =
            HYBRID_RE.findAll(manaCost)
                .map { m -> listOf(m.groupValues[1][0], m.groupValues[2][0]).sortedBy { "WUBRG".indexOf(it) }.joinToString("") }
                .toList()


        internal fun hybridGroupsOf(manaCost: String): List<String> =
            HYBRID_RE.findAll(manaCost)
                .map { m -> listOf(m.groupValues[1][0], m.groupValues[2][0]).sortedBy { "WUBRG".indexOf(it) }.joinToString("") }
                .distinct()
                .toList()

        private val REMOVAL_RE = Regex(
            "(destroy|exile) target (creature|permanent|artifact|enchantment|planeswalker|nonland)" +
                "|deals \\d+ damage to (any target|target creature|each creature|all creatures|target permanent|target planeswalker|that creature)" +
                "|fights?" +
                "|gets -\\d" +
                "|each (opponent|player) sacrifices",
        )

        /** Only broad, unconditional creature wipes; tribal/target-dependent effects need more context. */
        internal fun isMassRemoval(oracleText: String): Boolean {
            val text = oracleText.lowercase()
            if ("choose a creature type" in text || "if " in text || "unless " in text) return false
            return Regex("(destroy|exile) all creatures(?:[.;\n]|$)").containsMatchIn(text) ||
                Regex("deals ([4-9]|[1-9][0-9]+) damage to each creature(?:[.;\n]|$)").containsMatchIn(text)
        }

        internal fun isRepeatableAdvantage(oracleText: String): Boolean =
            oracleText.lowercase().lines().any { line ->
                (line.startsWith("whenever ") || line.startsWith("at the beginning of ")) &&
                    Regex("draw (a|two|three|[0-9]+) cards?").containsMatchIn(line)
            }

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
            val evasion = evasionTagged || (isCreature && EVASION_RE.containsMatchIn(text))
            val removal = removalTagged || REMOVAL_RE.containsMatchIn(text)
            val draw = drawTagged || DRAW_RE.containsMatchIn(text)
            val fixing = (producedColors.size >= 2) ||
                ("any color" in text) ||
                ("treasure" in text && "create" in text) || createsHeartwood(text)
            val finisher = isCreature && (power >= 5 || (power >= 4 && (cmc >= 5 || evasion)))
            return CardRoleFlags(isCreature, isLand, removal, fixing, finisher, evasion, draw)
        }
    }
}

data class CardCatalog(val cards: List<CardRating>, val lastUpdated: Instant)

internal data class CardRoleFlags(
    val creature: Boolean,
    val land: Boolean,
    val removal: Boolean,
    val fixing: Boolean,
    val finisher: Boolean,
    val evasion: Boolean,
    val draw: Boolean,
)
