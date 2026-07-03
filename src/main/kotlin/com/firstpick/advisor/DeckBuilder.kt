package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole

data class DeckOption(
    val colors: String,
    val basePair: String,
    val splash: Char?,
    val theme: String? = null,
    val powerScore: Double,
    val tier: String,
    val type: String,
    val outlook: String,
    val deckWinRate: Double,
    val spells: List<RankedCard>,
    val nonbasicLands: List<RankedCard>,
    val landCount: Int,
    val creatures: Int,
    val removal: Int,
    val curve: List<Pair<String, Int>>,
)

object DeckBuilder {
    private const val SPELL_SLOTS = 23
    private const val LAND_SLOTS = 17
    private const val DECK_SIZE = 40

    private const val MAX_SPLASH = 4
    private const val MIN_DECK_SPELLS = 21

    private const val CREATURE_TARGET = 15
    private const val NONCREATURE_CAP = 8
    private const val CREATURE_BIAS = 0.015
    private val CREATURE_CURVE = linkedMapOf(2 to 4, 3 to 4, 4 to 3, 5 to 2, 6 to 1, 1 to 1)

    private const val MIN_COLOR_PIPS = 4
    private const val MIN_COLOR_RATIO = 0.20

    private const val INCOMPLETE_SPELL_PENALTY = 3.0
    private const val UNFIXED_SPLASH_PENALTY = 4.0
    private const val SPLASH_FIXING_REWARD = 1.0

    // Win-rate-equivalent nudges: enough to flip near-ties toward the pair's theme,
    // never enough to seat a clearly weaker card over a stronger one.
    private const val SIGNPOST_NUDGE = 0.010
    private const val THEME_NUDGE = 0.008
    private const val KEY_NUDGE = 0.005

    fun build(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
        synergy: SynergyIndex? = null,
    ): List<DeckOption> {
        val spells = pool.filter { meta(it.name)?.isLand != true }
        val lands = pool.filter { meta(it.name)?.isLand == true }

        fun pass(minSpells: Int) = COLOR_PAIRS.mapNotNull { pair ->
            buildForPair(pair, spells, lands, metrics, meta, archetypeRating, strengthFor(pair, pairStrength), minSpells, synergy)
        }

        val options = pass(MIN_DECK_SPELLS).ifEmpty { pass(0) }
        return options
            .sortedByDescending { it.powerScore }
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
        val splashedSpells = chooseSplash(spells, pairSet, SPELL_SLOTS - base.size, ::onColor, ::cardScore)
        val chosen = base + splashedSpells
        val splashColor = splashedSpells.firstOrNull()
            ?.let { card -> LaneDetector.colorsOf(card).firstOrNull { it !in pairSet } }
        val deckColors = pairSet + setOfNotNull(splashColor)

        if (chosen.size < minSpells) return null

        val pips = mutableMapOf<Char, Int>()
        for (card in chosen) for (ch in LaneDetector.colorsOf(card)) if (ch in deckColors) pips.merge(ch, 1, Int::plus)
        val totalPips = pips.values.sum()
        val minBasePips = pairSet.minOf { pips[it] ?: 0 }
        if (totalPips == 0 || minBasePips < MIN_COLOR_PIPS || minBasePips.toDouble() / totalPips < MIN_COLOR_RATIO) return null

        val onColorLands = lands.filter { card ->
            val colors = LaneDetector.colorsOf(card)
            colors.isEmpty() || deckColors.containsAll(colors)
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

    private fun chooseSplash(
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        deficit: Int,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
    ): List<RankedCard> {
        if (deficit <= 0) return emptyList()
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val extra = LaneDetector.colorsOf(card).filter { it !in pairSet }
            if (extra.size != 1) continue
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
                // Copy caps come from the un-nudged score: theme bonuses break ties in
                // ordering but must not promote below-average cards to extra copies.
                val cap = copyCap(metrics.z(capScore(copies.first())) ?: 0.0)
                copies.sortedByDescending { it.gihWr ?: 0.0 }.take(cap)
            }
            .map { card ->
                val m = meta(card.name)
                Candidate(card, cardScore(card), m?.cmc ?: 3, m?.isCreature == true)
            }
            .sortedByDescending { it.score }

        val creatures = candidates.filter { it.isCreature }
        val others = candidates.filterNot { it.isCreature }

        val chosen = LinkedHashSet<Candidate>()
        val byBucket = creatures.groupBy { curveBucket(it.cmc) }
        for ((bucket, target) in CREATURE_CURVE) {
            byBucket[bucket].orEmpty().take(target).forEach { chosen.add(it) }
        }

        val creatureQueue = ArrayDeque(creatures.filterNot { it in chosen })
        val otherQueue = ArrayDeque(others)
        var creatureCount = chosen.size
        var otherCount = 0
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
