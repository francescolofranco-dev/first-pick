package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics

class AdvisorEngine(
    private val config: Config = Config(),
) {
    data class Config(
        val unratedZ: Double = -0.8,
        val bombZ: Double = 1.0,
        val bombIwd: Double = 0.045,
        val bombZNoIwd: Double = 1.6,
        val bombUnsplashablePenaltyScale: Double = 0.5,
        val wheelAlsa: Double = 7.0,
        val wheelPenaltyPts: Double = 1.5,
        val totalPicks: Int = 45,
        val picksPerPack: Int = 15,
        val valueMidpoint: Double = 50.0,
        val valuePerZ: Double = 16.0,
        val alsaPivot: Double = 5.0,
        val alsaZSlope: Double = 0.15,
        val alsaZMin: Double = -1.2,
        val alsaZMax: Double = 0.8,
        val minArchSamples: Int = 200,
        val archWeightBase: Double = 0.0,
        val archWeightSlope: Double = 1.0,
        val archWeightMax: Double = 0.9,
        val archWeightRampStart: Double = 0.12,
        val synergyDeltaPct: Double = 1.0,
        val glueMult: Double = 2.0,
        val synergyMult: Double = 1.2,
        val synergyCapPts: Double = 5.0,
        val penaltyRampStart: Double = 0.15,
        val penaltyMax: Double = 3.0,
        val needsRampStart: Double = 0.25,
        val needsRampSpan: Double = 0.55,
    )

    fun score(
        pack: List<RankedCard>,
        pool: List<RankedCard>,
        packNumber: Int,
        pickNumber: Int,
        metrics: SetMetrics,
        lane: Lane,
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        meta: (String) -> CardMeta? = { null },
    ): List<ScoredCard> {
        val poolMetas = pool.mapNotNull { meta(it.name) }
        val needs = PoolNeeds.analyze(poolMetas, pool.size)
        val picksTaken = (packNumber - 1) * config.picksPerPack + (pickNumber - 1)
        val progress = (picksTaken.toDouble() / config.totalPicks).coerceIn(0.0, 1.0)

        return pack.map { card ->
            evaluate(card, lane, metrics, archetypeRating, meta, progress, needs)
        }.sortedWith(compareByDescending<ScoredCard> { it.rawValue }.thenBy { it.card.displayName })
    }

    private fun evaluate(
        card: RankedCard,
        lane: Lane,
        metrics: SetMetrics,
        archetypeRating: (String, String) -> CardRating?,
        meta: (String) -> CardMeta?,
        progress: Double,
        needs: PoolNeeds,
    ): ScoredCard {
        val reasons = mutableListOf<String>()
        val globalWr = card.gihWr

        val baseZ = effectiveZ(card, metrics)
        val iwdVal = card.rating?.iwd
        val isBomb = baseZ > config.bombZ &&
            (if (iwdVal != null) iwdVal > config.bombIwd else baseZ > config.bombZNoIwd)

        val pair = lane.pair
        val archCard = pair?.let { archetypeRating(card.name, it) }
        val archWr = archCard?.gihWr
        val archSamples = archCard?.everDrawnGameCount ?: 0
        val archShiftZ = archetypeShiftZ(globalWr, archWr, archSamples, progress, metrics)
        val blendedZ = baseZ + archShiftZ
        val synergyPts = synergyBonus(card, globalWr, archWr, archSamples, reasons)

        val colors = LaneDetector.colorsOf(card)
        val hybridGroups = meta(card.name)?.hybridColorGroups.orEmpty()
        val offColors = if (lane.isEstablished) LaneDetector.uncastableColors(colors, lane.colors, hybridGroups) else emptySet()
        val onColor = colors.isNotEmpty() && lane.isEstablished && offColors.isEmpty()
        val splashable = offColors.size <= 1 || needs.fixing > 0
        val penaltyScale = when {
            !isBomb -> 1.0
            splashable -> 0.0
            else -> config.bombUnsplashablePenaltyScale
        }
        var penalty = 0.0
        if (colors.isNotEmpty() && offColors.isNotEmpty()) {
            penalty = penaltyZ(progress) * (offColors.size.toDouble() / colors.size) * penaltyScale
        }

        when {
            isBomb -> reasons.add(0, "Bomb")
            onColor -> reasons.add(0, "On-color")
            penalty >= PENALTY_REASON_THRESHOLD -> reasons.add(0, "Off-color (${lane.pair} lane)")
        }

        val needsResult = DeckNeeds.evaluateCard(meta(card.name), needs, config.totalPicks)
        val needsPts = needsResult.points * needsWeight(progress)
        reasons += needsResult.reasons

        var wheelPts = 0.0
        val alsa = card.rating?.alsa
        if (alsa != null && alsa >= config.wheelAlsa) {
            reasons += "Likely to wheel"
            wheelPts = config.wheelPenaltyPts
        }

        val rawValue = config.valueMidpoint + config.valuePerZ * (blendedZ - penalty) +
            synergyPts + needsPts - wheelPts
        val value = rawValue.coerceIn(0.0, 100.0)

        val breakdown = ValueBreakdown(
            baseScore = config.valueMidpoint + config.valuePerZ * baseZ,
            archetypeShift = config.valuePerZ * archShiftZ,
            synergyBonus = synergyPts,
            penalty = -config.valuePerZ * penalty,
            needsPoints = needsPts,
            finalScore = value,
        )

        return ScoredCard(card, value, blendedZ, isBomb, reasons.take(MAX_REASONS), breakdown, rawValue)
    }

    private fun effectiveZ(card: RankedCard, metrics: SetMetrics): Double {
        val rating = card.rating
        val gih = card.gihWr
        val games = rating?.everDrawnGameCount ?: 0
        if (gih != null && games > 0) {
            val z = metrics.z(gih) ?: 0.0
            if (games >= CardRating.MIN_GAMES) return z
            val trust = (games.toDouble() / CardRating.MIN_GAMES).coerceIn(0.0, 1.0)
            return z * trust
        }
        val alsa = rating?.alsa
        if (alsa != null) {
            return ((config.alsaPivot - alsa) * config.alsaZSlope).coerceIn(config.alsaZMin, config.alsaZMax)
        }
        return config.unratedZ
    }

    private fun archetypeShiftZ(
        globalWr: Double?,
        archWr: Double?,
        samples: Int,
        progress: Double,
        metrics: SetMetrics,
    ): Double {
        if (globalWr == null || archWr == null || samples < config.minArchSamples) return 0.0
        val effProgress = (progress - config.archWeightRampStart).coerceAtLeast(0.0)
        val weight = (config.archWeightBase + effProgress * config.archWeightSlope).coerceAtMost(config.archWeightMax)
        val confidence = (samples / 1000.0).coerceAtMost(1.0)
        val zGlobal = metrics.z(globalWr) ?: 0.0
        val zArch = metrics.z(archWr) ?: 0.0
        return weight * confidence * (zArch - zGlobal)
    }

    private fun synergyBonus(
        card: RankedCard,
        globalWr: Double?,
        archWr: Double?,
        samples: Int,
        reasons: MutableList<String>,
    ): Double {
        if (globalWr == null || archWr == null || samples < config.minArchSamples) return 0.0
        val deltaPct = (archWr - globalWr) * 100.0
        if (deltaPct < config.synergyDeltaPct) return 0.0
        val rarity = card.rating?.rarity?.lowercase()
        val glue = rarity == "common" || rarity == "uncommon"
        val pts = (deltaPct * if (glue) config.glueMult else config.synergyMult).coerceAtMost(config.synergyCapPts)
        if (pts > 1.0) reasons += if (glue) "Archetype glue" else "Archetype synergy"
        return pts
    }

    private fun needsWeight(progress: Double): Double =
        ((progress - config.needsRampStart) / config.needsRampSpan).coerceIn(0.0, 1.0)

    private fun penaltyZ(progress: Double): Double {
        val p = ((progress - config.penaltyRampStart) / (1.0 - config.penaltyRampStart)).coerceIn(0.0, 1.0)
        return p * config.penaltyMax
    }

    companion object {
        private const val PENALTY_REASON_THRESHOLD = 0.1
        private const val MAX_REASONS = 3
    }
}
