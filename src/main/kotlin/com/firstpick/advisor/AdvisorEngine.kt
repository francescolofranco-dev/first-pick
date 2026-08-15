package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.guide.LimitedPolicy

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
        val totalPicks: Int = LimitedPolicy.DRAFT_TOTAL_PICKS,
        val picksPerPack: Int = LimitedPolicy.PICKS_PER_PACK,
        val valueMidpoint: Double = 50.0,
        val valuePerZ: Double = 16.0,
        val alsaPivot: Double = 5.0,
        val alsaZSlope: Double = 0.15,
        val alsaZMin: Double = -1.2,
        val alsaZMax: Double = 0.8,
        val minArchSamples: Int = 200,
        val archWeightBase: Double = 0.0,


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
        val earlyBombThemeFuelBonus: Double = 1.0,
        val penaltyRampStart: Double = LimitedPolicy.COLOR_COMMITMENT_RAMP_START,
        val penaltyMax: Double = 3.0,
        val needsRampStart: Double = LimitedPolicy.DECK_NEEDS_RAMP_START,
        val needsRampSpan: Double = LimitedPolicy.DECK_NEEDS_RAMP_SPAN,


        val dupSpellPts: Double = 5.0,
        val dupSpellFree: Int = 1,
        val dupCreaturePts: Double = 2.0,
        val dupCreatureFree: Int = 2,
        val dupCapPts: Double = 12.0,




        val creatureFloorPts: Double = 0.0,
        val creatureFloorTarget: Double = LimitedPolicy.FINAL_CREATURE_TARGET.toDouble(),


        val fitPerPowerDelta: Double = 2.0,
        val fitCapPts: Double = 6.0,
        val fitRampStart: Double = LimitedPolicy.DECK_FIT_RAMP_START,
        val fitRampSpan: Double = LimitedPolicy.DECK_FIT_RAMP_SPAN,
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
        deckFit: ((RankedCard) -> DeckProjector.Fit?)? = null,
        activeManaSources: ManaSourceReport? = null,
    ): List<ScoredCard> {
        val poolMetas = pool.mapNotNull { meta(it.name) }
        val needs = PoolNeeds.analyze(poolMetas, pool.size)
        val picksTaken = (packNumber - 1) * config.picksPerPack + (pickNumber - 1)
        val progress = (picksTaken.toDouble() / config.totalPicks).coerceIn(0.0, 1.0)
        val theme = synergy?.let {
            ThemeSynergy(it, pool) { card ->
                if (!lane.isEstablished && isBomb(card, metrics)) {
                    1.0 + config.earlyBombThemeFuelBonus.coerceAtLeast(0.0)
                } else {
                    1.0
                }
            }
        }
        val poolCounts = pool.groupingBy { it.name }.eachCount()
        val splashColors = activeManaSources
            ?.takeIf { lane.isEstablished && it.baseColors == lane.colors && it.allRequirementsMet }
            ?.splashColor
            ?.let(::setOf)
            .orEmpty()
        val fitW = fitWeight(progress, lane)

        val heuristicOrder = pack.map { card ->
            val fit = if (fitW > 0.0) deckFit?.invoke(card) else null
            evaluate(card, lane, metrics, archetypeRating, meta, progress, needs, theme, poolCounts[card.name] ?: 0, splashColors, fit, fitW)
        }.sortedWith(compareByDescending<ScoredCard> { it.rawValue }.thenBy { it.card.displayName })
        // Hard guide feasibility applies even when PickNet is unavailable or
        // lacks enough pack coverage to run.
        return PickNetRanker.applyGuideOrder(heuristicOrder, heuristicOrder)
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
        fit: DeckProjector.Fit?,
        fitWeight: Double,
    ): ScoredCard {
        val reasons = mutableListOf<String>()
        val globalWr = card.gihWr

        val baseZ = effectiveZ(card, metrics)
        val isBomb = isBomb(baseZ, card.rating?.iwd)

        val pair = lane.pair
        val archCard = pair?.let { archetypeRating(card.name, it) }
        val archWr = archCard?.gihWr
        val archSamples = archCard?.everDrawnGameCount ?: 0
        val archShiftZ = archetypeShiftZ(globalWr, archWr, archSamples, progress, metrics)
        val blendedZ = baseZ + archShiftZ
        val statSynergyPts = synergyBonus(card, globalWr, archWr, archSamples, reasons)

        val activeGuidePair = if (lane.isEstablished) lane.pair ?: canonicalPair(lane.colors) else null
        val themeResult = theme?.evaluate(card, config, activeGuidePair) ?: ThemeSynergy.Result.NONE


        val totalSynergyPts = if (themeResult.points > 0.0) {
            (statSynergyPts + themeResult.points).coerceAtMost(config.synergyTotalCapPts)
        } else {
            statSynergyPts
        }
        val themePts = totalSynergyPts - statSynergyPts
        if (themePts > 0.0) reasons += themeResult.reasons

        val cardMeta = meta(card.name)
        val colors = castingColorsOf(card, cardMeta)
        val identityKnown = card.rating != null || cardMeta != null
        val hybridGroups = cardMeta?.hybridColorGroups.orEmpty()


        val produced = cardMeta?.producedColors.orEmpty()
        val isFixingLand = cardMeta?.isLand == true && produced.isNotEmpty()
        val offColors: Set<Char>
        val colorDenom: Int
        if (isFixingLand) {
            offColors = when {
                !lane.isEstablished -> emptySet()
                produced.containsAll(lane.colors) -> emptySet()


                else -> produced - lane.colors - splashColors
            }
            colorDenom = produced.size
        } else {
            offColors = if (lane.isEstablished) {
                val pureColors = cardMeta?.exactPureColorsOrNull()
                val options = LaneDetector.uncastableColorOptions(colors, lane.colors, hybridGroups, pureColors)
                val projectedSplash = fit?.afterSplash
                options.minWithOrNull(
                    compareBy<Set<Char>> { option ->
                        when {
                            projectedSplash != null && projectedSplash in option -> 0
                            option.any(splashColors::contains) -> 1
                            else -> 2
                        }
                    }.thenBy { it.size }
                        .thenBy { option -> "WUBRG".filter(option::contains) },
                ).orEmpty()
            } else {
                emptySet()
            }
            colorDenom = colors.size
        }
        val guardrail = pickGuardrail(lane, offColors, cardMeta, identityKnown, isFixingLand, isBomb, fit)
        val onColor = colors.isNotEmpty() && lane.isEstablished && offColors.isEmpty()
        val splashable = offColors.size <= 1 || needs.fixing > 0
        val penaltyScale = when {
            !isBomb -> 1.0
            !guardrail.modelPromotable -> 1.0
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
        if (!guardrail.modelPromotable &&
            (isBomb || reasons.none { it.startsWith("Off-color") })
        ) {
            val off = guardrail.offColors.sortedBy { "WUBRG".indexOf(it) }.joinToString("")
            val constraintReason = when {
                GuideConstraint.UNKNOWN_MANA in guardrail.constraints -> "Mana requirements unavailable"
                GuideConstraint.OFF_PLAN_FIXING in guardrail.constraints -> "Off-plan $off fixing"
                GuideConstraint.HEAVY_SPLASH_PIPS in guardrail.constraints -> "Heavy $off splash"
                else -> "Unsupported $off splash"
            }
            reasons.add(if (reasons.firstOrNull() == "Bomb") 1 else 0, constraintReason)
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


        var fitPts = 0.0
        if (fit != null && fit.makesDeck) {
            fitPts = (config.fitPerPowerDelta * fit.powerDelta.coerceAtLeast(0.0))
                .coerceAtMost(config.fitCapPts) * fitWeight
            if (fitPts >= FIT_REASON_THRESHOLD) {
                reasons += when {
                    fit.splashAdded != null -> "Worth a ${fit.splashAdded} splash"
                    fit.baseShifted -> "Bends your colors"
                    else -> "Makes your deck"
                }
            }
        }

        val rawValue = config.valueMidpoint + config.valuePerZ * (blendedZ - penalty) +
            totalSynergyPts + needsPts + fitPts - wheelPts - dupPts
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
            deckFitPoints = fitPts,
            wheelPenalty = -wheelPts,
            scoreCap = value - rawValue,
        )

        return ScoredCard(
            card = card,
            value = value,
            z = blendedZ,
            isBomb = isBomb,
            reasons = reasons.take(MAX_REASONS),
            breakdown = breakdown,
            rawValue = rawValue,
            guardrail = guardrail,
        )
    }

    private fun pickGuardrail(
        lane: Lane,
        offColors: Set<Char>,
        meta: CardMeta?,
        identityKnown: Boolean,
        isFixingLand: Boolean,
        isBomb: Boolean,
        fit: DeckProjector.Fit?,
    ): PickGuardrail {
        if (!lane.isEstablished) return PickGuardrail.OPEN
        if (!identityKnown) {
            return PickGuardrail(
                status = ModelPromotionStatus.CONSTRAINED,
                laneColors = lane.colors,
                constraints = setOf(GuideConstraint.UNKNOWN_MANA),
            )
        }
        if (offColors.isEmpty()) {
            return PickGuardrail(ModelPromotionStatus.ON_PLAN, lane.colors)
        }
        // A color label identifies the lane, but only Scryfall metadata tells us
        // whether the splash costs one pip, two pips, or hybrid mana.
        if (meta == null) {
            return PickGuardrail(
                status = ModelPromotionStatus.CONSTRAINED,
                laneColors = lane.colors,
                offColors = offColors,
                constraints = setOf(GuideConstraint.UNKNOWN_MANA),
            )
        }

        val heavy = offColors.filterTo(mutableSetOf()) { color ->
            color in meta?.heavyPipColors.orEmpty() || (meta?.effectiveSplashPips(color, lane.colors) ?: 0) >= 2
        }
        val expectedBasePair = lane.pair ?: canonicalPair(lane.colors)
        val expectedSplash = offColors.singleOrNull()
        val projectedUpgrade = fit?.let {
            val mana = it.afterManaSources
            it.makesDeck && !it.baseShifted && it.powerDelta > 0.0 &&
                expectedBasePair != null && it.afterBasePair == expectedBasePair &&
                expectedSplash != null && it.afterSplash == expectedSplash &&
                mana != null && mana.splashColor == expectedSplash && mana.allRequirementsMet
        } == true
        // A bomb is only speculative while no complete deck can be projected.
        // Once projection has answered, a failed source/fit check is a hard
        // constraint rather than an invitation for the learned model to guess.
        val lightSplashBomb = fit == null && isBomb && offColors.size == 1 && heavy.isEmpty()
        if (!isFixingLand && heavy.isEmpty() && offColors.size == 1 && (projectedUpgrade || lightSplashBomb)) {
            return PickGuardrail(
                status = if (projectedUpgrade) {
                    ModelPromotionStatus.SUPPORTED_SPLASH
                } else {
                    ModelPromotionStatus.SPLASH_CANDIDATE
                },
                laneColors = lane.colors,
                offColors = offColors,
            )
        }

        val constraints = buildSet {
            if (isFixingLand) add(GuideConstraint.OFF_PLAN_FIXING)
            if (offColors.size > 1) add(GuideConstraint.MULTIPLE_SPLASH_COLORS)
            if (heavy.isNotEmpty()) add(GuideConstraint.HEAVY_SPLASH_PIPS)
            if (!isFixingLand) add(GuideConstraint.UNSUPPORTED_SPLASH)
        }
        return PickGuardrail(
            status = ModelPromotionStatus.CONSTRAINED,
            laneColors = lane.colors,
            offColors = offColors,
            constraints = constraints,
        )
    }

    private fun castingColorsOf(card: RankedCard, meta: CardMeta?): Set<Char> {
        val metadataColors = buildSet {
            addAll(meta?.coloredPips.orEmpty().keys)
            meta?.hybridPips.orEmpty().forEach(::addAll)
            meta?.hybridColorGroups.orEmpty().forEach(::addAll)
            addAll(meta?.heavyPipColors.orEmpty())
        }
        return metadataColors.ifEmpty { LaneDetector.colorsOf(card) }
    }

    private fun CardMeta.exactPureColorsOrNull(): Set<Char>? =
        coloredPips.keys.takeIf { coloredPips.isNotEmpty() || hybridPips.isNotEmpty() || hybridColorGroups.isNotEmpty() }

    private fun CardMeta.effectiveSplashPips(color: Char, baseColors: Set<Char>): Int {
        var pips = coloredPips[color] ?: 0
        val hybrids = hybridPips.ifEmpty { hybridColorGroups }
        for (group in hybrids) {
            if (color in group && group.none(baseColors::contains)) pips++
        }
        return pips
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

    private fun isBomb(card: RankedCard, metrics: SetMetrics): Boolean =
        isBomb(effectiveZ(card, metrics), card.rating?.iwd)

    private fun isBomb(baseZ: Double, iwd: Double?): Boolean =
        baseZ > config.bombZ && (if (iwd != null) iwd > config.bombIwd else baseZ > config.bombZNoIwd)

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


    private fun fitWeight(progress: Double, lane: Lane): Double {
        if (!lane.isEstablished || config.fitPerPowerDelta <= 0.0) return 0.0
        return ((progress - config.fitRampStart) / config.fitRampSpan).coerceIn(0.0, 1.0)
    }

    private fun penaltyZ(progress: Double): Double {
        val p = ((progress - config.penaltyRampStart) / (1.0 - config.penaltyRampStart)).coerceIn(0.0, 1.0)
        return p * config.penaltyMax
    }

    companion object {
        private const val PENALTY_REASON_THRESHOLD = 0.1
        private const val DUP_REASON_THRESHOLD = 1.0
        private const val FIT_REASON_THRESHOLD = 1.0
        private const val MAX_REASONS = 3
    }
}
