package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole


object DeckProjector {
    private const val SPELL_SLOTS = 23
    private const val LAND_SLOTS = 17
    private const val DECK_SIZE = 40

    private const val MAX_SPLASH = 4
    private const val MIN_DECK_SPELLS = 21

    private const val CREATURE_TARGET = 15
    private const val NONCREATURE_CAP = 8
    private const val CREATURE_BIAS = 0.015
    private val CREATURE_CURVE = linkedMapOf(2 to 4, 3 to 4, 4 to 3, 5 to 2, 6 to 1, 1 to 1)


    private const val REMOVAL_TARGET = 4
    private const val REMOVAL_MIN_Z = -0.75

    private const val MIN_COLOR_PIPS = 4
    private const val MIN_COLOR_RATIO = 0.20

    private const val INCOMPLETE_SPELL_PENALTY = 3.0
    private const val UNFIXED_SPLASH_PENALTY = 4.0
    private const val SPLASH_FIXING_REWARD = 1.0
    private const val SPLASH_UPGRADE_MARGIN = 0.02


    private const val SIGNPOST_NUDGE = 0.010
    private const val THEME_NUDGE = 0.008
    private const val KEY_NUDGE = 0.005


    data class Fit(

        val makesDeck: Boolean,

        val displaced: List<String>,

        val baseShifted: Boolean,

        val splashAdded: Char?,

        val powerDelta: Double,
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
        return Fit(makesDeck, displaced, baseShifted, splashAdded, powerDelta)
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
    ): DeckOption? = projectAll(pool, metrics, meta, archetypeRating, pairStrength, maxOptions = 1, synergy = synergy).firstOrNull()

    fun projectAll(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
        synergy: SynergyIndex? = null,
    ): List<DeckOption> {


        val known = pool.filter { it.rating != null || meta(it.name) != null }
        val spells = known.filter { meta(it.name)?.isLand != true }
        val lands = known.filter { meta(it.name)?.isLand == true }

        fun pass(minSpells: Int, lenient: Boolean = false, upgrade: Boolean = false) = COLOR_PAIRS.mapNotNull { pair ->
            buildForPair(pair, spells, lands, metrics, meta, archetypeRating, strengthFor(pair, pairStrength), minSpells, synergy, lenient, upgrade)
        }


        val main = (pass(MIN_DECK_SPELLS) + pass(MIN_DECK_SPELLS, upgrade = true))
            .sortedByDescending { it.powerScore }
        val fill = pass(0, lenient = true).sortedByDescending { it.powerScore }
        return (main + fill)
            .distinctBy { it.colors }
            .take(maxOptions)
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
        lenient: Boolean = false,
        upgrade: Boolean = false,
    ): DeckOption? {
        val pairSet = pair.toSet()
        fun onColor(card: RankedCard): Boolean {
            val colors = LaneDetector.colorsOf(card)
            val hybridGroups = meta(card.name)?.hybridColorGroups.orEmpty()
            return colors.isEmpty() || LaneDetector.uncastableColors(colors, pairSet, hybridGroups).isEmpty()
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
            val arch = archetypeRating(card.name, pair)?.gihWr
            return arch ?: card.gihWr ?: (metrics.meanGihWr - 0.02)
        }
        fun cardScore(card: RankedCard): Double = rawScore(card) + themeNudge(card)

        val eligible = spells.filter(::onColor)
        val base = selectSpells(eligible, metrics, meta, ::cardScore, ::rawScore).toMutableList()

        val baseFixers = base.count { meta(it.name)?.isFixing == true } +
            lands.count { val c = LaneDetector.colorsOf(it); (c.isEmpty() || pairSet.containsAll(c)) && meta(it.name)?.isFixing == true }
        var splashedSpells = chooseSplash(spells, pairSet, SPELL_SLOTS - base.size, ::onColor, ::cardScore, meta)
        if (upgrade) {


            if (splashedSpells.isNotEmpty()) return null
            val swap = upgradeSplash(base, spells, pairSet, metrics, ::onColor, ::cardScore, meta) ?: return null
            for (r in swap.removed) base.remove(r)
            splashedSpells = swap.added
        }
        val chosen = base + splashedSpells
        val splashColor = splashedSpells.firstOrNull()
            ?.let { card -> LaneDetector.colorsOf(card).firstOrNull { it !in pairSet } }
        val deckColors = pairSet + setOfNotNull(splashColor)

        if (chosen.size < minSpells) return null

        val pips = mutableMapOf<Char, Int>()
        for (card in chosen) for (ch in LaneDetector.colorsOf(card)) if (ch in deckColors) pips.merge(ch, 1, Int::plus)
        val totalPips = pips.values.sum()
        val minBasePips = pairSet.minOf { pips[it] ?: 0 }
        if (totalPips == 0) return null
        if (!lenient && (minBasePips < MIN_COLOR_PIPS || minBasePips.toDouble() / totalPips < MIN_COLOR_RATIO)) return null

        val onColorLands = lands.filter { card ->


            val produced = meta(card.name)?.producedColors.orEmpty()
            val identity = produced.ifEmpty { LaneDetector.colorsOf(card) }
            identity.isEmpty() || deckColors.containsAll(identity) || identity.containsAll(deckColors)
        }

        val deckWr = chosen.mapNotNull { it.gihWr }.ifEmpty { listOf(metrics.meanGihWr) }.average()
        val metas = chosen.map { meta(it.name) }
        val creatures = metas.count { it?.isCreature == true }
        val removal = metas.count { it?.isRemoval == true }
        val twoDrops = metas.count { it != null && it.isCreature && it.cmc in 1..2 }
        val avgCmc = metas.mapNotNull { it?.cmc }.ifEmpty { listOf(3) }.average()
        val fixersCount = chosen.count { meta(it.name)?.isFixing == true } + onColorLands.count { meta(it.name)?.isFixing == true }

        val power = powerScore(deckWr, metrics, strength, creatures, removal, twoDrops, splashedSpells.size, fixersCount, chosen.size)
        val landCount = (DECK_SIZE - chosen.size).coerceAtLeast(LAND_SLOTS)
        return DeckOption(
            colors = "WUBRG".filter { it in deckColors },
            basePair = "WUBRG".filter { it in pairSet },
            splash = splashColor,
            theme = synergy?.archetype(pair)?.name,
            powerScore = power,
            tier = tierOf(power),
            type = typeOf(avgCmc, twoDrops, removal),
            outlook = outlookOf(power),
            deckWinRate = deckWr,
            spells = chosen.sortedWith(compareBy({ meta(it.name)?.cmc ?: 9 }, { -(it.gihWr ?: 0.0) })),
            nonbasicLands = onColorLands,
            landCount = landCount,
            creatures = creatures,
            removal = removal,
            curve = curveOf(metas),
        )
    }

    private class SplashUpgrade(val removed: List<RankedCard>, val added: List<RankedCard>)


    private fun upgradeSplash(
        base: List<RankedCard>,
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        metrics: SetMetrics,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
        meta: (String) -> CardMeta?,
    ): SplashUpgrade? {
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val extra = LaneDetector.colorsOf(card).filter { it !in pairSet }
            if (extra.size != 1) continue
            if (extra.first() in meta(card.name)?.heavyPipColors.orEmpty()) continue
            val m = meta(card.name)
            val impactful = m?.isRemoval == true || m?.isFinisher == true ||
                (card.gihWr ?: 0.0) >= metrics.meanGihWr + metrics.stdDevGihWr
            if (impactful) byColor.getOrPut(extra.first()) { mutableListOf() }.add(card)
        }
        val best = byColor.values.maxByOrNull { cards -> cards.maxOf(cardScore) } ?: return null
        val candidates = best.sortedByDescending(cardScore)
        val weakestFirst = base.sortedBy(cardScore)
        val removed = mutableListOf<RankedCard>()
        val added = mutableListOf<RankedCard>()
        for ((i, cand) in candidates.withIndex()) {
            if (i >= MAX_SPLASH || i >= weakestFirst.size) break
            if (cardScore(cand) < cardScore(weakestFirst[i]) + SPLASH_UPGRADE_MARGIN) break
            removed += weakestFirst[i]
            added += cand
        }
        if (added.isEmpty()) return null
        return SplashUpgrade(removed, added)
    }

    private fun chooseSplash(
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        deficit: Int,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
        meta: (String) -> CardMeta?,
    ): List<RankedCard> {
        if (deficit <= 0) return emptyList()
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val extra = LaneDetector.colorsOf(card).filter { it !in pairSet }
            if (extra.size != 1) continue

            if (extra.first() in meta(card.name)?.heavyPipColors.orEmpty()) continue
            byColor.getOrPut(extra.first()) { mutableListOf() }.add(card)
        }
        val best = byColor.values.maxByOrNull { cards -> cards.maxOf(cardScore) } ?: return emptyList()
        return best.sortedByDescending(cardScore).take(minOf(deficit, MAX_SPLASH))
    }

    private class Candidate(
        val card: RankedCard,
        val score: Double,
        val cmc: Int,
        val isCreature: Boolean,
        val isRemoval: Boolean,
    )

    private fun selectSpells(
        eligible: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        cardScore: (RankedCard) -> Double,
        capScore: (RankedCard) -> Double = cardScore,
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
        for ((bucket, target) in CREATURE_CURVE) {
            byBucket[bucket].orEmpty().take(target).forEach { chosen.add(it) }
        }


        var removalSeated = chosen.count { it.isRemoval }
        for (cand in others) {
            if (removalSeated >= REMOVAL_TARGET || chosen.size >= SPELL_SLOTS) break
            if (!cand.isRemoval) continue
            if ((metrics.z(capScore(cand.card)) ?: 0.0) < REMOVAL_MIN_Z) continue
            if (chosen.add(cand)) removalSeated++
        }

        val creatureQueue = ArrayDeque(creatures.filterNot { it in chosen })
        val otherQueue = ArrayDeque(others.filterNot { it in chosen })
        var creatureCount = chosen.count { it.isCreature }
        var otherCount = chosen.size - creatureCount
        while (chosen.size < SPELL_SLOTS && (creatureQueue.isNotEmpty() || otherQueue.isNotEmpty())) {
            val c = creatureQueue.firstOrNull()
            val o = otherQueue.firstOrNull()
            val pick = when {
                c == null -> otherQueue.removeFirst().also { otherCount++ }
                o == null -> creatureQueue.removeFirst()
                otherCount >= NONCREATURE_CAP -> creatureQueue.removeFirst()
                creatureCount < CREATURE_TARGET && c.score >= o.score - CREATURE_BIAS -> creatureQueue.removeFirst()
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

    private fun powerScore(
        deckWr: Double,
        metrics: SetMetrics,
        strength: Double?,
        creatures: Int,
        removal: Int,
        twoDrops: Int,
        splashCount: Int,
        fixersCount: Int,
        spellCount: Int,
    ): Double {
        val z = metrics.z(deckWr) ?: 0.0
        var score = 50.0 + 18.0 * z
        strength?.let { score += (it - metrics.meanGihWr) * 60.0 }
        if (removal >= 3) score += 3.0
        if (creatures in 13..18) score += 3.0
        if (twoDrops >= 6) score += 2.0

        if (spellCount < SPELL_SLOTS) score -= (SPELL_SLOTS - spellCount) * INCOMPLETE_SPELL_PENALTY

        if (splashCount > 0) {
            val unmitigated = (splashCount - fixersCount).coerceAtLeast(0)
            score -= unmitigated * UNFIXED_SPLASH_PENALTY
            if (fixersCount > splashCount) score += ((fixersCount - splashCount) * SPLASH_FIXING_REWARD).coerceAtMost(3.0)
        }

        return score.coerceIn(0.0, 100.0)
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

    private fun typeOf(avgCmc: Double, twoDrops: Int, removal: Int): String = when {
        avgCmc < 2.8 && twoDrops >= 6 -> "Aggro"
        avgCmc > 3.4 && removal >= 4 -> "Control"
        else -> "Midrange"
    }

    private fun outlookOf(power: Double): String = when {
        power >= 72 -> "Strong — aim for 6+ wins"
        power >= 60 -> "Solid — 4–5 wins"
        power >= 50 -> "Playable — 3–4 wins"
        else -> "Risky — needs the draw"
    }
}
