package com.firstpick.advisor

import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.math.sqrt

/** The colors the player is leaning into, the canonical 2-color pair, and raw per-color weight. */
data class Lane(
    val colors: Set<Char>,
    val pair: String?,
    val commitment: Map<Char, Double>,
    val topPairs: List<String> = emptyList(),
) {
    val isEstablished: Boolean get() = poolSize > 0 && colors.isNotEmpty()
    var poolSize: Int = 0 // Stored so UI knows if we've started picking
}

val WUBRG = setOf('W', 'U', 'B', 'R', 'G')
private const val WUBRG_ORDER = "WUBRG"

/** The ten two-color archetypes, in canonical WUBRG order. */
val COLOR_PAIRS = listOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG")
val TRI_COLORS = listOf("WUB", "WUR", "WUG", "WBR", "WBG", "WRG", "UBR", "UBG", "URG", "BRG")

/** Canonical WUBRG-ordered pair string for two colors, or null if not exactly two. */
fun canonicalPair(colors: Collection<Char>): String? {
    val cs = colors.filter { it in WUBRG }.distinct().sortedBy { WUBRG_ORDER.indexOf(it) }
    return if (cs.size == 2) cs.joinToString("") else null
}

/**
 * Infers the player's lane by scoring all ten color pairs on two signals:
 *  - pool fit: recency-weighted power of the pool cards that fit the pair
 *    (recent picks weigh more — "sunk-cost evasion"), and
 *  - archetype strength: how strong that pair is in the set (17Lands pair win rate).
 *
 * Because pool fit grows as you draft while the archetype term is constant, the set's
 * strong archetypes guide an empty/ambiguous lane early, and your actual picks take
 * over later — exactly the behavior good drafters use.
 */
object LaneDetector {

    fun detect(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        pairStrength: Map<String, Double> = emptyMap(),
    ): Lane {
        val commitment = colorCommitment(pool, metrics)

        val strengthValues = pairStrength.values
        val meanStrength = if (strengthValues.isEmpty()) 0.0 else strengthValues.average()
        val sdStrength = stdDev(strengthValues, meanStrength)

        val pairScores = mutableListOf<Pair<String, Double>>()
        for (pair in COLOR_PAIRS) {
            val fit = poolFit(pool, metrics, pair)
            val archZ = pairStrength[pair]?.let { if (sdStrength > 1e-9) (it - meanStrength) / sdStrength else 0.0 } ?: 0.0
            val score = fit + ARCH_BIAS * archZ
            pairScores.add(pair to score)
        }

        pairScores.sortByDescending { it.second }
        val topPairs = pairScores.take(3).map { it.first }
        val bestPairCandidate = if (pool.isNotEmpty()) topPairs.firstOrNull() else null
        
        // Don't claim a single best lane until we actually have some commitment to BOTH colors!
        // It is confusing to declare "Simic" when the user has only picked a single blue card.
        // It's also way too early to declare a lane before we have at least 5 cards.
        val bestPair = if (pool.size >= 5 && bestPairCandidate != null && 
                           (commitment[bestPairCandidate[0]] ?: 0.0) > 0.0 && 
                           (commitment[bestPairCandidate[1]] ?: 0.0) > 0.0) {
            bestPairCandidate
        } else {
            null
        }
        
        // If we haven't established a pair yet, our "colors" are just our most committed colors so far.
        // We take the top 2 committed colors so the pip display doesn't show 5 colors if we draft a 5-color pile early on.
        // However, before we have 5 cards, we should just stay completely undecided.
        val colors = if (pool.size < 5) emptySet() else (bestPair?.toSet() ?: commitment.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet())
        return Lane(colors, bestPair, commitment, topPairs).apply { poolSize = pool.size }
    }

    fun colorsOf(card: RankedCard): Set<Char> =
        card.rating?.color.orEmpty().filter { it in WUBRG }.toSet()

    /** Recency-weighted power the pool contributes to a specific pair (on-pair cards only). */
    private fun poolFit(pool: List<RankedCard>, metrics: SetMetrics, pair: String): Double {
        val pairSet = pair.toSet()
        val n = pool.size
        var sum = 0.0
        pool.forEachIndexed { i, card ->
            val colors = colorsOf(card)
            if (colors.isEmpty() || !pairSet.containsAll(colors)) return@forEachIndexed
            sum += recency(i, n) * power(card, metrics)
        }
        return sum
    }

    /** Per-color commitment (for the pip display), independent of pairing. */
    private fun colorCommitment(pool: List<RankedCard>, metrics: SetMetrics): Map<Char, Double> {
        val commitment = mutableMapOf<Char, Double>()
        val n = pool.size
        pool.forEachIndexed { i, card ->
            val w = recency(i, n) * power(card, metrics)
            for (ch in colorsOf(card)) commitment.merge(ch, w, Double::plus)
        }
        return commitment
    }

    private fun recency(index: Int, n: Int): Double =
        if (n <= 1) MAX_RECENCY else MIN_RECENCY + (MAX_RECENCY - MIN_RECENCY) * (index.toDouble() / (n - 1))

    private fun power(card: RankedCard, metrics: SetMetrics): Double =
        (metrics.z(card.gihWr) ?: DEFAULT_POWER).coerceAtLeast(FLOOR_POWER)

    private fun stdDev(values: Collection<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private const val MIN_RECENCY = 1.0
    private const val MAX_RECENCY = 2.5
    private const val DEFAULT_POWER = -0.5
    private const val FLOOR_POWER = 0.1

    /** How strongly set archetype strength biases an undecided lane (in pool-fit units). */
    private const val ARCH_BIAS = 2.0
}
