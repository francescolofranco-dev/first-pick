package com.firstpick.advisor

import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.math.sqrt

data class Lane(
    val colors: Set<Char>,
    val pair: String?,
    val commitment: Map<Char, Double>,
    val topPairs: List<String> = emptyList(),
) {
    val isEstablished: Boolean get() = poolSize > 0 && colors.isNotEmpty()
    var poolSize: Int = 0
}

val WUBRG = setOf('W', 'U', 'B', 'R', 'G')
private const val WUBRG_ORDER = "WUBRG"

val COLOR_PAIRS = listOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG")

fun canonicalPair(colors: Collection<Char>): String? {
    val cs = colors.filter { it in WUBRG }.distinct().sortedBy { WUBRG_ORDER.indexOf(it) }
    return if (cs.size == 2) cs.joinToString("") else null
}

object LaneDetector {

    fun detect(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        pairStrength: Map<String, Double> = emptyMap(),
        signals: Map<Char, Double> = emptyMap(),
    ): Lane {
        val commitment = colorCommitment(pool, metrics)

        val strengthValues = pairStrength.values
        val meanStrength = if (strengthValues.isEmpty()) 0.0 else strengthValues.average()
        val sdStrength = stdDev(strengthValues, meanStrength)

        val signalZ = zByColor(signals)

        val pairScores = mutableListOf<Pair<String, Double>>()
        for (pair in COLOR_PAIRS) {
            val fit = poolFit(pool, metrics, pair)
            val archZ = pairStrength[pair]?.let { if (sdStrength > 1e-9) (it - meanStrength) / sdStrength else 0.0 } ?: 0.0
            val sig = (signalZ[pair[0]] ?: 0.0) + (signalZ[pair[1]] ?: 0.0)
            val score = fit + ARCH_BIAS * archZ + SIGNAL_BIAS * sig
            pairScores.add(pair to score)
        }

        pairScores.sortByDescending { it.second }
        val topPairs = pairScores.take(3).map { it.first }
        val bestPairCandidate = if (pool.isNotEmpty()) topPairs.firstOrNull() else null

        val bestPair = if (pool.size >= 5 && bestPairCandidate != null &&
                           (commitment[bestPairCandidate[0]] ?: 0.0) > 0.0 &&
                           (commitment[bestPairCandidate[1]] ?: 0.0) > 0.0) {
            bestPairCandidate
        } else {
            null
        }

        val colors = if (pool.size < 5) emptySet() else (bestPair?.toSet() ?: commitment.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet())
        return Lane(colors, bestPair, commitment, topPairs).apply { poolSize = pool.size }
    }

    fun colorsOf(card: RankedCard): Set<Char> =
        card.rating?.color.orEmpty().filter { it in WUBRG }.toSet()

    fun uncastableColors(colors: Set<Char>, available: Set<Char>, hybridGroups: List<Set<Char>> = emptyList()): Set<Char> {
        if (hybridGroups.isEmpty()) return colors - available
        val hybridColors = hybridGroups.flatten().toSet()
        val offPure = (colors - hybridColors) - available
        val offHybrid = hybridGroups.filter { group -> group.none { it in available } }.flatten().toSet()
        return offPure + offHybrid
    }

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

    private fun zByColor(signals: Map<Char, Double>): Map<Char, Double> {
        if (signals.isEmpty()) return emptyMap()
        val full = WUBRG.associateWith { signals[it] ?: 0.0 }
        val mean = full.values.average()
        val sd = stdDev(full.values, mean)
        return if (sd <= 1e-9) full.mapValues { 0.0 } else full.mapValues { (it.value - mean) / sd }
    }

    private const val MIN_RECENCY = 1.0
    private const val MAX_RECENCY = 2.5
    private const val DEFAULT_POWER = -0.5
    private const val FLOOR_POWER = 0.1

    private const val ARCH_BIAS = 2.0

    private const val SIGNAL_BIAS = 1.5
}
