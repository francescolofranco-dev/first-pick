package com.firstpick.tools

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRating
import com.firstpick.cards.CardRepository
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt


fun main(args: Array<String>) = runBlocking {
    val set = args.getOrElse(0) { "FIN" }
    val format = args.getOrElse(1) { "PremierDraft" }

    print("Loading $set ratings + meta… ")
    val repo = CardRepository().apply { runCatching { load(set, format) } }
    if (!repo.isLoaded) { println("no 17Lands ratings for $set."); return@runBlocking }
    val metaRepo = CardMetaRepository().apply { runCatching { load(set, repo.cardNames) } }
    println("done.")

    data class Card(val name: String, val z: Double, val games: Int, val feat: DoubleArray)
    val raw = ArrayList<Card>()
    for (name in repo.cardNames.orEmpty()) {
        val rc = repo.resolveName(name)
        val gih = rc.gihWr ?: continue
        val games = rc.rating?.everDrawnGameCount ?: 0
        if (games < 200) continue
        val m = metaRepo.meta(name) ?: continue
        val z = repo.setMetrics.z(gih) ?: continue
        raw.add(Card(name, z, games, features(m, rc.rating)))
    }
    if (raw.size < 50) { println("Too few rated cards (${raw.size})."); return@runBlocking }


    val dim = raw[0].feat.size
    val mean = DoubleArray(dim); val std = DoubleArray(dim)
    for (d in 0 until dim) {
        val m = raw.sumOf { it.feat[d] } / raw.size
        val v = raw.sumOf { (it.feat[d] - m) * (it.feat[d] - m) } / raw.size
        mean[d] = m; std[d] = sqrt(v).takeIf { it > 1e-9 } ?: 1.0
    }
    val norm = raw.map { c -> DoubleArray(dim) { (c.feat[it] - mean[it]) / std[it] } }

    fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s
    }

    println("Leave-one-out k-NN imputation of GIH z-score from functional features (n=${raw.size}):")
    for (k in listOf(5, 10, 20)) {
        val pred = DoubleArray(raw.size)
        for (i in raw.indices) {
            val nearest = raw.indices.filter { it != i }
                .map { j -> j to dist(norm[i], norm[j]) }
                .sortedBy { it.second }.take(k)
            pred[i] = nearest.map { raw[it.first].z }.average()
        }
        val actual = DoubleArray(raw.size) { raw[it].z }
        val w = DoubleArray(raw.size) { raw[it].games.toDouble() }
        val corr = weightedCorr(pred, actual, w)
        val rmseM = weightedRmse(pred, actual, w)
        val rmseB = weightedRmse(DoubleArray(raw.size) { weightedMean(actual, w) }, actual, w)
        println("  k=%2d  corr=%.3f  RMSE %.3f vs baseline %.3f (%+.1f%% err reduction)".format(
            k, corr, rmseM, rmseB, if (rmseB > 0) (1 - rmseM / rmseB) * 100 else 0.0))
    }


    val rar = DoubleArray(raw.size) { raw[it].feat[RARITY_IDX] }
    val actual = DoubleArray(raw.size) { raw[it].z }
    val w = DoubleArray(raw.size) { raw[it].games.toDouble() }
    println("  rarity-only corr with GIH z: %.3f".format(weightedCorr(rar, actual, w)))
}

private const val RARITY_IDX = 8

private fun features(m: CardMeta, rating: CardRating?): DoubleArray {
    val rarity = when (rating?.rarity?.lowercase()) {
        "common" -> 0.0; "uncommon" -> 1.0; "rare" -> 2.0; "mythic" -> 3.0; else -> 1.0
    }
    val colors = rating?.color.orEmpty().count { it in "WUBRG" }.toDouble()
    return doubleArrayOf(
        m.cmc.toDouble(),
        if (m.isCreature) 1.0 else 0.0,
        if (m.isLand) 1.0 else 0.0,
        if (m.isRemoval) 1.0 else 0.0,
        if (m.isFixing) 1.0 else 0.0,
        if (m.isFinisher) 1.0 else 0.0,
        if (m.isEvasion) 1.0 else 0.0,
        if (m.isCardDraw) 1.0 else 0.0,
        rarity,
        colors,
    )
}

private fun weightedMean(x: DoubleArray, w: DoubleArray): Double {
    val sw = w.sum(); if (sw <= 0) return 0.0
    var s = 0.0; for (i in x.indices) s += w[i] * x[i]; return s / sw
}

private fun weightedRmse(pred: DoubleArray, actual: DoubleArray, w: DoubleArray): Double {
    val sw = w.sum(); if (sw <= 0) return 0.0
    var s = 0.0; for (i in pred.indices) s += w[i] * (pred[i] - actual[i]) * (pred[i] - actual[i])
    return sqrt(s / sw)
}

private fun weightedCorr(a: DoubleArray, b: DoubleArray, w: DoubleArray): Double {
    val ma = weightedMean(a, w); val mb = weightedMean(b, w)
    var cov = 0.0; var va = 0.0; var vb = 0.0
    for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; cov += w[i] * da * db; va += w[i] * da * da; vb += w[i] * db * db }
    return if (va > 1e-12 && vb > 1e-12) cov / sqrt(va * vb) else 0.0
}
