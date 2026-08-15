package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.RankedCard
import com.firstpick.guide.DeckConstructionPolicy

data class ManaSourceReport(
    val requiredSources: Map<Char, Int>,
    val basicSources: Map<Char, Int>,
    val nonbasicSources: Map<Char, Int>,
    val fixerSources: Map<Char, Int>,
    val totalSources: Map<Char, Int>,
    val shortfalls: Map<Char, Int>,
    val baseColors: Set<Char>,
    val splashColor: Char?,
) {
    val baseShortfall: Int get() = baseColors.sumOf { shortfalls[it] ?: 0 }
    val splashShortfall: Int get() = splashColor?.let { shortfalls[it] ?: 0 } ?: 0
    val splashSupported: Boolean get() = splashColor == null || splashShortfall == 0
    val allRequirementsMet: Boolean get() = shortfalls.values.all { it == 0 }
    /** A third color is legal only when it leaves every base and splash target intact. */
    val splashFeasible: Boolean get() = splashColor == null || allRequirementsMet
}

/** Allocates the actual 17 land slots to base colors before considering a splash. */
object ManaSources {
    private val COLORS = "WUBRG"
    private data class SourceNeeds(val required: Map<Char, Int>, val castingTurn: Map<Char, Int>)

    fun estimate(
        spells: List<RankedCard>,
        basePair: String,
        splashColor: Char?,
        nonbasicLands: List<RankedCard>,
        meta: (String) -> CardMeta?,
        policy: DeckConstructionPolicy,
        landSlots: Int = policy.landSlots,
    ): ManaSourceReport {
        val baseColors = basePair.filter { it in COLORS }.toSet()
        val deckColors = baseColors + setOfNotNull(splashColor)
        val needs = sourceRequirements(spells, baseColors, splashColor, meta, policy)
        val requirements = needs.required

        val nonbasic = deckColors.associateWith { color ->
            nonbasicLands.count { land -> color in meta(land.name)?.producedColors.orEmpty() }
        }
        val fixers = deckColors.associateWith { color ->
            spells.count { card ->
                val m = meta(card.name) ?: return@count false
                if (!m.isFixing || m.isLand) return@count false
                val produced = m.producedColors
                val canProduce = color in produced || (produced.isEmpty() && color == splashColor)
                val needsCreditedColorToCast = (coloredPips(card, m, baseColors, splashColor)[color] ?: 0) > 0
                canProduce && !needsCreditedColorToCast && m.cmc < (needs.castingTurn[color] ?: Int.MAX_VALUE)
            }
        }
        val basics = deckColors.associateWith { 0 }.toMutableMap()
        var basicsLeft = (landSlots - nonbasicLands.size).coerceAtLeast(0)

        fun sources(color: Char): Int =
            (basics[color] ?: 0) + (nonbasic[color] ?: 0) + (fixers[color] ?: 0)

        // A splash is never funded by making a base color unreliable. Meet both
        // base targets first, balancing the two colors as basics are assigned.
        while (basicsLeft > 0) {
            val color = baseColors
                .filter { sources(it) < (requirements[it] ?: 0) }
                .maxWithOrNull(
                    compareBy<Char> { ((requirements[it] ?: 0) - sources(it)).toDouble() / (requirements[it] ?: 1).coerceAtLeast(1) }
                        .thenBy { -COLORS.indexOf(it) },
                ) ?: break
            basics[color] = (basics[color] ?: 0) + 1
            basicsLeft--
        }

        if (splashColor != null) {
            while (basicsLeft > 0 && sources(splashColor) < (requirements[splashColor] ?: 0)) {
                basics[splashColor] = (basics[splashColor] ?: 0) + 1
                basicsLeft--
            }
        }

        // Extra basics still follow colored demand instead of being left
        // unassigned in the report.
        while (basicsLeft > 0 && baseColors.isNotEmpty()) {
            val color = baseColors.maxWithOrNull(
                compareBy<Char> { pipDemand(spells, it, baseColors, splashColor, meta).toDouble() / (sources(it) + 1) }
                    .thenBy { -COLORS.indexOf(it) },
            )!!
            basics[color] = (basics[color] ?: 0) + 1
            basicsLeft--
        }

        val totals = deckColors.associateWith(::sources)
        val shortfalls = deckColors.associateWith { color ->
            ((requirements[color] ?: 0) - (totals[color] ?: 0)).coerceAtLeast(0)
        }
        return ManaSourceReport(
            requiredSources = requirements,
            basicSources = basics,
            nonbasicSources = nonbasic,
            fixerSources = fixers,
            totalSources = totals,
            shortfalls = shortfalls,
            baseColors = baseColors,
            splashColor = splashColor,
        )
    }

    private fun sourceRequirements(
        spells: List<RankedCard>,
        baseColors: Set<Char>,
        splashColor: Char?,
        meta: (String) -> CardMeta?,
        policy: DeckConstructionPolicy,
    ): SourceNeeds {
        val required = mutableMapOf<Char, Int>()
        val timing = mutableMapOf<Char, Int>()
        for (card in spells) {
            val m = meta(card.name)
            val demands = coloredPips(card, m, baseColors, splashColor)
            for ((color, pips) in demands) {
                if (color == splashColor) continue
                val castingTurn = m?.cmc?.coerceAtLeast(2) ?: 3
                val target = onCurveSources(pips, castingTurn) - policy.baseSourceRelaxation
                if (target > (required[color] ?: 0)) {
                    required[color] = target
                    timing[color] = castingTurn
                } else if (target == required[color]) {
                    timing[color] = minOf(timing[color] ?: castingTurn, castingTurn)
                }
            }
        }
        for (color in baseColors) {
            required[color] = maxOf(required[color] ?: 0, policy.minimumBaseSources)
            timing.putIfAbsent(color, 3)
        }
        if (splashColor != null) {
            var splashRequirement = policy.minimumSplashSources
            var splashTurn = 7
            for (card in spells) {
                val m = meta(card.name)
                val pips = coloredPips(card, m, baseColors, splashColor)[splashColor] ?: 0
                if (pips == 0) continue
                val castingTurn = (m?.cmc ?: 3) + policy.splashCastingDelay
                val target = maxOf(policy.minimumSplashSources, onCurveSources(pips, castingTurn))
                if (target > splashRequirement) {
                    splashRequirement = target
                    splashTurn = castingTurn
                } else if (target == splashRequirement) {
                    splashTurn = minOf(splashTurn, castingTurn)
                }
            }
            required[splashColor] = splashRequirement
            timing[splashColor] = splashTurn
        }
        val colors = "WUBRG".filter { it in baseColors || it == splashColor }
        return SourceNeeds(
            required = colors.associateWith { required[it] ?: 0 },
            castingTurn = colors.associateWith { timing[it] ?: 3 },
        )
    }

    private fun coloredPips(
        card: RankedCard,
        meta: CardMeta?,
        baseColors: Set<Char>,
        splashColor: Char?,
    ): Map<Char, Int> {
        val deckColors = baseColors + setOfNotNull(splashColor)
        val out = meta?.coloredPips.orEmpty()
            .filterKeys { it in deckColors }
            .toMutableMap()
        if (out.isEmpty()) {
            val cardColors = LaneDetector.colorsOf(card).filter { it in deckColors }
            val entirelyHybrid = meta?.hybridColorGroups.orEmpty().any { group -> cardColors.isNotEmpty() && group.containsAll(cardColors) }
            if (!entirelyHybrid) cardColors.forEach { out[it] = 1 }
        }
        meta?.heavyPipColors.orEmpty().filter { it in deckColors }.forEach { out[it] = maxOf(out[it] ?: 0, 2) }

        val hybrids = meta?.hybridPips.orEmpty().ifEmpty { meta?.hybridColorGroups.orEmpty() }
        for (group in hybrids) {
            val basePayable = group.filter { it in baseColors }
            when {
                // Either base color pays this symbol, so it creates shared
                // demand rather than a false requirement for one color.
                basePayable.size >= 2 -> Unit
                basePayable.size == 1 -> {
                    val color = basePayable.single()
                    out[color] = (out[color] ?: 0) + 1
                }
                splashColor != null && splashColor in group ->
                    out[splashColor] = (out[splashColor] ?: 0) + 1
            }
        }
        return out
    }

    private fun pipDemand(
        spells: List<RankedCard>,
        color: Char,
        baseColors: Set<Char>,
        splashColor: Char?,
        meta: (String) -> CardMeta?,
    ): Int = spells.sumOf { card -> coloredPips(card, meta(card.name), baseColors, splashColor)[color] ?: 0 }

    /** 90%-style 40-card source targets, indexed by colored pips and mana value. */
    internal fun onCurveSources(coloredPips: Int, manaValue: Int): Int {
        val turn = manaValue.coerceIn(2, 7)
        return when {
            coloredPips >= 3 -> when (turn) { 2 -> 16; 3 -> 15; 4 -> 14; 5 -> 13; 6 -> 12; else -> 11 }
            coloredPips == 2 -> when (turn) { 2 -> 14; 3 -> 12; 4 -> 11; 5 -> 10; 6 -> 9; else -> 8 }
            else -> when (turn) { 2 -> 9; 3 -> 8; 4 -> 7; 5 -> 6; 6 -> 5; else -> 4 }
        }
    }
}
