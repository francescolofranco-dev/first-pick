package com.firstpick.eval

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.COLOR_PAIRS
import com.firstpick.advisor.LaneDetector
import com.firstpick.signals.SignalsEngine
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.RankedCard
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Backtests the advisor against a real 17Lands draft dataset: replays every pick a
 * real drafter faced, runs our engine, and measures how our recommendation compares
 * to what they took — split by whether they won. See docs/eval-harness.md.
 *
 *   ./gradlew evalHarness -Pdata=/tmp/mkm_sample.csv -Pset=MKM [-Plimit=90000]
 */
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
    println("done.")

    // Read rows, grouped by draft (the dataset is contiguous per draft_id).
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
    // Constants overridable via -Dfirstpick.* so the tuning loop can A/B without recompiling.
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
        penaltyRampStart = sysD("firstpick.penaltyRampStart", base.penaltyRampStart),
        penaltyMax = sysD("firstpick.penaltyMax", base.penaltyMax),
        needsRampStart = sysD("firstpick.needsRampStart", base.needsRampStart),
        needsRampSpan = sysD("firstpick.needsRampSpan", base.needsRampSpan),
    )
    val engine = AdvisorEngine(cfg)
    println("Replaying $rows picks across ${drafts.size} drafts ($picksPerPack picks/pack); penaltyMax=${cfg.penaltyMax} needsRampStart=${cfg.needsRampStart} synergyCap=${cfg.synergyCapPts}")

    val m = Metrics()
    for ((_, picks) in drafts) {
        picks.sortWith(compareBy({ it.pack }, { it.pick }))
        val pool = ArrayList<RankedCard>()
        val seen = LinkedHashMap<Pair<Int, Int>, List<RankedCard>>()
        for (row in picks) {
            val pack = row.packCards.map(repo::resolveName)
            // Include the current pack in the signal (a strong card still here is the
            // freshest "this color is flowing to me" read), mirroring the live app where
            // DraftState.seen already contains the current pack at scoring time.
            seen[(row.pack + 1) to (row.pick + 1)] = pack
            val signals = SignalsEngine.openLanesResolved(seen)
            val lane = LaneDetector.detect(pool, repo.setMetrics, archRepo.strengthMap(), signals)
            val scored = engine.score(pack, pool, row.pack + 1, row.pick + 1, repo.setMetrics, lane, archRepo::archetypeRating, metaRepo::meta)
            scored.firstOrNull()?.let { topPick ->
                m.record(
                    agreeTop1 = topPick.card.name == row.pickedName,
                    agreeTop3 = scored.take(3).any { it.card.name == row.pickedName },
                    wins = row.wins,
                    ourGih = repo.resolveName(topPick.card.name).gihWr,
                    humanGih = repo.resolveName(row.pickedName).gihWr,
                    oracleGih = pack.mapNotNull { it.gihWr }.maxOrNull(),
                )
                for (s in scored) s.card.gihWr?.let { m.calibrate(s.value, it) }
            }
            pool.add(repo.resolveName(row.pickedName))
        }
    }
    m.print(set, format)
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
        println("\n=== FirstPick advisor backtest — $set $format ===")
        println("picks evaluated: $n")
        println("Top-1 agreement (our #1 = drafter's pick): ${pct(top1, n)}")
        println("Top-3 agreement (their pick in our top 3): ${pct(top3, n)}")
        println("  winners (>=5 wins): ${pct(winTop1, winN)}")
        println("  losers  (<=2 wins): ${pct(loseTop1, loseN)}")
        println("  winner-minus-loser agreement: %+.1f pts  (>0 = we align with winning play)".format((rate(winTop1, winN) - rate(loseTop1, loseN)) * 100))
        if (gihN > 0) {
            println("Mean GIH WR — oracle(best in pack): ${wr(oracle / gihN)}, our #1: ${wr(our / gihN)}, drafter: ${wr(human / gihN)}")
            println("  regret vs oracle — ours: %.2f pts, drafter: %.2f pts".format((oracle - our) / gihN * 100, (oracle - human) / gihN * 100))
        }
        println("VALUE calibration (bucket → avg actual GIH WR, should rise):")
        for (b in calibCount.keys.sorted()) {
            println("  %3d–%-3d: %s  (n=%d)".format(b, b + 9, wr(calibGih[b]!! / calibCount[b]!!), calibCount[b]))
        }
    }
}
