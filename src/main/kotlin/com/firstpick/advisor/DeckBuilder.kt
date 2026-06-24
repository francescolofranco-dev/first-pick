package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics

/** A finished 40-card deck proposal built from the drafted pool. */
data class DeckOption(
    val pair: String,
    val powerScore: Double,
    val tier: String,
    val type: String,
    val outlook: String,
    val deckWinRate: Double,
    val spells: List<RankedCard>,
    val nonbasicLands: List<RankedCard>,
    val basics: Map<Char, Int>,
    val creatures: Int,
    val removal: Int,
    val curve: List<Pair<String, Int>>,
)

/**
 * Builds the best 40-card decks out of a completed draft pool. For each viable
 * color pair it picks the strongest on-color spells (preferring each card's win
 * rate within that pair), fills to ~17 lands, and rates the result — so the player
 * gets 2–3 concrete "build this" options with a power estimate.
 */
object DeckBuilder {
    private const val SPELL_SLOTS = 23
    private const val LAND_SLOTS = 17
    private const val MIN_PLAYABLES = 18

    /** Reject pairs where the second color is only a token splash (uncastable as 2-color). */
    private const val MIN_COLOR_PIPS = 4
    private const val MIN_COLOR_RATIO = 0.20

    fun build(
        pool: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta? = { null },
        archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
        pairStrength: Map<String, Double> = emptyMap(),
        maxOptions: Int = 3,
    ): List<DeckOption> {
        val spells = pool.filter { meta(it.name)?.isLand != true }
        val lands = pool.filter { meta(it.name)?.isLand == true }

        return (COLOR_PAIRS + TRI_COLORS)
            .mapNotNull { pair -> buildForPair(pair, spells, lands, metrics, meta, archetypeRating, pairStrength[pair]) }
            .sortedByDescending { it.powerScore }
            .take(maxOptions)
    }

    private fun buildForPair(
        pair: String,
        spells: List<RankedCard>,
        lands: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        archetypeRating: (String, String) -> CardRating?,
        strength: Double?,
    ): DeckOption? {
        val pairSet = pair.toSet()
        fun onColor(card: RankedCard): Boolean {
            val colors = LaneDetector.colorsOf(card)
            return colors.isEmpty() || pairSet.containsAll(colors)
        }

        fun cardScore(card: RankedCard): Double {
            val arch = archetypeRating(card.name, pair)?.gihWr
            return arch ?: card.gihWr ?: (metrics.meanGihWr - 0.02)
        }

        val eligible = spells.filter(::onColor)
        val chosen = eligible.sortedByDescending(::cardScore).take(SPELL_SLOTS).toMutableList()
        val deficit = SPELL_SLOTS - chosen.size
        
        val splashedSpells = if (deficit > 0) {
            val offColorSpells = spells.filterNot(::onColor)
            offColorSpells.sortedByDescending(::cardScore).take(deficit)
        } else emptyList()
        
        chosen.addAll(splashedSpells)
        val splashColors = splashedSpells.flatMap { LaneDetector.colorsOf(it) }.filter { it !in pairSet }.toSet()

        val onColorLands = lands.filter { card ->
            val colors = LaneDetector.colorsOf(card)
            colors.isEmpty() || pairSet.containsAll(colors) || colors.any { it in splashColors }
        }

        // Reject "fake" pairs — a mono deck with a token splash isn't castable as two colors.
        val pips = mutableMapOf<Char, Int>()
        for (card in chosen) {
            for (ch in LaneDetector.colorsOf(card)) {
                if (ch in pairSet || ch in splashColors) pips.merge(ch, 1, Int::plus)
            }
        }
        val totalPips = pips.values.sum()
        val minPips = pairSet.minOf { pips[it] ?: 0 }
        if (totalPips == 0 || minPips < MIN_COLOR_PIPS || minPips.toDouble() / totalPips < MIN_COLOR_RATIO) return null

        val deckWr = chosen.mapNotNull { it.gihWr }.ifEmpty { listOf(metrics.meanGihWr) }.average()
        val metas = chosen.map { meta(it.name) }
        val creatures = metas.count { it?.isCreature == true }
        val removal = metas.count { it?.isRemoval == true }
        val twoDrops = metas.count { it != null && it.isCreature && it.cmc in 1..2 }
        val avgCmc = metas.mapNotNull { it?.cmc }.ifEmpty { listOf(3) }.average()
        
        val fixersCount = chosen.count { meta(it.name)?.isFixing == true } + onColorLands.count { meta(it.name)?.isFixing == true }
        val splashCount = splashedSpells.size

        val power = powerScore(deckWr, metrics, strength, creatures, removal, twoDrops, splashCount, fixersCount)
        return DeckOption(
            pair = pair,
            powerScore = power,
            tier = tierOf(power),
            type = typeOf(avgCmc, twoDrops, removal),
            outlook = outlookOf(power),
            deckWinRate = deckWr,
            spells = chosen.sortedWith(compareBy({ meta(it.name)?.cmc ?: 9 }, { -(it.gihWr ?: 0.0) })),
            nonbasicLands = onColorLands,
            basics = basicSplit(pips, pairSet, (LAND_SLOTS - onColorLands.size).coerceAtLeast(0)),
            creatures = creatures,
            removal = removal,
            curve = curveOf(metas),
        )
    }

    /** Distribute basic lands across the pair's colors by the spells' colored-pip weight. */
    private fun basicSplit(pips: Map<Char, Int>, pairSet: Set<Char>, slots: Int): Map<Char, Int> {
        if (slots <= 0) return emptyMap()
        val total = pips.values.sum()
        if (total == 0) return pairSet.associateWith { slots / pairSet.size }
        val out = pips.mapValues { (it.value * slots) / total }.toMutableMap()
        // Hand any rounding remainder to the most-demanded color.
        var assigned = out.values.sum()
        val order = pips.entries.sortedByDescending { it.value }.map { it.key }
        var i = 0
        while (assigned < slots && order.isNotEmpty()) {
            val c = order[i % order.size]
            out[c] = (out[c] ?: 0) + 1
            assigned++; i++
        }
        return out
    }

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
        fixersCount: Int
    ): Double {
        val z = metrics.z(deckWr) ?: 0.0
        var score = 50.0 + 18.0 * z
        // Archetype tailwind, plus a small reward for a complete backbone.
        strength?.let { score += (it - metrics.meanGihWr) * 60.0 }
        if (removal >= 3) score += 3.0
        if (creatures in 13..18) score += 3.0
        if (twoDrops >= 6) score += 2.0
        
        // Splash penalty logic: penalize off-color cards that don't have supporting fixers
        if (splashCount > 0) {
            val unmitigatedSplash = (splashCount - fixersCount).coerceAtLeast(0)
            score -= (unmitigatedSplash * 4.0)
            // Small reward for having excess fixing when splashing
            if (fixersCount > splashCount) {
                score += ((fixersCount - splashCount) * 1.0).coerceAtMost(3.0)
            }
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
