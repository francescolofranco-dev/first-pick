package com.firstpick.cards

import com.firstpick.advisor.canonicalPair
import java.util.concurrent.ConcurrentHashMap

data class ArchetypeStrength(val pair: String, val winRate: Double, val games: Int)

class ArchetypeRepository(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
) {
    private var strengths: Map<String, ArchetypeStrength> = emptyMap()
    // A color pair is not a stable cache key on its own: every set and event
    // format has its own card rows. Keeping the data key here prevents a WU
    // lookup after a set/format switch from silently reusing the previous WU.
    private val perPair = ConcurrentHashMap<String, Map<String, CardRating>>()

    var loadedKey: String? = null
        private set

    suspend fun loadStrengths(setCode: String, format: String) {
        val key = dataKey(setCode, format)
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

    suspend fun ensurePair(setCode: String, format: String, pair: String) {
        val cacheKey = pairKey(dataKey(setCode, format), pair)
        if (perPair.containsKey(cacheKey)) return
        val cards = client.fetch(setCode, format, pair)
        if (cards.isNotEmpty()) perPair[cacheKey] = cards.associateBy { normalize(it.name) }
    }

    fun strengthMap(): Map<String, Double> = strengths.mapValues { it.value.winRate }

    fun rankedPairs(): List<ArchetypeStrength> = strengths.values.sortedByDescending { it.winRate }

    fun archetypeRating(name: String, pair: String): CardRating? {
        val key = loadedKey ?: return null
        return perPair[pairKey(key, pair)]?.get(normalize(name))
    }


    internal fun indexStrengths(map: Map<String, ArchetypeStrength>, key: String = "TEST") {
        strengths = map
        loadedKey = key
    }

    internal fun indexPair(pair: String, ratings: List<CardRating>) {
        val key = loadedKey ?: TEST_KEY.also { loadedKey = it }
        perPair[pairKey(key, pair)] = ratings.associateBy { normalize(it.name) }
    }

    private fun dataKey(setCode: String, format: String): String = "${setCode.uppercase()}_$format"

    private fun pairKey(dataKey: String, pair: String): String = "${dataKey}_${pair.uppercase()}"

    private fun pairFromColorName(name: String): String? {
        if ("+" in name || "splash" in name.lowercase()) return null
        val inParen = PAIR_RE.find(name)?.groupValues?.get(1) ?: return null
        return canonicalPair(inParen.toList())
    }

    private fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()

    companion object {
        private const val TEST_KEY = "TEST"
        private const val MIN_GAMES = 800
        private val PAIR_RE = Regex("\\(([WUBRG]{2})\\)")
    }
}
