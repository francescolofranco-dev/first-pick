package com.firstpick.eval

import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.LaneDetector
import com.firstpick.cards.CardMeta
import com.firstpick.cards.SetMetrics
import kotlin.math.sqrt

/**
 * Turns a built [DeckOption] into a fixed feature vector for the outcome-trained
 * [DeckStrengthModel]. Features are deck-quality signals (power, curve, roles) plus the
 * drafter's [rankOrdinal] as an explicit skill control, so the model can hold skill fixed
 * when comparing two decks. Index 0 is the intercept; [RANK_INDEX] locates the skill control.
 */
object DeckFeatures {
    val NAMES = listOf(
        "intercept", "deckZ", "topZ", "removal", "creatures", "twoDrops",
        "avgCmc", "bombs", "fixers", "shortfall", "colorConc", "rank",
    )
    val RANK_INDEX = NAMES.indexOf("rank")
    val DIM = NAMES.size

    /** All features. */
    val KEEP_ALL = IntArray(DIM) { it }

    /** Structure only — drops the GIH-derived features (deckZ, topZ, bombs) so we can see
     *  whether a deck is better for reasons OTHER than raw card quality. */
    val KEEP_STRUCTURAL = (0 until DIM).filter { NAMES[it] !in setOf("deckZ", "topZ", "bombs") }.toIntArray()

    fun project(x: DoubleArray, keep: IntArray): DoubleArray = DoubleArray(keep.size) { x[keep[it]] }

    fun of(deck: DeckOption, metrics: SetMetrics, meta: (String) -> CardMeta?, rankOrdinal: Double): DoubleArray {
        val spells = deck.spells
        val zs = spells.mapNotNull { it.gihWr }.map { metrics.z(it) ?: 0.0 }
        val deckZ = if (zs.isEmpty()) 0.0 else zs.average()
        val topZ = zs.sortedDescending().take(10).let { if (it.isEmpty()) 0.0 else it.average() }
        val metas = spells.map { meta(it.name) }
        val removal = metas.count { it?.isRemoval == true }.toDouble()
        val creatures = deck.creatures.toDouble()
        val twoDrops = metas.count { it != null && it.isCreature && it.cmc in 1..2 }.toDouble()
        val avgCmc = metas.mapNotNull { it?.cmc }.ifEmpty { listOf(3) }.average()
        val bombs = zs.count { it > 1.0 }.toDouble()
        val fixers = (deck.nonbasicLands.count { meta(it.name)?.isFixing == true } +
            spells.count { meta(it.name)?.isFixing == true }).toDouble()
        // How far the pool fell short of a full 23-spell deck — thin pools win less.
        val shortfall = (23 - spells.size).coerceAtLeast(0).toDouble()
        val pips = HashMap<Char, Int>()
        for (c in spells) for (ch in LaneDetector.colorsOf(c)) pips.merge(ch, 1, Int::plus)
        val totalPips = pips.values.sum().coerceAtLeast(1)
        val colorConc = (pips.values.maxOrNull() ?: 0).toDouble() / totalPips
        return doubleArrayOf(
            1.0, deckZ, topZ, removal, creatures, twoDrops,
            avgCmc, bombs, fixers, shortfall, colorConc, rankOrdinal,
        )
    }
}

/** Maps 17Lands rank strings ("bronze", "gold-3", …) to an ordinal skill scale 0..5. */
object DraftRank {
    private val ORDER = listOf("bronze", "silver", "gold", "platinum", "diamond", "mythic")
    const val DEFAULT = 2.0 // ~gold, the middle of the ladder, for missing ranks

    fun ordinal(rank: String?): Double {
        if (rank.isNullOrBlank()) return DEFAULT
        val tier = rank.trim().lowercase().substringBefore('-').substringBefore(' ')
        val i = ORDER.indexOf(tier)
        return if (i >= 0) i.toDouble() else DEFAULT
    }
}

/**
 * Predicts a deck's match win rate from its features, fit to REAL match outcomes (not GIH).
 * Standardizes inputs with training-set stats so ridge is scale-invariant; the intercept
 * (index 0) is never standardized or penalized.
 */
class DeckStrengthModel(
    private val beta: DoubleArray,
    private val mean: DoubleArray,
    private val std: DoubleArray,
) {
    fun predict(x: DoubleArray): Double {
        var s = beta[0]
        for (i in 1 until beta.size) {
            val z = if (std[i] > 1e-9) (x[i] - mean[i]) / std[i] else 0.0
            s += beta[i] * z
        }
        return s.coerceIn(0.0, 1.0)
    }

    /** Win rate for this deck if drafted by a player of [referenceRank], isolating deck quality. */
    fun strengthAtRank(x: DoubleArray, referenceRank: Double): Double {
        val c = x.copyOf()
        c[DeckFeatures.RANK_INDEX] = referenceRank
        return predict(c)
    }

    fun coefficients(): Map<String, Double> = DeckFeatures.NAMES.indices.associate { DeckFeatures.NAMES[it] to beta[it] }
}

/** Weighted ridge regression via the normal equations, solved with Gaussian elimination. */
object DeckStrengthTrainer {
    fun train(x: List<DoubleArray>, y: DoubleArray, w: DoubleArray, lambda: Double = 5.0): DeckStrengthModel {
        require(x.isNotEmpty()) { "no training samples" }
        val d = x[0].size
        val mean = DoubleArray(d)
        val std = DoubleArray(d).also { it[0] = 1.0 }
        for (i in 1 until d) {
            var m = 0.0
            for (row in x) m += row[i]
            m /= x.size
            var v = 0.0
            for (row in x) v += (row[i] - m) * (row[i] - m)
            mean[i] = m
            std[i] = sqrt(v / x.size).takeIf { it > 1e-9 } ?: 1.0
        }
        fun z(row: DoubleArray): DoubleArray {
            val out = DoubleArray(d)
            out[0] = 1.0
            for (i in 1 until d) out[i] = (row[i] - mean[i]) / std[i]
            return out
        }
        val a = Array(d) { DoubleArray(d) }
        val b = DoubleArray(d)
        for (n in x.indices) {
            val zi = z(x[n]); val wi = w[n]; val yi = y[n]
            for (i in 0 until d) {
                b[i] += wi * yi * zi[i]
                for (j in 0 until d) a[i][j] += wi * zi[i] * zi[j]
            }
        }
        for (i in 1 until d) a[i][i] += lambda // ridge on slopes only, not the intercept
        return DeckStrengthModel(solve(a, b), mean, std)
    }

    private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
        val n = bIn.size
        val a = Array(n) { aIn[it].copyOf() }
        val b = bIn.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            if (pivot != col) { a[col] = a[pivot].also { a[pivot] = a[col] }; b[col] = b[pivot].also { b[pivot] = b[col] } }
            val diag = a[col][col].takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / diag
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        return DoubleArray(n) { b[it] / (a[it][it].takeIf { d -> kotlin.math.abs(d) > 1e-12 } ?: 1e-12) }
    }
}
