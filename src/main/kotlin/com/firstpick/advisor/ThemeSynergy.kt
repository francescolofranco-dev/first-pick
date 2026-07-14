package com.firstpick.advisor

import com.firstpick.cards.RankedCard
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyRole


class ThemeSynergy(
    private val index: SynergyIndex,
    pool: List<RankedCard>,
) {
    data class Result(val points: Double, val reasons: List<String>) {
        companion object { val NONE = Result(0.0, emptyList()) }
    }

    private class Fuel(var enabler: Double = 0.0, var payoff: Double = 0.0, var total: Double = 0.0)

    private val fuel = mutableMapOf<String, Fuel>()
    private val poolNames: Set<String> = pool.map { SynergyIndex.normalize(it.name) }.toSet()

    init {
        for (card in pool) {
            for (tag in index.tags(card.name)) {
                val f = fuel.getOrPut(tag.pair) { Fuel() }
                when (tag.role) {
                    SynergyRole.SIGNPOST -> { f.enabler += SIGNPOST_FUEL; f.payoff += SIGNPOST_FUEL; f.total += SIGNPOST_FUEL }
                    SynergyRole.PAYOFF -> { f.payoff += 1.0; f.total += 1.0 }
                    SynergyRole.ENABLER -> { f.enabler += 1.0; f.total += 1.0 }
                    SynergyRole.KEY -> f.total += KEY_FUEL
                }
            }
        }
    }

    fun evaluate(card: RankedCard, config: AdvisorEngine.Config): Result {
        var bestPts = 0.0
        var bestTheme: String? = null
        for (tag in index.tags(card.name)) {
            val f = fuel[tag.pair] ?: continue
            val relevant = when (tag.role) {
                SynergyRole.PAYOFF -> f.enabler
                SynergyRole.ENABLER -> f.payoff
                else -> f.total
            }
            if (relevant <= 0.0) continue
            val ramp = (relevant / config.themeFuelCap).coerceAtMost(1.0)
            val pts = roleWeight(tag.role) * ramp * config.themeCapPts
            if (pts > bestPts) { bestPts = pts; bestTheme = tag.archetypeName }
        }

        val partnersInPool = index.partners(card.name).filterKeys { it in poolNames }
        val comboPts = (partnersInPool.size * config.comboPts).coerceAtMost(config.comboCapPts)

        val reasons = buildList {
            if (bestPts >= REASON_THRESHOLD) bestTheme?.let { add("Synergy: $it") }
            partnersInPool.values.firstOrNull()?.let { add("Combo: ${it.name}") }
        }
        return Result(bestPts + comboPts, reasons)
    }

    private fun roleWeight(role: SynergyRole): Double = when (role) {
        SynergyRole.SIGNPOST -> 1.0
        SynergyRole.PAYOFF -> 1.0
        SynergyRole.ENABLER -> 0.85
        SynergyRole.KEY -> 0.6
    }

    companion object {
        private const val SIGNPOST_FUEL = 1.25
        private const val KEY_FUEL = 0.5
        private const val REASON_THRESHOLD = 1.5
    }
}
