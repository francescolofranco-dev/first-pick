package com.firstpick.cards

import com.firstpick.advisor.canonicalPair

/** A color-pair archetype's strength in the set (overall win rate over many games). */
data class ArchetypeStrength(val pair: String, val winRate: Double, val games: Int)

/**
 * 17Lands archetype data: each color pair's overall strength, plus per-pair
 * card win rates (loaded lazily for the pairs the draft actually visits, to keep
 * network use small). All graceful — lookups return null until data arrives.
 */
class ArchetypeRepository(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
) {
    private var strengths: Map<String, ArchetypeStrength> = emptyMap()
    private val perPair = mutableMapOf<String, Map<String, CardRating>>()

    var loadedKey: String? = null
        private set

    /** Load the ten color-pair strengths for a set/format (one request). */
    suspend fun loadStrengths(setCode: String, format: String) {
        val key = "${setCode.uppercase()}_$format"
        if (key == loadedKey && strengths.isNotEmpty()) return
        val rows = client.colorRatings(setCode, format)
        strengths = rows
            .mapNotNull { row ->
                val pair = pairFromColorName(row.colorName) ?: return@mapNotNull null
                val wr = row.winRate ?: return@mapNotNull null
                if (row.games >= MIN_GAMES) pair to ArchetypeStrength(pair, wr, row.games) else null
            }
            .toMap()
        loadedKey = key
    }

    /** Load a single pair's archetype-specific card win rates (cached; idempotent). */
    suspend fun ensurePair(setCode: String, format: String, pair: String) {
        if (perPair.containsKey(pair)) return
        val cards = client.fetch(setCode, format, pair)
        if (cards.isNotEmpty()) perPair[pair] = cards.associateBy { normalize(it.name) }
    }

    fun strengthMap(): Map<String, Double> = strengths.mapValues { it.value.winRate }

    fun rankedPairs(): List<ArchetypeStrength> = strengths.values.sortedByDescending { it.winRate }

    /** A card's win rate within a specific archetype pair (null if not loaded / no sample). */
    fun archetypeRating(name: String, pair: String): CardRating? = perPair[pair]?.get(normalize(name))

    // ---- internal / test hooks -------------------------------------------

    internal fun indexStrengths(map: Map<String, ArchetypeStrength>, key: String = "TEST") {
        strengths = map
        loadedKey = key
    }

    internal fun indexPair(pair: String, ratings: List<CardRating>) {
        perPair[pair] = ratings.associateBy { normalize(it.name) }
    }

    /** "Boros (RW)" -> "WR"; skips splash / mono / three-color rows. */
    private fun pairFromColorName(name: String): String? {
        if ("+" in name || "splash" in name.lowercase()) return null
        val inParen = PAIR_RE.find(name)?.groupValues?.get(1) ?: return null
        return canonicalPair(inParen.toList())
    }

    private fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()

    companion object {
        private const val MIN_GAMES = 800
        private val PAIR_RE = Regex("\\(([WUBRG]{2})\\)")
    }
}
