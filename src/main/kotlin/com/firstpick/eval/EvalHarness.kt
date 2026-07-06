package com.firstpick.eval

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.COLOR_PAIRS
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.LaneDetector
import com.firstpick.signals.SignalsEngine
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SynergyRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

fun main(args: Array<String>) = runBlocking {
    val path = Path.of(args.getOrElse(0) { error("usage: <draft.csv> [set] [format] [limit]") })
    val set = args.getOrElse(1) { "MKM" }
    val format = args.getOrElse(2) { "PremierDraft" }
    val limit = args.getOrNull(3)?.toIntOrNull() ?: Int.MAX_VALUE

    print("Loading 17Lands ratings + archetypes + Scryfall meta for $set… ")
    val repo = CardRepository().apply { runCatching { load(set, format) } }
    if (!repo.isLoaded) { println("\nNo 17Lands ratings for $set."); return@runBlocking }
    val archRepo = ArchetypeRepository().apply { loadStrengths(set, format) }
    for (p in COLOR_PAIRS) runCatching { archRepo.ensurePair(set, format, p) }
    val metaRepo = CardMetaRepository().apply { runCatching { load(set, repo.cardNames) } }
    val synergyRepo = SynergyRepository().apply { runCatching { load(set) } }
    val synergyOn = System.getProperty("firstpick.synergy") != "off"
    val synergyIndex = if (synergyOn) synergyRepo.index else null
    println("done. (synergy profile: ${if (synergyIndex != null) "${synergyIndex.profile.archetypes.size} archetypes" else "none"})")

    val drafts = LinkedHashMap<String, MutableList<PickRow>>()
    var rows = 0
    var maxPick = 0
    Files.newBufferedReader(path).use { br ->
        val reader = DraftReader(Csv.parse(br.readLine() ?: return@runBlocking))
        if (!reader.valid) { println("Unrecognized dataset header."); return@runBlocking }
        var line = br.readLine()
        while (line != null && rows < limit) {
            reader.parse(Csv.parse(line))?.let {
                drafts.getOrPut(it.draftId) { ArrayList() }.add(it)
                maxPick = maxOf(maxPick, it.pick)
                rows++
            }
            line = br.readLine()
        }
    }
    val picksPerPack = maxPick + 1
    fun sysD(key: String, def: Double) = System.getProperty(key)?.toDoubleOrNull() ?: def
    val base = AdvisorEngine.Config()
    val cfg = base.copy(
        totalPicks = picksPerPack * 3,
        picksPerPack = picksPerPack,
        valuePerZ = sysD("firstpick.valuePerZ", base.valuePerZ),
        unratedZ = sysD("firstpick.unratedZ", base.unratedZ),
        alsaZSlope = sysD("firstpick.alsaZSlope", base.alsaZSlope),
        bombZ = sysD("firstpick.bombZ", base.bombZ),
        bombIwd = sysD("firstpick.bombIwd", base.bombIwd),
        wheelPenaltyPts = sysD("firstpick.wheelPenaltyPts", base.wheelPenaltyPts),
        archWeightBase = sysD("firstpick.archWeightBase", base.archWeightBase),
        archWeightSlope = sysD("firstpick.archWeightSlope", base.archWeightSlope),
        archWeightMax = sysD("firstpick.archWeightMax", base.archWeightMax),
        archWeightRampStart = sysD("firstpick.archWeightRampStart", base.archWeightRampStart),
        glueMult = sysD("firstpick.glueMult", base.glueMult),
        synergyMult = sysD("firstpick.synergyMult", base.synergyMult),
        synergyCapPts = sysD("firstpick.synergyCapPts", base.synergyCapPts),
        themeCapPts = sysD("firstpick.themeCapPts", base.themeCapPts),
        themeFuelCap = sysD("firstpick.themeFuelCap", base.themeFuelCap),
        comboPts = sysD("firstpick.comboPts", base.comboPts),
        comboCapPts = sysD("firstpick.comboCapPts", base.comboCapPts),
        synergyTotalCapPts = sysD("firstpick.synergyTotalCapPts", base.synergyTotalCapPts),
        penaltyRampStart = sysD("firstpick.penaltyRampStart", base.penaltyRampStart),
        penaltyMax = sysD("firstpick.penaltyMax", base.penaltyMax),
        needsRampStart = sysD("firstpick.needsRampStart", base.needsRampStart),
        needsRampSpan = sysD("firstpick.needsRampSpan", base.needsRampSpan),
        dupSpellPts = sysD("firstpick.dupSpellPts", base.dupSpellPts),
        dupCreaturePts = sysD("firstpick.dupCreaturePts", base.dupCreaturePts),
        creatureFloorPts = sysD("firstpick.creatureFloorPts", base.creatureFloorPts),
        creatureFloorTarget = sysD("firstpick.creatureFloorTarget", base.creatureFloorTarget),
    )
    val engine = AdvisorEngine(cfg)
    println("Replaying $rows picks across ${drafts.size} drafts ($picksPerPack picks/pack)")

    val meta: (String) -> CardMeta? = metaRepo::meta
    val pairStrength = archRepo.strengthMap()
    fun buildDeck(pool: List<RankedCard>): DeckOption? =
        DeckBuilder.build(pool, repo.setMetrics, meta, archRepo::archetypeRating, pairStrength, maxOptions = 1, synergy = synergyIndex)
            .firstOrNull()

    // ---- Pass 1: per-draft human pool + agreement diagnostics; assemble estimator training data.
    fun isTest(id: String) = (id.hashCode() and 0x7fffffff) % 5 == 0
    val diag = Metrics()
    val samples = ArrayList<DeckSample>()
    var rankSum = 0.0; var rankN = 0
    for ((id, picks) in drafts) {
        picks.sortWith(compareBy({ it.pack }, { it.pick }))
        val pool = ArrayList<RankedCard>()
        val seen = LinkedHashMap<Pair<Int, Int>, List<RankedCard>>()
        for (row in picks) {
            val pack = row.packCards.map(repo::resolveName)
            seen[(row.pack + 1) to (row.pick + 1)] = pack
            val signals = SignalsEngine.openLanesResolved(seen)
            val lane = LaneDetector.detect(pool, repo.setMetrics, pairStrength, signals)
            val scored = engine.score(pack, pool, row.pack + 1, row.pick + 1, repo.setMetrics, lane, archRepo::archetypeRating, meta, synergyIndex)
            scored.firstOrNull()?.let { top ->
                diag.record(
                    agreeTop1 = top.card.name == row.pickedName,
                    agreeTop3 = scored.take(3).any { it.card.name == row.pickedName },
                    wins = row.wins,
                    ourGih = repo.resolveName(top.card.name).gihWr,
                    humanGih = repo.resolveName(row.pickedName).gihWr,
                    oracleGih = pack.mapNotNull { it.gihWr }.maxOrNull(),
                )
                for (s in scored) s.card.gihWr?.let { diag.calibrate(s.value, it) }
            }
            pool.add(repo.resolveName(row.pickedName))
        }
        val head = picks.first()
        rankSum += DraftRank.ordinal(head.rank); rankN++
        val deck = buildDeck(pool) ?: continue
        val matches = head.wins + head.losses
        samples += DeckSample(
            features = DeckFeatures.of(deck, repo.setMetrics, meta, DraftRank.ordinal(head.rank)),
            winRate = if (matches > 0) head.wins.toDouble() / matches else 0.0,
            matches = matches,
            test = isTest(id),
        )
    }
    val referenceRank = if (rankN > 0) rankSum / rankN else DraftRank.DEFAULT

    // ---- Split into train (fit estimator) and held-out test (report on).
    val train = samples.filter { !it.test && it.matches > 0 }
    val valid = samples.filter { it.test && it.matches > 0 }
    if (train.size < 50 || valid.isEmpty()) {
        println("\nToo few outcome-bearing drafts (train=${train.size}); diagnostics only.")
        diag.print(set, format); return@runBlocking
    }

    // ---- Pass 2: engine self-draft on the TEST split; collect engine vs human deck features.
    val cmp = ArrayList<Pair<DoubleArray, DoubleArray>>()
    fun poolCreatures(pool: List<RankedCard>) = pool.count { meta(it.name)?.isCreature == true }
    var engPoolCr = 0.0; var humPoolCr = 0.0; var poolN = 0
    for ((id, picks) in drafts) {
        if (!isTest(id)) continue
        picks.sortWith(compareBy({ it.pack }, { it.pick }))
        val enginePool = ArrayList<RankedCard>()
        val seen = LinkedHashMap<Pair<Int, Int>, List<RankedCard>>()
        for (row in picks) {
            val pack = row.packCards.map(repo::resolveName)
            seen[(row.pack + 1) to (row.pick + 1)] = pack
            val signals = SignalsEngine.openLanesResolved(seen)
            val lane = LaneDetector.detect(enginePool, repo.setMetrics, pairStrength, signals)
            val scored = engine.score(pack, enginePool, row.pack + 1, row.pick + 1, repo.setMetrics, lane, archRepo::archetypeRating, meta, synergyIndex)
            enginePool.add(scored.firstOrNull()?.card ?: pack.first())
        }
        val humanPool = picks.map { repo.resolveName(it.pickedName) }
        val engDeck = buildDeck(enginePool) ?: continue
        val humDeck = buildDeck(humanPool) ?: continue
        engPoolCr += poolCreatures(enginePool); humPoolCr += poolCreatures(humanPool); poolN++
        cmp += DeckFeatures.of(engDeck, repo.setMetrics, meta, referenceRank) to
            DeckFeatures.of(humDeck, repo.setMetrics, meta, referenceRank)
    }
    if (poolN > 0) println("[pool creatures — engine %.2f vs human %.2f]".format(engPoolCr / poolN, humPoolCr / poolN))

    // ---- Report: honest headline first, GIH agreement demoted to diagnostics.
    fun wr(x: Double) = "%.1f%%".format(x * 100)
    println("\n=== HONEST EVAL — $set $format (held-out ${samples.count { it.test }}/${samples.size} drafts, ${cmp.size} self-drafted) ===")
    println("Estimators trained on REAL match outcomes (rank-controlled, held-out validation):")

    fun variant(label: String, keep: IntArray, interactions: Boolean = false) {
        fun feat(f: DoubleArray): DoubleArray {
            val p = DeckFeatures.project(f, keep)
            return if (interactions) DeckFeatures.expandInteractions(p) else p
        }
        val model = DeckStrengthTrainer.train(
            train.map { feat(it.features) },
            DoubleArray(train.size) { train[it].winRate },
            DoubleArray(train.size) { train[it].matches.toDouble() },
        )
        val actual = DoubleArray(valid.size) { valid[it].winRate }
        val w = DoubleArray(valid.size) { valid[it].matches.toDouble() }
        val preds = DoubleArray(valid.size) { model.predict(feat(valid[it].features)) }
        val corr = weightedCorr(preds, actual, w)
        val rmseM = weightedRmse(preds, actual, w)
        val rmseB = weightedRmse(DoubleArray(valid.size) { weightedMean(actual, w) }, actual, w)
        var e = 0.0; var h = 0.0; var wins = 0
        for ((ef, hf) in cmp) {
            val ep = model.predict(feat(ef))
            val hp = model.predict(feat(hf))
            e += ep; h += hp; if (ep >= hp) wins++
        }
        val n = cmp.size.coerceAtLeast(1)
        val uplift = "%+.2f".format((e - h) / n * 100)
        val geq = "%.0f%%".format(wins.toDouble() / n * 100)
        val names = keep.map { DeckFeatures.NAMES[it] }
        val beta = model.beta()
        val coefs = names.indices.filter { names[it] != "intercept" }
            .sortedByDescending { kotlin.math.abs(beta[it]) }.take(5).joinToString(", ") { "%s %+.3f".format(names[it], beta[it]) }
        println("• $label")
        println("    validity: corr=%.3f, RMSE %.3f vs baseline %.3f (%+.1f%% err reduction)".format(corr, rmseM, rmseB, if (rmseB > 0) (1 - rmseM / rmseB) * 100 else 0.0))
        println("    top drivers: $coefs")
        println("    engine deck ${wr(e / n)} vs human ${wr(h / n)}  → uplift $uplift pts, engine≥human $geq")
    }

    variant("card-quality + structure (all features)", DeckFeatures.KEEP_ALL)
    variant("structure only (curve/colors/roles, GIH removed)", DeckFeatures.KEEP_STRUCTURAL)
    variant("all features + interactions (quadratic)", DeckFeatures.KEEP_ALL, interactions = true)
    variant("structure only + interactions (quadratic)", DeckFeatures.KEEP_STRUCTURAL, interactions = true)

    println("Structural profile — where the engine's pools diverge from humans (means):")
    for (name in listOf("creatures", "removal", "twoDrops", "avgCmc", "topEnd", "colorCount", "colorConc", "minColorShare", "curveDev", "shortfall")) {
        val i = DeckFeatures.NAMES.indexOf(name)
        val e = cmp.map { it.first[i] }.average()
        val h = cmp.map { it.second[i] }.average()
        println("    %-14s engine %.2f  human %.2f  (%+.2f)".format(name, e, h, e - h))
    }

    println("\n--- diagnostics (GIH-based; circular vs 17Lands, not ground truth) ---")
    diag.print(set, format)
}

private class DeckSample(val features: DoubleArray, val winRate: Double, val matches: Int, val test: Boolean)

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

private class Metrics {
    var n = 0; var top1 = 0; var top3 = 0
    var winN = 0; var winTop1 = 0; var loseN = 0; var loseTop1 = 0
    var gihN = 0; var our = 0.0; var human = 0.0; var oracle = 0.0
    private val calibCount = HashMap<Int, Int>()
    private val calibGih = HashMap<Int, Double>()

    fun record(agreeTop1: Boolean, agreeTop3: Boolean, wins: Int, ourGih: Double?, humanGih: Double?, oracleGih: Double?) {
        n++
        if (agreeTop1) top1++
        if (agreeTop3) top3++
        if (wins >= 5) { winN++; if (agreeTop1) winTop1++ } else if (wins <= 2) { loseN++; if (agreeTop1) loseTop1++ }
        if (ourGih != null && humanGih != null && oracleGih != null) {
            gihN++; our += ourGih; human += humanGih; oracle += oracleGih
        }
    }

    fun calibrate(value: Double, gih: Double) {
        val bucket = (value / 10).toInt() * 10
        calibCount.merge(bucket, 1, Int::plus)
        calibGih.merge(bucket, gih, Double::plus)
    }

    fun print(set: String, format: String) {
        fun rate(a: Int, b: Int) = if (b > 0) a.toDouble() / b else 0.0
        fun pct(a: Int, b: Int) = "%.1f%% (%d/%d)".format(rate(a, b) * 100, a, b)
        fun wr(x: Double) = "%.1f%%".format(x * 100)
        println("picks evaluated: $n")
        println("Top-1 agreement (our #1 = drafter's pick): ${pct(top1, n)}")
        println("Top-3 agreement (their pick in our top 3): ${pct(top3, n)}")
        println("  winner-minus-loser agreement: %+.1f pts".format((rate(winTop1, winN) - rate(loseTop1, loseN)) * 100))
        if (gihN > 0) {
            println("Mean GIH WR — oracle: ${wr(oracle / gihN)}, our #1: ${wr(our / gihN)}, drafter: ${wr(human / gihN)}")
            println("  regret vs GIH oracle — ours: %.2f, drafter: %.2f".format((oracle - our) / gihN * 100, (oracle - human) / gihN * 100))
        }
    }
}
