package com.firstpick.eval

import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.LaneDetector
import com.firstpick.cards.CardMeta
import com.firstpick.cards.SetMetrics
import kotlin.math.sqrt


object DeckFeatures {
    val NAMES = listOf(
        "intercept", "deckZ", "topZ", "removal", "creatures", "twoDrops",
        "avgCmc", "bombs", "fixers", "shortfall", "colorConc",
        "curveDev", "colorCount", "minColorShare", "topEnd", "rank",
    )
    val RANK_INDEX = NAMES.indexOf("rank")
    val DIM = NAMES.size


    val KEEP_ALL = IntArray(DIM) { it }


    val KEEP_STRUCTURAL = (0 until DIM).filter { NAMES[it] !in setOf("deckZ", "topZ", "bombs") }.toIntArray()

    fun project(x: DoubleArray, keep: IntArray): DoubleArray = DoubleArray(keep.size) { x[keep[it]] }


    fun expandInteractions(x: DoubleArray): DoubleArray {
        val extra = ArrayList<Double>()
        for (i in 1 until x.size) for (j in i until x.size) extra.add(x[i] * x[j])
        return DoubleArray(x.size + extra.size) { if (it < x.size) x[it] else extra[it - x.size] }
    }


    private val CURVE_TEMPLATE = doubleArrayOf(1.0, 6.0, 6.0, 4.0, 3.0, 3.0)

    fun of(deck: DeckOption, metrics: SetMetrics, meta: (String) -> CardMeta?, rankOrdinal: Double): DoubleArray {
        val spells = deck.spells
        val metas = spells.map { meta(it.name) }
        val zs = spells.mapNotNull { it.gihWr }.map { metrics.z(it) ?: 0.0 }
        val deckZ = if (zs.isEmpty()) 0.0 else zs.average()
        val topZ = zs.sortedDescending().take(10).let { if (it.isEmpty()) 0.0 else it.average() }
        val removal = metas.count { it?.isRemoval == true }.toDouble()
        val creatures = deck.creatures.toDouble()
        val twoDrops = metas.count { it != null && it.isCreature && it.cmc in 1..2 }.toDouble()
        val cmcs = metas.mapNotNull { it?.cmc }
        val avgCmc = cmcs.ifEmpty { listOf(3) }.average()
        val bombs = zs.count { it > 1.0 }.toDouble()
        val fixers = (deck.nonbasicLands.count { meta(it.name)?.isFixing == true } +
            spells.count { meta(it.name)?.isFixing == true }).toDouble()

        val shortfall = (23 - spells.size).coerceAtLeast(0).toDouble()


        val pips = HashMap<Char, Int>()
        for (c in spells) for (ch in LaneDetector.colorsOf(c)) pips.merge(ch, 1, Int::plus)
        val totalPips = pips.values.sum().coerceAtLeast(1)
        val colorConc = (pips.values.maxOrNull() ?: 0).toDouble() / totalPips
        val minColorShare = if (pips.isEmpty()) 0.0 else pips.values.min().toDouble() / totalPips
        val colorCount = pips.count { it.value.toDouble() / totalPips >= 0.10 }.toDouble()


        val hist = IntArray(6)
        for (c in cmcs) hist[c.coerceIn(1, 6) - 1]++
        val n = cmcs.size.coerceAtLeast(1)
        val templSum = CURVE_TEMPLATE.sum()
        var curveDev = 0.0
        for (i in 0 until 6) curveDev += kotlin.math.abs(hist[i].toDouble() / n - CURVE_TEMPLATE[i] / templSum)
        val topEnd = cmcs.count { it >= 5 }.toDouble()

        return doubleArrayOf(
            1.0, deckZ, topZ, removal, creatures, twoDrops,
            avgCmc, bombs, fixers, shortfall, colorConc,
            curveDev, colorCount, minColorShare, topEnd, rankOrdinal,
        )
    }
}


object DraftRank {
    private val ORDER = listOf("bronze", "silver", "gold", "platinum", "diamond", "mythic")
    const val DEFAULT = 2.0

    fun ordinal(rank: String?): Double {
        if (rank.isNullOrBlank()) return DEFAULT
        val tier = rank.trim().lowercase().substringBefore('-').substringBefore(' ')
        val i = ORDER.indexOf(tier)
        return if (i >= 0) i.toDouble() else DEFAULT
    }
}


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


    fun strengthAtRank(x: DoubleArray, referenceRank: Double): Double {
        val c = x.copyOf()
        c[DeckFeatures.RANK_INDEX] = referenceRank
        return predict(c)
    }

    fun beta(): DoubleArray = beta.copyOf()
}


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
        for (i in 1 until d) a[i][i] += lambda
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
