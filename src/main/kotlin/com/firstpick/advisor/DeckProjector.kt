package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole
import com.firstpick.guide.DeckConstructionPolicy
import com.firstpick.guide.LimitedDeckPolicies
import com.firstpick.guide.LimitedMode


object DeckProjector {
    private const val MIN_DECK_OPTIONS = 2
    private const val CREATURE_BIAS = 0.015


    private const val SIGNPOST_NUDGE = 0.010
    private const val THEME_NUDGE = 0.008
    private const val KEY_NUDGE = 0.005


    data class Fit(

        val makesDeck: Boolean,

        val displaced: List<String>,

        val baseShifted: Boolean,

        val splashAdded: Char?,

        val powerDelta: Double,

        val afterBasePair: String? = null,

        val afterSplash: Char? = null,

        val afterManaSources: ManaSourceReport? = null,
    )


    fun fit(before: DeckOption?, after: DeckOption?, candidate: RankedCard): Fit {
        val beforeCounts = before?.spells?.groupingBy { it.name }?.eachCount().orEmpty()
        val afterCounts = after?.spells?.groupingBy { it.name }?.eachCount().orEmpty()
        val makesDeck = (afterCounts[candidate.name] ?: 0) > (beforeCounts[candidate.name] ?: 0)
        val displaced = beforeCounts.entries.flatMap { (name, count) ->
            List((count - (afterCounts[name] ?: 0)).coerceAtLeast(0)) { name }
        }
        val baseShifted = before != null && after != null && before.basePair != after.basePair
        val splashAdded = after?.splash?.takeIf { it != before?.splash }
        val powerDelta = (after?.powerScore ?: 0.0) - (before?.powerScore ?: 0.0)
        return Fit(
            makesDeck = makesDeck,
            displaced = displaced,
            baseShifted = baseShifted,
            splashAdded = splashAdded,
            powerDelta = powerDelta,
            afterBasePair = after?.basePair,
            afterSplash = after?.splash,
            afterManaSources = after?.manaSources,
        )
    }


    fun fit(
        pool: List<RankedCard>,
        candidate: RankedCard,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        synergy: SynergyIndex? = null,
        before: DeckOption? = null,
        mode: LimitedMode = LimitedMode.DRAFT,
    ): Fit {
        val b = before ?: project(pool, metrics, meta, archetypeRating, pairStrength, synergy, mode)
        val a = project(pool + candidate, metrics, meta, archetypeRating, pairStrength, synergy, mode)
        return fit(b, a, candidate)
    }


    fun project(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        synergy: SynergyIndex? = null,
        mode: LimitedMode = LimitedMode.DRAFT,
    ): DeckOption? = projectAll(pool, metrics, meta, archetypeRating, pairStrength, maxOptions = 1, synergy = synergy, mode = mode).firstOrNull()

    fun projectAll(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
        synergy: SynergyIndex? = null,
        mode: LimitedMode = LimitedMode.DRAFT,
    ): List<DeckOption> {
        val policy = LimitedDeckPolicies.forMode(mode)

        val known = pool.filter { it.rating != null || meta(it.name) != null }
        val spells = known.filter { meta(it.name)?.isLand != true && !it.isBasicLand }
        // Arena exposes unlimited basics separately. A drafted basic must not
        // consume one of the finite nonbasic slots or be reported as fixing.
        val lands = known.filter { meta(it.name)?.isLand == true && !it.isBasicLand }

        fun pass(minSpells: Int, lenient: Boolean = false, upgrade: Boolean = false) = COLOR_PAIRS.mapNotNull { pair ->
            buildForPair(pair, spells, lands, metrics, meta, archetypeRating, strengthFor(pair, pairStrength), minSpells, synergy, policy, lenient, upgrade)
        }

        val main = (pass(policy.minimumBuildableSpells) + pass(policy.minimumBuildableSpells, upgrade = true))
            .sortedByDescending { it.powerScore }
            .distinctBy { it.colors }
        // Lenient builds may fill the slate to two choices, but must never pad it to three.
        val optionCount = minOf(maxOptions, maxOf(MIN_DECK_OPTIONS, main.size))
        if (main.size >= optionCount) return main.take(optionCount)

        // A nearly buildable fallback must beat a much thinner pile, while
        // similarly incomplete choices can still be ordered by actual power.
        val nearBuildable = policy.minimumBuildableSpells - 2
        val fill = pass(0, lenient = true).sortedWith(
            compareByDescending<DeckOption> { it.spells.size >= nearBuildable }.thenByDescending { it.powerScore },
        )
        return (main + fill)
            .distinctBy { it.colors }
            .take(optionCount)
    }

    private fun strengthFor(pair: String, pairStrength: Map<String, Double>): Double? = pairStrength[pair]

    private fun buildForPair(
        pair: String,
        spells: List<RankedCard>,
        lands: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        archetypeRating: (String, String) -> CardRating?,
        strength: Double?,
        minSpells: Int,
        synergy: SynergyIndex?,
        policy: DeckConstructionPolicy,
        lenient: Boolean = false,
        upgrade: Boolean = false,
    ): DeckOption? {
        val pairSet = pair.toSet()
        fun onColor(card: RankedCard): Boolean {
            val cardMeta = meta(card.name)
            val colors = castingColorsOf(card, cardMeta)
            val hybridGroups = cardMeta?.hybridColorGroups.orEmpty()
            val pureColors = cardMeta?.exactPureColorsOrNull()
            return colors.isEmpty() || LaneDetector.uncastableColorOptions(colors, pairSet, hybridGroups, pureColors)
                .any { it.isEmpty() }
        }
        fun themeNudge(card: RankedCard): Double {
            val tag = synergy?.tags(card.name)?.firstOrNull { it.pair == pair } ?: return 0.0
            return when (tag.role) {
                SynergyRole.SIGNPOST -> SIGNPOST_NUDGE
                SynergyRole.PAYOFF, SynergyRole.ENABLER -> THEME_NUDGE
                SynergyRole.KEY -> KEY_NUDGE
            }
        }
        fun rawScore(card: RankedCard): Double {
            val pairRating = archetypeRating(card.name, pair)
            if (card.rating == null && pairRating == null) return metrics.meanGihWr - 0.02
            return DeckAnalysis.cardQuality(card.rating, pairRating, metrics)
        }
        fun cardScore(card: RankedCard): Double {
            val m = meta(card.name)
            val sealedRoles = (if (m?.isRemoval == true) policy.removalSelectionBonus else 0.0) +
                (if (m?.isFinisher == true) policy.finisherSelectionBonus else 0.0) +
                (if (m?.isEvasion == true || m?.isCardDraw == true) policy.stallBreakerSelectionBonus else 0.0)
            return rawScore(card) + themeNudge(card) + sealedRoles
        }

        fun landsFor(splashColor: Char?): List<RankedCard> {
            val deckColors = pairSet + setOfNotNull(splashColor)
            return lands.asSequence()
                .filter { card ->
                    val produced = meta(card.name)?.producedColors.orEmpty()
                    val identity = produced.ifEmpty { castingColorsOf(card, meta(card.name)) }
                    identity.isNotEmpty() && (deckColors.containsAll(identity) || identity.containsAll(deckColors))
                }
                .sortedByDescending { card ->
                    val m = meta(card.name)
                    m?.producedColors.orEmpty().count { it in deckColors } + if (m?.isFixing == true) 1 else 0
                }
                .take(policy.landSlots)
                .toList()
        }

        fun manaFor(chosen: List<RankedCard>, splashColor: Char?): ManaSourceReport {
            val landCount = (policy.deckSize - chosen.size).coerceAtLeast(policy.landSlots)
            val selectedLands = landsFor(splashColor)
            return ManaSources.estimate(chosen, pair, splashColor, selectedLands, meta, policy, landCount)
        }

        val eligible = spells.filter(::onColor)
        val base = selectSpells(eligible, metrics, meta, ::cardScore, ::rawScore, policy).toMutableList()

        var splashedSpells = emptyList<RankedCard>()
        var selectedSplashColor: Char? = null
        if (upgrade) {
            // Upgrade variants only exist for a complete base; short bases are
            // handled by the normal top-up pass below.
            if (base.size < policy.spellSlots) return null
            val swap = upgradeSplashCandidates(base, spells, pairSet, metrics, ::onColor, ::cardScore, meta, policy)
                .firstOrNull { candidate ->
                    val chosen = base.toMutableList().apply { candidate.removed.forEach(::remove) } + candidate.added
                    manaFor(chosen, candidate.color).splashFeasible
                } ?: return null
            swap.removed.forEach(base::remove)
            splashedSpells = swap.added
            selectedSplashColor = swap.color
        } else {
            val splash = splashCandidates(
                spells, pairSet, policy.spellSlots - base.size, ::onColor, ::cardScore, meta, policy,
            ).firstOrNull { candidate ->
                manaFor(base + candidate.cards, candidate.color).splashFeasible
            }
            splashedSpells = splash?.cards.orEmpty()
            selectedSplashColor = splash?.color
        }
        val chosen = base + splashedSpells
        val splashColor = selectedSplashColor
        val deckColors = pairSet + setOfNotNull(splashColor)

        if (chosen.size < minSpells) return null

        val pips = mutableMapOf<Char, Int>()
        for (card in chosen) for (ch in castingColorsOf(card, meta(card.name))) if (ch in deckColors) pips.merge(ch, 1, Int::plus)
        val totalPips = pips.values.sum()
        val minBasePips = pairSet.minOf { pips[it] ?: 0 }
        if (totalPips == 0) return null
        if (!lenient && (minBasePips < policy.minimumBaseColorPips || minBasePips.toDouble() / totalPips < policy.minimumBaseColorRatio)) return null

        val onColorLands = landsFor(splashColor)

        val metas = chosen.map { meta(it.name) }
        val creatures = metas.count { it?.isCreature == true }
        val removal = metas.count { it?.isRemoval == true }
        val landFixers = onColorLands.count { meta(it.name)?.isFixing == true }
        val landCount = (policy.deckSize - chosen.size).coerceAtLeast(policy.landSlots)
        val manaSources = ManaSources.estimate(chosen, pair, splashColor, onColorLands, meta, policy, landCount)
        // A splash is only real when it can be funded after both base colors.
        if (splashColor != null && !manaSources.splashFeasible) return null
        val identity = DeckAnalysis.identity(chosen, pair, meta, synergy, landFixers)
        val power = DeckAnalysis.power(
            spells = chosen,
            pair = pair,
            metrics = metrics,
            meta = meta,
            archetypeRating = archetypeRating,
            pairStrength = strength,
            identity = identity,
            synergy = synergy,
            landFixers = landFixers,
            splashCount = splashedSpells.size,
            policy = policy,
            manaSources = manaSources,
        )
        return DeckOption(
            colors = "WUBRG".filter { it in deckColors },
            basePair = "WUBRG".filter { it in pairSet },
            splash = splashColor,
            theme = synergy?.archetype(pair)?.name,
            powerScore = power.score,
            tier = tierOf(power.score),
            type = identity.pace.label,
            outlook = DeckAnalysis.outlook(power),
            deckWinRate = power.qualityRate,
            identityConfidence = identity.confidenceLabel,
            identityReasons = identity.reasons,
            powerConfidence = power.confidenceLabel,
            powerReasons = power.reasons,
            spells = chosen.sortedWith(compareBy({ meta(it.name)?.cmc ?: 9 }, { -(it.gihWr ?: 0.0) })),
            nonbasicLands = onColorLands,
            landCount = landCount,
            creatures = creatures,
            removal = removal,
            curve = curveOf(metas),
            manaSources = manaSources,
            constructionMode = policy.mode,
        )
    }

    private class SplashUpgrade(
        val color: Char,
        val removed: List<RankedCard>,
        val added: List<RankedCard>,
    )

    private class SplashChoice(val color: Char, val cards: List<RankedCard>)


    private fun upgradeSplashCandidates(
        base: List<RankedCard>,
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        metrics: SetMetrics,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
        meta: (String) -> CardMeta?,
        policy: DeckConstructionPolicy,
    ): List<SplashUpgrade> {
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val m = meta(card.name) ?: continue
            val splashColors = singleColorSplashOptions(card, pairSet, m)
            if (splashColors.isEmpty()) continue
            val impactful = m.isRemoval || m.isFinisher ||
                (card.gihWr ?: 0.0) >= metrics.meanGihWr + metrics.stdDevGihWr
            if (impactful) splashColors.forEach { color ->
                if (color !in m.heavyPipColors) byColor.getOrPut(color) { mutableListOf() }.add(card)
            }
        }
        val weakestFirst = base.sortedBy(cardScore)
        return byColor.flatMap { (color, sameColor) ->
            val candidates = sameColor.sortedByDescending(cardScore)
            val removed = mutableListOf<RankedCard>()
            val added = mutableListOf<RankedCard>()
            val prefixes = mutableListOf<SplashUpgrade>()
            for ((i, cand) in candidates.withIndex()) {
                if (i >= policy.maximumSplashCards || i >= weakestFirst.size) break
                if (cardScore(cand) < cardScore(weakestFirst[i]) + policy.splashUpgradeMargin) break
                removed += weakestFirst[i]
                added += cand
                prefixes += SplashUpgrade(color, removed.toList(), added.toList())
            }
            prefixes
        }
            .sortedByDescending { swap -> swap.added.sumOf(cardScore) - swap.removed.sumOf(cardScore) }
    }

    private fun splashCandidates(
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        deficit: Int,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
        meta: (String) -> CardMeta?,
        policy: DeckConstructionPolicy,
    ): List<SplashChoice> {
        if (deficit <= 0) return emptyList()
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val m = meta(card.name) ?: continue
            singleColorSplashOptions(card, pairSet, m).forEach { color ->
                if (color !in m.heavyPipColors) byColor.getOrPut(color) { mutableListOf() }.add(card)
            }
        }
        return byColor
            .flatMap { (color, sameColor) ->
                sameColor.sortedByDescending(cardScore)
                    .take(minOf(deficit, policy.maximumSplashCards))
                    .runningFold(emptyList<RankedCard>()) { prefix, card -> prefix + card }
                    .drop(1)
                    .map { SplashChoice(color, it) }
            }
            .filter { it.cards.isNotEmpty() }
            .sortedByDescending { choice -> choice.cards.sumOf(cardScore) }
    }

    private class Candidate(
        val card: RankedCard,
        val score: Double,
        val cmc: Int,
        val isCreature: Boolean,
        val isRemoval: Boolean,
    )

    private fun castingColorsOf(card: RankedCard, meta: CardMeta?): Set<Char> {
        val metadataColors = buildSet {
            addAll(meta?.coloredPips.orEmpty().keys)
            meta?.hybridPips.orEmpty().forEach(::addAll)
            meta?.hybridColorGroups.orEmpty().forEach(::addAll)
            addAll(meta?.heavyPipColors.orEmpty())
        }
        return metadataColors.ifEmpty { LaneDetector.colorsOf(card) }
    }

    private fun singleColorSplashOptions(card: RankedCard, baseColors: Set<Char>, meta: CardMeta): Set<Char> =
        LaneDetector.uncastableColorOptions(
            colors = castingColorsOf(card, meta),
            available = baseColors,
            hybridGroups = meta.hybridColorGroups,
            pureColors = meta.exactPureColorsOrNull(),
        ).mapNotNull { it.singleOrNull() }.toSet()

    private fun CardMeta.exactPureColorsOrNull(): Set<Char>? =
        coloredPips.keys.takeIf { coloredPips.isNotEmpty() || hybridPips.isNotEmpty() || hybridColorGroups.isNotEmpty() }

    private fun selectSpells(
        eligible: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        cardScore: (RankedCard) -> Double,
        capScore: (RankedCard) -> Double = cardScore,
        policy: DeckConstructionPolicy,
    ): List<RankedCard> {
        val candidates = eligible
            .groupBy { it.name }
            .flatMap { (_, copies) ->


                val cap = copyCap(metrics.z(capScore(copies.first())) ?: 0.0)
                copies.sortedByDescending { it.gihWr ?: 0.0 }.take(cap)
            }
            .map { card ->
                val m = meta(card.name)
                Candidate(card, cardScore(card), m?.cmc ?: 3, m?.isCreature == true, m?.isRemoval == true)
            }
            .sortedByDescending { it.score }

        val creatures = candidates.filter { it.isCreature }
        val others = candidates.filterNot { it.isCreature }

        val chosen = LinkedHashSet<Candidate>()
        val byBucket = creatures.groupBy { curveBucket(it.cmc) }
        for ((bucket, target) in policy.creatureCurve) {
            byBucket[bucket].orEmpty().take(target).forEach { chosen.add(it) }
        }


        var removalSeated = chosen.count { it.isRemoval }
        for (cand in others) {
            if (removalSeated >= policy.removalTarget || chosen.size >= policy.spellSlots) break
            if (!cand.isRemoval) continue
            if ((metrics.z(capScore(cand.card)) ?: 0.0) < policy.removalMinimumZ) continue
            if (chosen.add(cand)) removalSeated++
        }

        val creatureQueue = ArrayDeque(creatures.filterNot { it in chosen })
        val otherQueue = ArrayDeque(others.filterNot { it in chosen })
        var creatureCount = chosen.count { it.isCreature }
        var otherCount = chosen.size - creatureCount
        while (chosen.size < policy.spellSlots && (creatureQueue.isNotEmpty() || otherQueue.isNotEmpty())) {
            val c = creatureQueue.firstOrNull()
            val o = otherQueue.firstOrNull()
            val pick = when {
                c == null -> otherQueue.removeFirst().also { otherCount++ }
                o == null -> creatureQueue.removeFirst()
                otherCount >= policy.nonCreatureCap -> creatureQueue.removeFirst()
                creatureCount < policy.creatureTarget && c.score >= o.score - CREATURE_BIAS -> creatureQueue.removeFirst()
                c.score > o.score -> creatureQueue.removeFirst()
                else -> otherQueue.removeFirst().also { otherCount++ }
            }
            if (chosen.add(pick) && pick.isCreature) creatureCount++
        }
        return chosen.map { it.card }
    }

    private fun copyCap(z: Double): Int = when {
        z >= 1.0 -> 3
        z >= 0.0 -> 2
        else -> 1
    }

    private fun curveBucket(cmc: Int): Int = cmc.coerceIn(1, 6)

    private fun curveOf(metas: List<CardMeta?>): List<Pair<String, Int>> {
        val buckets = linkedMapOf("≤1" to 0, "2" to 0, "3" to 0, "4" to 0, "5" to 0, "6+" to 0)
        for (m in metas) {
            if (m == null || m.isLand) continue
            val key = when {
                m.cmc <= 1 -> "≤1"
                m.cmc >= 6 -> "6+"
                else -> m.cmc.toString()
            }
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets.toList()
    }

    private fun tierOf(power: Double): String = when {
        power >= 80 -> "A+"
        power >= 72 -> "A"
        power >= 64 -> "B+"
        power >= 56 -> "B"
        power >= 48 -> "C+"
        power >= 40 -> "C"
        else -> "D"
    }

}
