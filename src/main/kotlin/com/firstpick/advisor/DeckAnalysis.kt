package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole
import kotlin.math.abs

enum class DeckPace(val label: String) {
    AGGRO("Aggro"),
    TEMPO("Tempo"),
    MIDRANGE("Midrange"),
    CONTROL("Control"),
    RAMP("Ramp"),
}

data class DeckIdentity(
    val pace: DeckPace,
    val confidence: Double,
    val reasons: List<String>,
) {
    val confidenceLabel: String
        get() = confidenceLabel(confidence)
}

data class DeckPowerAssessment(
    val score: Double,
    val qualityRate: Double,
    val confidence: Double,
    val reasons: List<String>,
) {
    val confidenceLabel: String
        get() = confidenceLabel(confidence)
}

object DeckAnalysis {
    private data class Features(
        val spellCount: Int,
        val knownMeta: Int,
        val creatures: Int,
        val earlyCreatures: Int,
        val removal: Int,
        val cheapInteraction: Int,
        val draw: Int,
        val evasion: Int,
        val finishers: Int,
        val fixers: Int,
        val topEnd: Int,
        val avgCmc: Double,
        val curve: IntArray,
    )

    private data class Adjustment(val points: Double, val positive: String, val negative: String = positive)

    fun identity(
        spells: List<RankedCard>,
        pair: String,
        meta: (String) -> CardMeta?,
        synergy: SynergyIndex?,
        landFixers: Int = 0,
    ): DeckIdentity {
        val f = features(spells, meta, landFixers)
        val archetype = synergy?.archetype(pair)
        val prior = archetype?.let { paceFrom(it.speed) ?: paceFrom(it.name) }
        val scores = linkedMapOf(
            DeckPace.AGGRO to (
                f.earlyCreatures * 0.85 + (3.0 - f.avgCmc).coerceAtLeast(0.0) * 3.0 +
                    (f.creatures - 14).coerceAtLeast(0) * 0.30 + f.evasion * 0.15 - f.topEnd * 0.35
                ),
            DeckPace.TEMPO to (
                f.earlyCreatures * 0.35 + f.evasion * 0.60 + f.cheapInteraction * 0.55 +
                    (3.2 - f.avgCmc).coerceAtLeast(0.0) * 1.5 + if (f.creatures in 11..16) 1.0 else 0.0
                ),
            DeckPace.MIDRANGE to (
                4.0 + (if (f.creatures in 13..17) 1.0 else 0.0) +
                    (if (f.avgCmc in 2.9..3.5) 1.0 else 0.0) + f.removal.coerceAtMost(4) * 0.25
                ),
            DeckPace.CONTROL to (
                f.removal * 0.55 + f.draw * 0.75 + f.topEnd * 0.35 +
                    (f.avgCmc - 3.2).coerceAtLeast(0.0) * 2.0 +
                    (if (f.creatures <= 12) 1.0 else 0.0) - (f.earlyCreatures - 4).coerceAtLeast(0) * 0.35
                ),
            DeckPace.RAMP to if (f.fixers >= 2 && f.topEnd >= 3) {
                f.fixers * 0.65 + f.topEnd * 0.60 + f.finishers * 0.35 +
                    (f.avgCmc - 3.2).coerceAtLeast(0.0) * 2.0
            } else {
                1.0 + f.fixers * 0.15 + f.topEnd * 0.10
            },
        )
        if (prior != null) scores[prior] = scores.getValue(prior) + 2.5

        val ranked = scores.entries.sortedByDescending { it.value }
        val pace = ranked.first().key
        val margin = ranked.first().value - ranked.getOrElse(1) { ranked.first() }.value
        val coverage = if (f.spellCount == 0) 0.0 else f.knownMeta.toDouble() / f.spellCount
        val confidence = (
            0.38 + (margin / 7.0).coerceIn(0.0, 0.32) + coverage * 0.20 +
                if (prior == pace) 0.05 else 0.0
            ).coerceIn(0.35, 0.95)

        val reasons = buildList {
            when (pace) {
                DeckPace.AGGRO -> {
                    add("${f.earlyCreatures} cheap creatures")
                    if (f.avgCmc < 3.0) add("${oneDecimal(f.avgCmc)} average mana value")
                    if (f.creatures >= 15) add("${f.creatures} creatures")
                }
                DeckPace.TEMPO -> {
                    if (f.evasion > 0) add("${f.evasion} evasive threats")
                    if (f.cheapInteraction > 0) add("${f.cheapInteraction} cheap interaction")
                    add("${f.earlyCreatures} cheap creatures")
                }
                DeckPace.MIDRANGE -> {
                    add("Balanced ${oneDecimal(f.avgCmc)} mana curve")
                    add("${f.creatures} creatures and ${f.removal} removal")
                }
                DeckPace.CONTROL -> {
                    add("${f.removal} removal and ${f.draw} card draw")
                    if (f.topEnd > 0) add("${f.topEnd} top-end spells")
                    add("${f.creatures} creatures")
                }
                DeckPace.RAMP -> {
                    add("${f.fixers} mana sources or fixers")
                    add("${f.topEnd} top-end spells")
                    if (f.finishers > 0) add("${f.finishers} finishers")
                }
            }
            if (prior == pace) archetype?.let { add("${it.name} set archetype") }
        }.distinct().take(3)

        return DeckIdentity(pace, confidence, reasons)
    }

    fun power(
        spells: List<RankedCard>,
        pair: String,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        archetypeRating: (String, String) -> CardRating?,
        pairStrength: Double?,
        identity: DeckIdentity,
        synergy: SynergyIndex?,
        landFixers: Int,
        splashCount: Int,
    ): DeckPowerAssessment {
        val f = features(spells, meta, landFixers)
        val estimates = spells.map { cardQuality(it.rating, archetypeRating(it.name, pair), metrics) }
        val zs = estimates.map { metrics.z(it) ?: 0.0 }
        val meanZ = zs.ifEmpty { listOf(0.0) }.average()
        val topZ = zs.sortedDescending().take(8).ifEmpty { listOf(0.0) }.average()
        val floorZ = zs.sorted().take(5).ifEmpty { listOf(0.0) }.average()
        val qualityZ = 0.72 * meanZ + 0.18 * topZ + 0.10 * floorZ

        val adjustments = ArrayList<Adjustment>()
        pairStrength?.let {
            val points = ((it - metrics.meanGihWr) * 60.0).coerceIn(-3.0, 3.0)
            if (abs(points) >= 0.25) adjustments += Adjustment(points, "Strong color-pair baseline", "Weak color-pair baseline")
        }

        val curveDeviation = curveDeviation(f.curve, identity.pace)
        val curvePoints = when {
            curveDeviation <= 0.20 -> 2.0
            curveDeviation <= 0.34 -> 1.0
            curveDeviation >= 0.58 -> -4.0
            curveDeviation >= 0.45 -> -2.0
            else -> 0.0
        }
        if (curvePoints != 0.0) adjustments += Adjustment(curvePoints, "Curve fits ${identity.pace.label.lowercase()} plan", "Curve misses ${identity.pace.label.lowercase()} targets")

        when (identity.pace) {
            DeckPace.AGGRO -> {
                val early = ((f.earlyCreatures - 5) * 0.8).coerceIn(-3.2, 2.4)
                if (early != 0.0) adjustments += Adjustment(early, "Deep early pressure", "Too few early threats")
                val topEnd = -((f.topEnd - 3).coerceAtLeast(0) * 0.7).coerceAtMost(2.8)
                if (topEnd != 0.0) adjustments += Adjustment(topEnd, "Lean top end", "Too much top end for aggro")
            }
            DeckPace.TEMPO -> {
                val tools = ((f.evasion + f.cheapInteraction - 6) * 0.45).coerceIn(-2.7, 2.7)
                if (tools != 0.0) adjustments += Adjustment(tools, "Threat-and-interaction density", "Not enough tempo tools")
            }
            DeckPace.MIDRANGE -> {
                val balance = if (f.creatures in 13..17 && f.removal >= 2) 2.0 else -1.0
                adjustments += Adjustment(balance, "Balanced threats and answers", "Midrange role balance is thin")
            }
            DeckPace.CONTROL -> {
                val answers = ((f.removal + f.draw - 7) * 0.45).coerceIn(-3.15, 3.15)
                if (answers != 0.0) adjustments += Adjustment(answers, "Deep answers and card advantage", "Control tools are thin")
            }
            DeckPace.RAMP -> {
                val ramp = ((f.fixers + f.topEnd - 8) * 0.45).coerceIn(-3.15, 3.15)
                if (ramp != 0.0) adjustments += Adjustment(ramp, "Ramp and payoff balance", "Ramp or payoffs are missing")
            }
        }

        if (f.spellCount < 23) {
            val missing = 23 - f.spellCount
            adjustments += Adjustment(-missing * 3.0, "Complete spell suite", "$missing spell slots short")
        }

        if (splashCount > 0) {
            val unsupported = (splashCount - f.fixers).coerceAtLeast(0)
            if (unsupported > 0) adjustments += Adjustment(-unsupported * 4.0, "Supported splash", "$unsupported splash cards lack fixing")
            val excess = (f.fixers - splashCount).coerceAtLeast(0)
            if (excess > 0) adjustments += Adjustment(excess.coerceAtMost(3).toDouble(), "Splash has reliable fixing")
        }

        synergy?.let { index ->
            var enablers = 0.0
            var payoffs = 0.0
            var signposts = 0
            for (card in spells) {
                val tag = index.tags(card.name).firstOrNull { it.pair == pair } ?: continue
                when (tag.role) {
                    SynergyRole.SIGNPOST -> { signposts++; enablers += 0.75; payoffs += 0.75 }
                    SynergyRole.PAYOFF -> payoffs += 1.0
                    SynergyRole.ENABLER -> enablers += 1.0
                    SynergyRole.KEY -> Unit
                }
            }
            val points = (minOf(enablers, payoffs) * 0.55 + signposts * 0.45).coerceAtMost(3.0)
            if (points >= 0.75) adjustments += Adjustment(points, "Enablers and payoffs reinforce the theme")
        }

        val structural = adjustments.sumOf { it.points }.coerceIn(-15.0, 12.0)
        val score = (50.0 + 17.0 * qualityZ + structural).coerceIn(0.0, 100.0)
        val metaCoverage = if (spells.isEmpty()) 0.0 else f.knownMeta.toDouble() / spells.size
        val pairCoverage = spells.map {
            val rating = archetypeRating(it.name, pair)
            if (rating?.gihWr == null) 0.0 else evidenceWeight(rating.everDrawnGameCount, 1_200.0)
        }.ifEmpty { listOf(0.0) }.average()
        val ratingEvidence = spells.map { ratingEvidence(it.rating) }.ifEmpty { listOf(0.0) }.average()
        val confidence = (0.20 + 0.35 * ratingEvidence + 0.25 * metaCoverage + 0.20 * pairCoverage).coerceIn(0.25, 0.95)

        val reasons = buildList {
            add(
                when {
                    qualityZ >= 0.65 -> "Card quality is well above the set average"
                    qualityZ >= 0.15 -> "Card quality is above the set average"
                    qualityZ <= -0.65 -> "Card quality is well below the set average"
                    qualityZ <= -0.15 -> "Card quality is below the set average"
                    else -> "Card quality is near the set average"
                },
            )
            if (topZ - meanZ >= 0.55) add("Best cards raise the deck's ceiling")
            if (floorZ <= -0.70) add("Weakest cards lower the deck's floor")
            adjustments.sortedByDescending { abs(it.points) }
                .filter { abs(it.points) >= 0.75 }
                .forEach { add(if (it.points >= 0.0) it.positive else it.negative) }
        }.distinct().take(4)

        return DeckPowerAssessment(score, estimates.ifEmpty { listOf(metrics.meanGihWr) }.average(), confidence, reasons)
    }

    fun outlook(power: DeckPowerAssessment): String {
        val label = when {
            power.score >= 76 -> "Excellent build"
            power.score >= 66 -> "Strong build"
            power.score >= 56 -> "Above-average build"
            power.score >= 46 -> "Average build"
            power.score >= 36 -> "Fragile build"
            else -> "Risky build"
        }
        return "$label · ${power.confidenceLabel.lowercase()} confidence"
    }

    private fun features(spells: List<RankedCard>, meta: (String) -> CardMeta?, landFixers: Int): Features {
        val metas = spells.map { meta(it.name) }
        val known = metas.filterNotNull()
        val cmcs = known.filterNot { it.isLand }.map { it.cmc }
        val curve = IntArray(6)
        cmcs.forEach { curve[it.coerceIn(1, 6) - 1]++ }
        return Features(
            spellCount = spells.size,
            knownMeta = known.size,
            creatures = known.count { it.isCreature },
            earlyCreatures = known.count { it.isCreature && it.cmc in 1..2 },
            removal = known.count { it.isRemoval },
            cheapInteraction = known.count { it.isRemoval && it.cmc <= 3 },
            draw = known.count { it.isCardDraw },
            evasion = known.count { it.isEvasion },
            finishers = known.count { it.isFinisher },
            fixers = known.count { it.isFixing } + landFixers,
            topEnd = known.count { it.cmc >= 5 },
            avgCmc = cmcs.ifEmpty { listOf(3) }.average(),
            curve = curve,
        )
    }

    fun cardQuality(global: CardRating?, pair: CardRating?, metrics: SetMetrics): Double {
        val globalWr = global?.gihWr ?: metrics.meanGihWr
        val globalWeight = evidenceWeight(global?.everDrawnGameCount ?: 0, 400.0)
        val shrunkGlobal = metrics.meanGihWr + (globalWr - metrics.meanGihWr) * globalWeight
        val pairWr = pair?.gihWr ?: return shrunkGlobal
        val pairWeight = evidenceWeight(pair.everDrawnGameCount, 1_200.0)
        return shrunkGlobal + (pairWr - shrunkGlobal) * pairWeight
    }

    private fun ratingEvidence(rating: CardRating?): Double = evidenceWeight(rating?.everDrawnGameCount ?: 0, 800.0)

    private fun evidenceWeight(games: Int, priorGames: Double): Double = games.coerceAtLeast(0) / (games.coerceAtLeast(0) + priorGames)

    private fun curveDeviation(curve: IntArray, pace: DeckPace): Double {
        val template = when (pace) {
            DeckPace.AGGRO -> doubleArrayOf(2.0, 7.0, 6.0, 4.0, 2.0, 1.0)
            DeckPace.TEMPO -> doubleArrayOf(1.0, 6.0, 6.0, 4.0, 3.0, 2.0)
            DeckPace.MIDRANGE -> doubleArrayOf(1.0, 5.0, 6.0, 5.0, 3.0, 3.0)
            DeckPace.CONTROL -> doubleArrayOf(1.0, 4.0, 5.0, 4.0, 4.0, 5.0)
            DeckPace.RAMP -> doubleArrayOf(1.0, 4.0, 5.0, 4.0, 4.0, 5.0)
        }
        val actualTotal = curve.sum().coerceAtLeast(1).toDouble()
        val templateTotal = template.sum()
        return curve.indices.sumOf { abs(curve[it] / actualTotal - template[it] / templateTotal) }
    }

    private fun paceFrom(value: String): DeckPace? {
        val normalized = value.lowercase()
        return when {
            "control" in normalized -> DeckPace.CONTROL
            "ramp" in normalized -> DeckPace.RAMP
            "tempo" in normalized -> DeckPace.TEMPO
            "aggro" in normalized -> DeckPace.AGGRO
            "midrange" in normalized -> DeckPace.MIDRANGE
            else -> null
        }
    }

    private fun oneDecimal(value: Double): String = "%.1f".format(value)
}

private fun confidenceLabel(confidence: Double): String = when {
    confidence >= 0.75 -> "High"
    confidence >= 0.55 -> "Medium"
    else -> "Low"
}
