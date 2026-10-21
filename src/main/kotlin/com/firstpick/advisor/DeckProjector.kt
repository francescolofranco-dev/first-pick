package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole
import com.firstpick.guide.DeckConstructionPolicy
import com.firstpick.guide.LimitedDeckPolicies


object DeckProjector {
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
    ): Fit {
        val b = before ?: project(pool, metrics, meta, archetypeRating, pairStrength, synergy)
        val a = project(pool + candidate, metrics, meta, archetypeRating, pairStrength, synergy)
        return fit(b, a, candidate)
    }


    fun project(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        synergy: SynergyIndex? = null,
        basePair: String? = null,
    ): DeckOption? = projectAll(pool, metrics, meta, archetypeRating, pairStrength, maxOptions = 1, synergy = synergy, basePair = basePair).firstOrNull()

    fun projectAll(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
        synergy: SynergyIndex? = null,
        basePair: String? = null,
    ): List<DeckOption> {
        val policy = LimitedDeckPolicies.DRAFT

        val known = pool.filter { it.rating != null || meta(it.name) != null }
        val spells = known.filter { meta(it.name)?.isLand != true && !it.isBasicLand }
        // Arena exposes unlimited basics separately. A drafted basic must not
        // consume one of the finite nonbasic slots or be reported as fixing.
        val lands = known.filter { meta(it.name)?.isLand == true && !it.isBasicLand }

        fun pass(minSpells: Int, lenient: Boolean = false): List<DeckOption> =
            COLOR_PAIRS.filter { basePair == null || it == basePair }.flatMap { pair ->
                val clean = buildForPair(pair, spells, lands, metrics, meta, archetypeRating,
                    strengthFor(pair, pairStrength), minSpells, synergy, policy, lenient, allowSplash = false)
                val splashes = "WUBRG".filterNot { it in pair }.flatMap { color ->
                    listOfNotNull(
                        buildForPair(pair, spells, lands, metrics, meta, archetypeRating,
                            strengthFor(pair, pairStrength), minSpells, synergy, policy, lenient,
                            requiredSplash = color),
                        buildForPair(pair, spells, lands, metrics, meta, archetypeRating,
                            strengthFor(pair, pairStrength), minSpells, synergy, policy, lenient,
                            upgrade = true, requiredSplash = color),
                    ).filter { it.splash == color }
                }
                val threeColor = if (lenient) emptyList() else "WUBRG".filterNot { it in pair }.flatMap { color ->
                    // Jointly select the deck, rather than only appending bombs
                    // to a frozen two-color list. Also try cutting heavy third-color costs.
                    listOf(1, Int.MAX_VALUE).flatMap { pipLimit ->
                        val plans = listOf(ThreeColorPlan.CONSERVATIVE to 0) +
                            (2..policy.maximumSplashCards).map { ThreeColorPlan.BOLD to it }
                        plans.mapNotNull { (plan, thirdColorCards) ->
                            buildForPair(pair, spells, lands, metrics, meta, archetypeRating,
                                strengthFor(pair, pairStrength), minSpells, synergy, policy,
                                requiredSplash = color, threeColorPlan = plan, thirdColorPipLimit = pipLimit,
                                requiredThirdColorCards = thirdColorCards)
                        }
                    }.filter { option ->
                        val baseline = clean ?: return@filter true
                        option.spells.sumOf { DeckAnalysis.cardQuality(it.rating, archetypeRating(it.name, pair), metrics) } >=
                            baseline.spells.sumOf { DeckAnalysis.cardQuality(it.rating, archetypeRating(it.name, pair), metrics) } + policy.splashUpgradeMargin
                    }
                }
                listOfNotNull(clean) + splashes + threeColor
            }

        val ranked = pass(policy.minimumBuildableSpells).sortedByDescending { it.powerScore }
            .distinctBy(::deckContents)
        fun thirdColorCards(option: DeckOption): Int = option.spells.count { card ->
            val m = meta(card.name)
            LaneDetector.uncastableColorOptions(castingColorsOf(card, m), option.basePair.toSet(),
                m?.hybridColorGroups.orEmpty(), m?.exactPureColorsOrNull()).none { it.isEmpty() }
        }
        fun differentManaPlans(first: DeckOption, second: DeckOption): Boolean {
            if (first.threeColorPlan == second.threeColorPlan) return false
            val bold = listOf(first, second).firstOrNull { it.threeColorPlan == ThreeColorPlan.BOLD } ?: return false
            val cautious = if (bold === first) second else first
            return bold.deckWinRate > cautious.deckWinRate && thirdColorCards(bold) > thirdColorCards(cautious) &&
                bold.manaSources!!.shortfalls.values.sum() > cautious.manaSources!!.shortfalls.values.sum()
        }
        // Every alternative must be competitive; never force a weak second deck.
        val main = ranked.filterIndexed { index, option ->
            index == 0 || option.powerScore >= ranked.first().powerScore - 12.0
        }
        if (main.isNotEmpty()) {
            // Different card lists in the same colors can offer a real mana/quality
            // tradeoff. Keep up to two, without filling the slate with near copies.
            val selected = mutableListOf<DeckOption>()
            for (option in main) {
                val sameColors = selected.filter { it.colors == option.colors }
                if (sameColors.isEmpty() || (sameColors.size == 1 &&
                        differentManaPlans(sameColors.single(), option) &&
                        sameColors.single().spells.groupingBy { it.name }.eachCount() != option.spells.groupingBy { it.name }.eachCount())) {
                    selected += option
                }
                if (selected.size >= maxOptions) break
            }
            return selected.take(maxOptions)
        }

        // A nearly buildable fallback must beat a much thinner pile, while
        // similarly incomplete choices can still be ordered by actual power.
        val nearBuildable = policy.minimumBuildableSpells - 2
        val fill = pass(0, lenient = true).sortedWith(
            compareByDescending<DeckOption> { it.spells.size >= nearBuildable }.thenByDescending { it.powerScore },
        )
        return fill.filter { it.spells.size >= minOf(nearBuildable, fill.firstOrNull()?.spells?.size ?: 0) }
            .filter { it.powerScore >= (fill.firstOrNull()?.powerScore ?: 0.0) - 12.0 }
            .distinctBy { it.colors }
            .take(maxOptions)
    }

    private fun strengthFor(pair: String, pairStrength: Map<String, Double>): Double? = pairStrength[pair]

    private fun deckContents(option: DeckOption) = Triple(option.colors,
        option.spells.groupingBy { it.name }.eachCount(), option.nonbasicLands.groupingBy { it.name }.eachCount())

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
        allowSplash: Boolean = true,
        requiredSplash: Char? = null,
        threeColorPlan: ThreeColorPlan? = null,
        thirdColorPipLimit: Int = Int.MAX_VALUE,
        requiredThirdColorCards: Int = 0,
    ): DeckOption? {
        val threeColor = threeColorPlan != null
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
        fun cardScore(card: RankedCard): Double = rawScore(card) + themeNudge(card)

        fun landsFor(splashColor: Char?): List<RankedCard> {
            val deckColors = pairSet + setOfNotNull(splashColor)
            return lands.asSequence()
                .filter { card ->
                    val produced = meta(card.name)?.producedColors.orEmpty()
                    val identity = produced.ifEmpty { meta(card.name)?.landcyclingColors.orEmpty().ifEmpty { castingColorsOf(card, meta(card.name)) } }
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

        val eligible = spells.filter { card ->
            if (onColor(card)) return@filter true
            if (!threeColor || requiredSplash == null) return@filter false
            val m = meta(card.name) ?: return@filter false
            val pips = m.coloredPips[requiredSplash] ?: if (requiredSplash in m.heavyPipColors) 2 else 1
            requiredSplash in singleColorSplashOptions(card, pairSet, m) && pips <= thirdColorPipLimit
        }
        val curvePolicy = adaptiveCurvePolicy(policy, pair, synergy)
        val requiredCards = eligible.filterNot(::onColor).sortedByDescending(::cardScore).take(requiredThirdColorCards)
        if (requiredCards.size < requiredThirdColorCards) return null
        val base = selectSpells(eligible, metrics, meta, ::cardScore, ::rawScore, curvePolicy, requiredCards).toMutableList()

        var splashedSpells = emptyList<RankedCard>()
        var selectedSplashColor: Char? = null
        if (threeColor) {
            selectedSplashColor = requiredSplash
            splashedSpells = base.filterNot(::onColor)
            if (splashedSpells.isEmpty() || base.size < policy.spellSlots) return null
            if (threeColorPlan == ThreeColorPlan.CONSERVATIVE && splashedSpells.size > policy.maximumSplashCards) return null
            base.removeAll(splashedSpells.toSet())
        } else if (upgrade) {
            // Upgrade variants only exist for a complete base; short bases are
            // handled by the normal top-up pass below.
            if (base.size < policy.spellSlots) return null
            val swap = upgradeSplashCandidates(base, spells, pairSet, metrics, ::onColor, ::cardScore, meta, policy)
                .filter { requiredSplash == null || it.color == requiredSplash }
                .firstOrNull { candidate ->
                    val chosen = base.toMutableList().apply { candidate.removed.forEach(::remove) } + candidate.added
                    manaFor(chosen, candidate.color).splashFeasible
                } ?: return null
            swap.removed.forEach(base::remove)
            splashedSpells = swap.added
            selectedSplashColor = swap.color
        } else if (allowSplash) {
            val splash = splashCandidates(
                spells, pairSet, policy.spellSlots - base.size, ::onColor, ::cardScore, meta, policy, metrics,
            ).filter { requiredSplash == null || it.color == requiredSplash }.firstOrNull { candidate ->
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
        // Even an incomplete projection needs repeated evidence for both base colors.
        if (totalPips == 0 || minBasePips < 2) return null
        if (!lenient && (minBasePips < policy.minimumBaseColorPips || minBasePips.toDouble() / totalPips < policy.minimumBaseColorRatio)) return null

        val onColorLands = landsFor(splashColor)

        val metas = chosen.map { meta(it.name) }
        val creatures = metas.count { it?.isCreature == true }
        val removal = metas.count { it?.isRemoval == true }
        val landFixers = onColorLands.count { meta(it.name)?.isFixing == true }
        val landCount = (policy.deckSize - chosen.size).coerceAtLeast(policy.landSlots)
        // A substantial third color needs its spells on curve, unlike a few late splash cards.
        val manaPolicy = if (threeColor && splashedSpells.size > policy.maximumSplashCards)
            policy.copy(splashCastingDelay = 0) else policy
        val manaSources = ManaSources.estimate(chosen, pair, splashColor, onColorLands, meta, manaPolicy, landCount,
            allowBaseShortfall = threeColor, baseAllocationTarget = threeColorPlan?.minimumBaseSources ?: policy.minimumBaseSources)
        // A splash is only real when it can be funded after both base colors.
        if (threeColor) {
            // A compromise needs real fixing and a usable base. Unsupported
            // piles must not slip through just because their cards are strong.
            val sharedSources = onColorLands.sumOf { (meta(it.name)?.producedColors.orEmpty().count { c -> c in deckColors } - 1).coerceAtLeast(0) } +
                manaSources.fixerSources.values.sum()
            val plan = threeColorPlan!!
            val thirdColorShortfall = if (plan == ThreeColorPlan.BOLD) 2 else 0
            if (sharedSources < 2 || manaSources.splashShortfall > thirdColorShortfall ||
                (manaSources.totalSources[splashColor] ?: 0) < policy.minimumSplashSources ||
                manaSources.shortfalls.values.sum() > plan.maximumShortfall || pairSet.any {
                    (manaSources.totalSources[it] ?: 0) < plan.minimumBaseSources
                }) return null
        } else if (splashColor != null && !manaSources.splashFeasible) return null
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
            theme = if (threeColor) null else synergy?.archetype(pair)?.name,
            powerScore = power.score,
            tier = tierOf(power.score),
            type = identity.pace.label,
            outlook = DeckAnalysis.outlook(power),
            deckWinRate = power.qualityRate,
            identityConfidence = identity.confidenceLabel,
            identityReasons = identity.reasons,
            powerConfidence = power.confidenceLabel,
            powerReasons = (if (threeColor) listOf(
                "${threeColorPlan!!.label}: ${splashedSpells.size} third-color cards; stronger cards at the cost of mana consistency",
                "${manaSources.shortfalls.values.sum()} colored sources short of on-curve targets; " +
                    manaSources.requiredSources.entries.joinToString { (color, required) -> "$color ${manaSources.totalSources[color] ?: 0}/$required" },
            ) else if (splashColor != null) listOf(
                "Splash justified by " + splashedSpells.mapNotNull { card -> HaymakerAssessment.reason(card, metrics, meta(card.name))?.let { "${card.name}: $it" } }.joinToString(),
                "$splashColor sources: ${manaSources.totalSources[splashColor] ?: 0}" +
                    (manaSources.landcyclingSources[splashColor]?.takeIf { it > 0 }?.let { " ($it landcycling)" } ?: "") +
                    (manaSources.selectionCredit[splashColor]?.takeIf { it > 0.0 }?.let { " + %.2f selection credit".format(java.util.Locale.ROOT, it) } ?: "") +
                    "/${manaSources.requiredSources[splashColor] ?: 0} required; base colors supported",
            ) else emptyList()) + power.reasons,
            spells = chosen.sortedWith(compareBy({ meta(it.name)?.cmc ?: 9 }, { -(it.gihWr ?: 0.0) })),
            nonbasicLands = onColorLands,
            landCount = landCount,
            creatures = creatures,
            removal = removal,
            curve = curveOf(metas),
            manaSources = manaSources,
            hasObservedPower = power.hasObservedPower,
            isThreeColor = threeColor,
            threeColorPlan = threeColorPlan,
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
            val impactful = isSplashHaymaker(card, metrics, m)
            if (impactful || isStrongSplashCompanion(card, metrics)) splashColors.forEach { color ->
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
                if (added.any { isSplashHaymaker(it, metrics, meta(it.name)) }) {
                    prefixes += SplashUpgrade(color, removed.toList(), added.toList())
                }
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
        metrics: SetMetrics,
    ): List<SplashChoice> {
        if (deficit <= 0) return emptyList()
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val m = meta(card.name) ?: continue
            if (!isSplashHaymaker(card, metrics, m) && !isStrongSplashCompanion(card, metrics)) continue
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
            .filter { choice -> choice.cards.any { isSplashHaymaker(it, metrics, meta(it.name)) } }
            .sortedByDescending { choice -> choice.cards.sumOf(cardScore) }
    }

    private fun isStrongSplashCompanion(card: RankedCard, metrics: SetMetrics): Boolean =
        card.rating?.hasReliableWinRate == true && (metrics.z(card.gihWr) ?: 0.0) >= 1.0

    internal fun isSplashHaymaker(
        card: RankedCard,
        metrics: SetMetrics,
        meta: CardMeta? = null,
    ): Boolean = HaymakerAssessment.reason(card, metrics, meta) != null

    /** Set-wide archetype speeds supply a prior; the specific pair supplies the stronger signal. */
    internal fun adaptiveCurvePolicy(
        policy: DeckConstructionPolicy,
        pair: String,
        synergy: SynergyIndex?,
    ): DeckConstructionPolicy {
        if (synergy == null) return policy
        fun speed(value: String): Double? = when (value.lowercase()) {
            "aggro" -> -1.0
            "tempo" -> -0.5
            "midrange" -> 0.0
            "control", "ramp" -> 1.0
            else -> null
        }
        val setSpeed = synergy.profile.archetypes.mapNotNull { speed(it.speed) }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val pairSpeed = speed(synergy.archetype(pair)?.speed.orEmpty()) ?: setSpeed
        val pace = pairSpeed * 0.7 + setSpeed * 0.3
        val curve = when {
            pace <= -0.3 -> linkedMapOf(2 to 6, 1 to 2, 3 to 4, 4 to 2, 5 to 1, 6 to 0)
            pace >= 0.3 -> linkedMapOf(2 to 4, 3 to 3, 4 to 3, 5 to 3, 6 to 2, 1 to 0)
            else -> policy.creatureCurve
        }
        return policy.copy(creatureCurve = curve)
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
        // Legacy metadata may know only the double-pip color. That is not a
        // complete casting identity: preserve the other printed colors.
        val exactPips = meta != null && (meta.coloredPips.isNotEmpty() || meta.hybridPips.isNotEmpty())
        return if (exactPips) metadataColors else metadataColors + LaneDetector.colorsOf(card)
    }

    private fun singleColorSplashOptions(card: RankedCard, baseColors: Set<Char>, meta: CardMeta): Set<Char> =
        LaneDetector.uncastableColorOptions(
            colors = castingColorsOf(card, meta),
            available = baseColors,
            hybridGroups = meta.hybridColorGroups,
            pureColors = meta.exactPureColorsOrNull(),
        ).mapNotNull { it.singleOrNull() }.toSet()

    private fun CardMeta.exactPureColorsOrNull(): Set<Char>? =
        coloredPips.keys.takeIf { coloredPips.isNotEmpty() || hybridPips.isNotEmpty() }

    private fun selectSpells(
        eligible: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        cardScore: (RankedCard) -> Double,
        capScore: (RankedCard) -> Double = cardScore,
        policy: DeckConstructionPolicy,
        requiredCards: List<RankedCard> = emptyList(),
    ): List<RankedCard> {
        // Limited allows repeated copies. Compare each copy on quality and role;
        // a name-based cap can discard better cards and leave a complete pool short.
        val candidates = eligible
            .map { card ->
                val m = meta(card.name)
                Candidate(card, cardScore(card), m?.cmc ?: 3, m?.isCreature == true, m?.isRemoval == true)
            }
            .sortedByDescending { it.score }

        val creatures = candidates.filter { it.isCreature }
        val others = candidates.filterNot { it.isCreature }

        val chosen = LinkedHashSet<Candidate>()
        val requiredCounts = requiredCards.groupingBy { it.name }.eachCount().toMutableMap()
        for (candidate in candidates) {
            val needed = requiredCounts[candidate.card.name] ?: 0
            if (needed > 0) {
                chosen.add(candidate)
                requiredCounts[candidate.card.name] = needed - 1
            }
        }
        val byBucket = creatures.groupBy { curveBucket(it.cmc) }
        for ((bucket, target) in policy.creatureCurve) {
            // Committed third-color spells already occupy curve space, even
            // when they supply interaction or card advantage rather than bodies.
            val missing = (target - chosen.count { curveBucket(it.cmc) == bucket }).coerceAtLeast(0)
            byBucket[bucket].orEmpty().filterNot { it in chosen }
                .take(minOf(missing, (policy.spellSlots - chosen.size).coerceAtLeast(0))).forEach { chosen.add(it) }
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
        val requiredNonCreatures = requiredCards.count { meta(it.name)?.isCreature != true }
        while (chosen.size < policy.spellSlots && (creatureQueue.isNotEmpty() || otherQueue.isNotEmpty())) {
            val c = creatureQueue.firstOrNull()
            val o = otherQueue.firstOrNull()
            val pick = when {
                c == null -> otherQueue.removeFirst().also { otherCount++ }
                o == null -> creatureQueue.removeFirst()
                otherCount >= policy.nonCreatureCap + requiredNonCreatures -> creatureQueue.removeFirst()
                creatureCount < policy.creatureTarget - requiredNonCreatures && c.score >= o.score - CREATURE_BIAS -> creatureQueue.removeFirst()
                c.score > o.score -> creatureQueue.removeFirst()
                else -> otherQueue.removeFirst().also { otherCount++ }
            }
            if (chosen.add(pick) && pick.isCreature) creatureCount++
        }
        return chosen.map { it.card }
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
