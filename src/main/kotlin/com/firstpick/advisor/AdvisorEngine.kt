package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex

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
        // Once a lane is committed, a card's win rate WITHIN that lane predicts better than its
        // global rate, so the archetype shift ramps to full trust by late pack 1 (validated
        // neutral on human-agreement, slightly better winner-alignment, on the FIN backtest).
        val archWeightSlope: Double = 2.0,
        val archWeightMax: Double = 1.0,
        val archWeightRampStart: Double = 0.06,
        val synergyDeltaPct: Double = 1.0,
        val glueMult: Double = 2.0,
        val synergyMult: Double = 1.2,
        val synergyCapPts: Double = 5.0,
        val themeCapPts: Double = 6.0,
        val themeFuelCap: Double = 6.0,
        val comboPts: Double = 2.0,
        val comboCapPts: Double = 4.0,
        val synergyTotalCapPts: Double = 8.0,
        val penaltyRampStart: Double = 0.15,
        val penaltyMax: Double = 3.0,
        val needsRampStart: Double = 0.25,
        val needsRampSpan: Double = 0.55,
        // Diminishing returns for extra copies already in the pool. Noncreature spells (removal,
        // tricks) get redundant faster than creatures; lands are exempt (you want many).
        val dupSpellPts: Double = 5.0,
        val dupSpellFree: Int = 1,
        val dupCreaturePts: Double = 2.0,
        val dupCreatureFree: Int = 2,
        val dupCapPts: Double = 12.0,
        // A color the pool commits this many playable cards to (beyond the main pair) is a real
        // splash — fixing that taps for it is doing useful work, not wasting a pip.
        val splashMinCards: Int = 2,
        // Greedy win-rate drafting under-picks creatures and lands creature-light (~10.6 vs a
        // human's ~12.9), and creature count is an outcome-validated deck-strength driver. This
        // adds pull toward creatures while the pool projects below creatureFloorTarget.
        val creatureFloorPts: Double = 0.0,
        val creatureFloorTarget: Double = 15.0,
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
        synergy: SynergyIndex? = null,
    ): List<ScoredCard> {
        val poolMetas = pool.mapNotNull { meta(it.name) }
        val needs = PoolNeeds.analyze(poolMetas, pool.size)
        val picksTaken = (packNumber - 1) * config.picksPerPack + (pickNumber - 1)
        val progress = (picksTaken.toDouble() / config.totalPicks).coerceIn(0.0, 1.0)
        val theme = synergy?.let { ThemeSynergy(it, pool) }
        val poolCounts = pool.groupingBy { it.name }.eachCount()
        val splashColors = splashColorsOf(pool, lane, meta)

        return pack.map { card ->
            evaluate(card, lane, metrics, archetypeRating, meta, progress, needs, theme, poolCounts[card.name] ?: 0, splashColors)
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
        theme: ThemeSynergy?,
        copiesInPool: Int,
        splashColors: Set<Char>,
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
        val statSynergyPts = synergyBonus(card, globalWr, archWr, archSamples, reasons)

        val themeResult = theme?.evaluate(card, config) ?: ThemeSynergy.Result.NONE
        // The shared cap only engages when theme points exist, so a profile-less run is
        // byte-identical to the old engine under any knob combination.
        val totalSynergyPts = if (themeResult.points > 0.0) {
            (statSynergyPts + themeResult.points).coerceAtMost(config.synergyTotalCapPts)
        } else {
            statSynergyPts
        }
        val themePts = totalSynergyPts - statSynergyPts
        if (themePts > 0.0) reasons += themeResult.reasons

        val cardMeta = meta(card.name)
        val colors = LaneDetector.colorsOf(card)
        val hybridGroups = cardMeta?.hybridColorGroups.orEmpty()
        // A mana-producing nonbasic land is judged by what it TAPS FOR, not its (blank) color:
        // a land that covers your lane's colors is premium fixing, one that wastes production on
        // off-colors (e.g. a Selesnya dual in a Golgari deck) is a wrong dual, not a top pick.
        val produced = cardMeta?.producedColors.orEmpty()
        val isFixingLand = cardMeta?.isLand == true && produced.isNotEmpty()
        val offColors: Set<Char>
        val colorDenom: Int
        if (isFixingLand) {
            offColors = when {
                !lane.isEstablished -> emptySet()
                produced.containsAll(lane.colors) -> emptySet() // taps for all your colors → good fixing
                // A produced color you're actually splashing isn't wasted — it's the fixing you
                // committed to when you took those off-color cards. Only truly unused pips count.
                else -> produced - lane.colors - splashColors
            }
            colorDenom = produced.size
        } else {
            offColors = if (lane.isEstablished) LaneDetector.uncastableColors(colors, lane.colors, hybridGroups) else emptySet()
            colorDenom = colors.size
        }
        val onColor = colors.isNotEmpty() && lane.isEstablished && offColors.isEmpty()
        val splashable = offColors.size <= 1 || needs.fixing > 0
        val penaltyScale = when {
            !isBomb -> 1.0
            splashable -> 0.0
            else -> config.bombUnsplashablePenaltyScale
        }
        var penalty = 0.0
        if (colorDenom > 0 && offColors.isNotEmpty()) {
            penalty = penaltyZ(progress) * (offColors.size.toDouble() / colorDenom) * penaltyScale
        }

        val splashFixed = if (isFixingLand) produced.intersect(splashColors) else emptySet()
        when {
            isBomb -> reasons.add(0, "Bomb")
            onColor -> reasons.add(0, "On-color")
            penalty >= PENALTY_REASON_THRESHOLD ->
                reasons.add(0, if (isFixingLand) "Off-color fixing (${lane.pair} lane)" else "Off-color (${lane.pair} lane)")
            splashFixed.isNotEmpty() ->
                reasons.add(0, "Fixes ${splashFixed.sortedBy { "WUBRG".indexOf(it) }.joinToString("")} splash")
        }

        val needsResult = DeckNeeds.evaluateCard(cardMeta, needs, config.totalPicks)
        var needsPts = needsResult.points * needsWeight(progress)
        reasons += needsResult.reasons
        if (config.creatureFloorPts > 0.0 && cardMeta?.isCreature == true) {
            val projected = needs.projected(needs.creatures, config.totalPicks, config.creatureFloorTarget)
            if (projected < config.creatureFloorTarget) {
                val deficit = ((config.creatureFloorTarget - projected) / config.creatureFloorTarget).coerceIn(0.0, 1.0)
                needsPts += config.creatureFloorPts * needsWeight(progress) * deficit
            }
        }

        var wheelPts = 0.0
        val alsa = card.rating?.alsa
        if (alsa != null && alsa >= config.wheelAlsa) {
            reasons += "Likely to wheel"
            wheelPts = config.wheelPenaltyPts
        }

        val dupPts = duplicatePenalty(copiesInPool, cardMeta)
        if (dupPts >= DUP_REASON_THRESHOLD) reasons += "${copiesInPool + 1}th copy — diminishing"

        val rawValue = config.valueMidpoint + config.valuePerZ * (blendedZ - penalty) +
            totalSynergyPts + needsPts - wheelPts - dupPts
        val value = rawValue.coerceIn(0.0, 100.0)

        val breakdown = ValueBreakdown(
            baseScore = config.valueMidpoint + config.valuePerZ * baseZ,
            archetypeShift = config.valuePerZ * archShiftZ,
            synergyBonus = statSynergyPts,
            themeBonus = themePts,
            penalty = -config.valuePerZ * penalty,
            needsPoints = needsPts,
            finalScore = value,
            duplicatePenalty = -dupPts,
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

    // Colors the pool is meaningfully splashing (playable, off the main pair, at least
    // splashMinCards deep). Fixing that taps for one of these is real fixing, not a wasted pip.
    private fun splashColorsOf(
        pool: List<RankedCard>,
        lane: Lane,
        meta: (String) -> CardMeta?,
    ): Set<Char> {
        if (!lane.isEstablished) return emptySet()
        val counts = HashMap<Char, Int>()
        for (card in pool) {
            if (meta(card.name)?.isLand == true) continue
            for (ch in LaneDetector.colorsOf(card)) {
                if (ch !in lane.colors) counts.merge(ch, 1, Int::plus)
            }
        }
        return counts.filterValues { it >= config.splashMinCards }.keys
    }

    private fun duplicatePenalty(copiesInPool: Int, meta: CardMeta?): Double {
        if (copiesInPool <= 0 || meta?.isLand == true) return 0.0
        val creature = meta?.isCreature == true
        val free = if (creature) config.dupCreatureFree else config.dupSpellFree
        val per = if (creature) config.dupCreaturePts else config.dupSpellPts
        val extra = (copiesInPool - free).coerceAtLeast(0)
        return (per * extra).coerceAtMost(config.dupCapPts)
    }

    private fun needsWeight(progress: Double): Double =
        ((progress - config.needsRampStart) / config.needsRampSpan).coerceIn(0.0, 1.0)

    private fun penaltyZ(progress: Double): Double {
        val p = ((progress - config.penaltyRampStart) / (1.0 - config.penaltyRampStart)).coerceIn(0.0, 1.0)
        return p * config.penaltyMax
    }

    companion object {
        private const val PENALTY_REASON_THRESHOLD = 0.1
        private const val DUP_REASON_THRESHOLD = 1.0
        private const val MAX_REASONS = 3
    }
}
