package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics

/**
 * The contextual advisor — a port of the reference tool's "Compositional Brain".
 * Each pack card's raw 17Lands power becomes a 0–100 pick VALUE by layering context:
 *
 *  1. Base power      — GIH win-rate z-score within the set.
 *  2. Archetype gravity — blend global vs your-pair win rate, weighted toward the pair
 *                         as the draft commits (Bayesian-smoothed by sample size), plus a
 *                         synergy/glue bonus for cards that overperform in your pair.
 *  3. Commitment      — sliding off-color penalty that tightens by pack/pick, overridden
 *                         for true bombs (high z AND high IWD).
 *  4. Deck needs      — removal/creatures/2-drops/fixing/finisher/curve multipliers whose
 *                         influence ramps up with draft progress (early = pure value, late =
 *                         build a balanced deck). See [DeckNeeds].
 *  5. Wheel signal    — flag late-ALSA cards likely to come back.
 */
class AdvisorEngine(
    private val config: Config = Config(),
) {
    data class Config(
        val unratedZ: Double = -0.8,
        val bombZ: Double = 1.0,
        val bombIwd: Double = 0.045,
        val wheelAlsa: Double = 7.0,
        val totalPicks: Int = 45,
        val picksPerPack: Int = 15,
        val valueMidpoint: Double = 50.0,
        val valuePerZ: Double = 16.0,
        // Archetype blend.
        val minArchSamples: Int = 200,
        val archWeightBase: Double = 0.2,
        val archWeightSlope: Double = 0.7,
        val archWeightMax: Double = 0.9,
        val synergyDeltaPct: Double = 1.0,
        val glueMult: Double = 5.0,
        val synergyMult: Double = 3.0,
        val synergyCapPts: Double = 14.0,
        // Deck-needs time ramp (fraction of draft progress).
        val needsRampStart: Double = 0.18,
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
        val progress = picksTaken.toDouble() / config.totalPicks

        return pack.map { card ->
            evaluate(card, lane, metrics, archetypeRating, meta, packNumber, pickNumber, progress, needs)
        }.sortedWith(compareByDescending<ScoredCard> { it.value }.thenBy { it.card.displayName })
    }

    private fun evaluate(
        card: RankedCard,
        lane: Lane,
        metrics: SetMetrics,
        archetypeRating: (String, String) -> CardRating?,
        meta: (String) -> CardMeta?,
        packNumber: Int,
        pickNumber: Int,
        progress: Double,
        needs: PoolNeeds,
    ): ScoredCard {
        val reasons = mutableListOf<String>()
        val globalWr = card.gihWr
        val rawZ = metrics.z(globalWr) ?: config.unratedZ
        val iwd = card.rating?.iwd ?: 0.0
        val isBomb = rawZ > config.bombZ && iwd > config.bombIwd

        // (2) Archetype gravity.
        val pair = lane.pair
        val archCard = pair?.let { archetypeRating(card.name, it) }
        val archWr = archCard?.gihWr
        val archSamples = archCard?.everDrawnGameCount ?: 0
        val blendedZ = metrics.z(blend(globalWr, archWr, archSamples, progress)) ?: config.unratedZ
        val synergyPts = synergyBonus(card, globalWr, archWr, archSamples, reasons)

        // (3) Sliding commitment penalty — bombs ignore it.
        val colors = LaneDetector.colorsOf(card)
        val offColors = if (lane.isEstablished) colors - lane.colors else emptySet()
        val onColor = colors.isNotEmpty() && lane.isEstablished && offColors.isEmpty()
        var penalty = 0.0
        if (!isBomb && colors.isNotEmpty() && offColors.isNotEmpty()) {
            penalty = phasePenalty(packNumber, pickNumber) * (offColors.size.toDouble() / colors.size)
        }

        when {
            isBomb -> reasons.add(0, "Bomb")
            onColor -> reasons.add(0, "On-color")
            penalty >= PENALTY_REASON_THRESHOLD -> reasons.add(0, "Off-color (${lane.pair} lane)")
        }

        // (4) Deck needs, ramped by draft progress.
        val needsResult = DeckNeeds.evaluateCard(meta(card.name), needs, config.totalPicks)
        val effMult = 1.0 + (needsResult.multiplier - 1.0) * needsWeight(progress)
        reasons += needsResult.reasons

        // (5) Wheel signal.
        val alsa = card.rating?.alsa
        if (alsa != null && alsa >= config.wheelAlsa) reasons += "Likely to wheel"

        val baseValue = config.valueMidpoint + config.valuePerZ * (blendedZ - penalty) + synergyPts
        val value = (baseValue * effMult).coerceIn(0.0, 100.0)
        return ScoredCard(card, value, blendedZ, isBomb, reasons.take(MAX_REASONS))
    }

    /** Progressive global<->archetype blend, Bayesian-smoothed by the pair sample size. */
    private fun blend(globalWr: Double?, archWr: Double?, samples: Int, progress: Double): Double? {
        if (globalWr == null) return archWr
        if (archWr == null || samples < config.minArchSamples) return globalWr
        val weight = (config.archWeightBase + progress * config.archWeightSlope).coerceAtMost(config.archWeightMax)
        val confidence = (samples / 1000.0).coerceAtMost(1.0)
        val trustedArch = archWr * confidence + globalWr * (1.0 - confidence)
        return globalWr * (1.0 - weight) + trustedArch * weight
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
        reasons += if (glue) "Archetype glue" else "Archetype synergy"
        return pts
    }

    /** 0 early (pure value) ramping to 1 mid/late (build a deck). */
    private fun needsWeight(progress: Double): Double =
        ((progress - config.needsRampStart) / config.needsRampSpan).coerceIn(0.0, 1.0)

    /** Off-color suppression that tightens as the draft commits. In z-units. */
    private fun phasePenalty(packNumber: Int, pickNumber: Int): Double = when {
        packNumber <= 1 && pickNumber <= 7 -> 0.0
        packNumber <= 1 -> (pickNumber - 7) * P1_LATE_PER_PICK
        packNumber == 2 -> P2_PENALTY
        else -> P3_PENALTY
    }

    companion object {
        private const val P1_LATE_PER_PICK = 0.07
        private const val P2_PENALTY = 1.5
        private const val P3_PENALTY = 3.0
        private const val PENALTY_REASON_THRESHOLD = 0.3
        private const val MAX_REASONS = 3
    }
}
