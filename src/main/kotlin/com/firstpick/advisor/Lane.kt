package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.math.sqrt

data class Lane(
    val colors: Set<Char>,
    val pair: String?,
    val commitment: Map<Char, Double>,
    val topPairs: List<String> = emptyList(),
) {
    val isEstablished: Boolean get() = pair != null
    val hasBaseColorEvidence: Boolean get() = colors.isNotEmpty()
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
        meta: (String) -> CardMeta? = { null },
        useRecency: Boolean = true,
    ): Lane {
        val commitmentColors = pool.map { card -> commitmentColorsOf(card, meta(card.name)) }
        val commitment = colorCommitment(pool, metrics, commitmentColors, useRecency)
        val colorCounts = colorCounts(commitmentColors)

        val strengthValues = pairStrength.values
        val meanStrength = if (strengthValues.isEmpty()) 0.0 else strengthValues.average()
        val sdStrength = stdDev(strengthValues, meanStrength)

        val signalZ = zByColor(signals)

        val poolPairScores = COLOR_PAIRS.map { pair ->
            pair to poolFit(pool, metrics, pair, commitmentColors, useRecency)
        }
            .sortedByDescending { it.second }
        val supportedPairScores = poolPairScores.filter { (pair, _) ->
            pair.all { color -> hasMeaningfulSupport(color, colorCounts, commitment) }
        }
        val bestSupported = supportedPairScores.firstOrNull()
        val runnerUpSupported = supportedPairScores.getOrNull(1)
        val hasScoreMargin = bestSupported != null && (
            runnerUpSupported == null ||
                bestSupported.second - runnerUpSupported.second >= bestSupported.second * MIN_PAIR_SCORE_MARGIN
            )
        val bestPair = bestSupported?.first?.takeIf { pool.size >= MIN_POOL_SIZE && hasScoreMargin }

        // Archetype strength and passed-card signals are useful while reading the
        // table, but they must not overwrite an identity supported by drafted cards.
        val guidancePairScores = if (bestPair == null) {
            COLOR_PAIRS.map { pair ->
                val fit = poolPairScores.first { it.first == pair }.second
                val archZ = pairStrength[pair]?.let { if (sdStrength > 1e-9) (it - meanStrength) / sdStrength else 0.0 } ?: 0.0
                val sig = (signalZ[pair[0]] ?: 0.0) + (signalZ[pair[1]] ?: 0.0)
                pair to (fit + ARCH_BIAS * archZ + SIGNAL_BIAS * sig)
            }.sortedByDescending { it.second }
        } else {
            poolPairScores
        }
        val topPairs = if (bestPair == null) {
            guidancePairScores.take(3).map { it.first }
        } else {
            listOf(bestPair) + guidancePairScores.asSequence()
                .map { it.first }
                .filterNot { it == bestPair }
                .take(2)
        }

        val colors = if (pool.size < MIN_POOL_SIZE) {
            emptySet()
        } else {
            bestPair?.toSet() ?: commitment.entries
                .filter { (color, _) -> hasMeaningfulSupport(color, colorCounts, commitment) }
                .sortedByDescending { it.value }
                .take(2)
                .map { it.key }
                .toSet()
        }
        return Lane(colors, bestPair, commitment, topPairs).apply { poolSize = pool.size }
    }

    fun colorsOf(card: RankedCard): Set<Char> =
        card.rating?.color.orEmpty().filter { it in WUBRG }.toSet()

    fun uncastableColors(
        colors: Set<Char>,
        available: Set<Char>,
        hybridGroups: List<Set<Char>> = emptyList(),
        pureColors: Set<Char>? = null,
    ): Set<Char> = uncastableColorOptions(colors, available, hybridGroups, pureColors).flatten().toSet()

    /**
     * Returns every minimal set of additional colors that can cast the card.
     * Keeping hybrid alternatives separate prevents `{B/R}` from becoming a
     * false two-color requirement and prevents `{B}{B/R}` from hiding pure B.
     */
    fun uncastableColorOptions(
        colors: Set<Char>,
        available: Set<Char>,
        hybridGroups: List<Set<Char>> = emptyList(),
        pureColors: Set<Char>? = null,
    ): List<Set<Char>> {
        val hybridColors = hybridGroups.flatten().toSet()
        val mandatory = ((pureColors ?: (colors - hybridColors)) - available).filterTo(mutableSetOf()) { it in WUBRG }
        var options = listOf<Set<Char>>(mandatory)
        for (group in hybridGroups.map { it.filterTo(mutableSetOf()) { color -> color in WUBRG } }.filter { it.isNotEmpty() }) {
            options = options.flatMap { current ->
                if (group.any { it in available || it in current }) listOf(current)
                else group.map { current + it }
            }.distinct().filterMinimalColorSets()
        }
        return options.filterMinimalColorSets().sortedWith(
            compareBy<Set<Char>> { it.size }.thenBy { option -> WUBRG_ORDER.filter(option::contains) },
        )
    }

    private fun List<Set<Char>>.filterMinimalColorSets(): List<Set<Char>> =
        filter { candidate -> none { other -> other !== candidate && other.size < candidate.size && candidate.containsAll(other) } }

    private fun poolFit(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        pair: String,
        commitmentColors: List<Set<Char>>,
        useRecency: Boolean,
    ): Double {
        val pairSet = pair.toSet()
        val n = pool.size
        var sum = 0.0
        pool.forEachIndexed { i, card ->
            val colors = commitmentColors[i]
            if (colors.isEmpty() || !pairSet.containsAll(colors)) return@forEachIndexed
            sum += recency(i, n, useRecency) * power(card, metrics)
        }
        return sum
    }

    private fun colorCommitment(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        commitmentColors: List<Set<Char>>,
        useRecency: Boolean,
    ): Map<Char, Double> {
        val commitment = mutableMapOf<Char, Double>()
        val n = pool.size
        pool.forEachIndexed { i, card ->
            val w = recency(i, n, useRecency) * power(card, metrics)
            for (ch in commitmentColors[i]) commitment.merge(ch, w, Double::plus)
        }
        return commitment
    }

    private fun commitmentColorsOf(card: RankedCard, meta: CardMeta?): Set<Char> {
        val hasExactManaCost = meta != null && (
            meta.coloredPips.isNotEmpty() ||
                meta.hybridPips.isNotEmpty() ||
                meta.hybridColorGroups.isNotEmpty()
            )
        // Flexible hybrid symbols do not commit a drafter to both printed colors.
        // Pure pips still count, so {B}{B/R} contributes to black but not red.
        if (!hasExactManaCost) return colorsOf(card)
        return meta?.coloredPips.orEmpty().keys.filterTo(mutableSetOf()) { it in WUBRG }
    }

    private fun colorCounts(commitmentColors: List<Set<Char>>): Map<Char, Int> {
        val counts = mutableMapOf<Char, Int>()
        for (colors in commitmentColors) for (color in colors) counts.merge(color, 1, Int::plus)
        return counts
    }

    private fun hasMeaningfulSupport(
        color: Char,
        colorCounts: Map<Char, Int>,
        commitment: Map<Char, Double>,
    ): Boolean {
        if ((colorCounts[color] ?: 0) < MIN_COLOR_CARD_COUNT) return false
        val totalCommitment = commitment.values.sum()
        return totalCommitment > 0.0 && (commitment[color] ?: 0.0) / totalCommitment >= MIN_COLOR_COMMITMENT_SHARE
    }

    private fun recency(index: Int, n: Int, useRecency: Boolean): Double =
        if (!useRecency) NEUTRAL_RECENCY
        else if (n <= 1) MAX_RECENCY
        else MIN_RECENCY + (MAX_RECENCY - MIN_RECENCY) * (index.toDouble() / (n - 1))

    private fun power(card: RankedCard, metrics: SetMetrics): Double {
        val qualityZ = metrics.z(card.gihWr)?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_QUALITY_Z) ?: 0.0
        return BASE_PICK_WEIGHT + QUALITY_WEIGHT * qualityZ
    }

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
    private const val NEUTRAL_RECENCY = 1.0
    private const val BASE_PICK_WEIGHT = 1.0
    private const val QUALITY_WEIGHT = 0.35
    private const val MAX_QUALITY_Z = 2.0

    private const val MIN_POOL_SIZE = 5
    private const val MIN_COLOR_CARD_COUNT = 2
    private const val MIN_COLOR_COMMITMENT_SHARE = 0.10
    private const val MIN_PAIR_SCORE_MARGIN = 0.10

    private const val ARCH_BIAS = 2.0

    private const val SIGNAL_BIAS = 1.5
}
