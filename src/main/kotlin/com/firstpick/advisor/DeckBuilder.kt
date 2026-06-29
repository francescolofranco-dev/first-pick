package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics

/** A finished 40-card deck proposal built from the drafted pool. */
data class DeckOption(
    /** Full color identity (base pair + splash), in WUBRG order — drives the pips. */
    val colors: String,
    /** The two base colors. */
    val basePair: String,
    /** The single splash color, or null for a clean two-color deck. */
    val splash: Char?,
    val powerScore: Double,
    val tier: String,
    val type: String,
    val outlook: String,
    val deckWinRate: Double,
    val spells: List<RankedCard>,
    val nonbasicLands: List<RankedCard>,
    /** Total lands the deck wants (≈17). Arena fills basics, so we never list them. */
    val landCount: Int,
    val creatures: Int,
    val removal: Int,
    val curve: List<Pair<String, Int>>,
)

/**
 * Builds the best 40-card decks out of a completed draft pool.
 *
 * A limited deck is **23 nonland cards + 17 lands**, in **two colors plus at most a light
 * single-color splash** — never a balanced three-color pile (the mana base is too weak in
 * 40 cards). So for each two-color base we pick the strongest on-color spells, then top up to
 * 23 with a capped splash of one off-color if the base runs short, fill out the lands, and
 * rate the result. We never pad a thin pool with extra lands (that produced the broken "18
 * spells + 22 lands" decks), and we never suggest basics — Arena's deck editor adds those.
 */
object DeckBuilder {
    private const val SPELL_SLOTS = 23
    private const val LAND_SLOTS = 17
    private const val DECK_SIZE = 40

    // A two-color base may top up to 23 with a SINGLE off-color splash, capped so it stays a
    // splash (a few cards) rather than a third main color. Pairs that can't reach this many
    // playable cards (even with the splash) aren't a real deck and are dropped — unless none
    // qualify at all, in which case we relax and still offer the best available.
    private const val MAX_SPLASH = 4
    private const val MIN_DECK_SPELLS = 21

    // Deck-shaping targets. A limited deck wants a creature-dense board and a real mana
    // curve — not just the 23 highest win rates (which skews top-heavy and trick-heavy).
    private const val CREATURE_TARGET = 15
    private const val NONCREATURE_CAP = 8
    // A leftover creature is preferred over a (slightly better) non-creature within this
    // win-rate margin while the deck is still short on creatures.
    private const val CREATURE_BIAS = 0.015
    // Ideal creature count per mana-value bucket (6 = 6+). Sums to CREATURE_TARGET; the
    // 1-drop slot is filled last because cheap creatures matter least to the curve.
    private val CREATURE_CURVE = linkedMapOf(2 to 4, 3 to 4, 4 to 3, 5 to 2, 6 to 1, 1 to 1)

    /** Reject pairs where a base color is only a token presence (uncastable as 2-color). */
    private const val MIN_COLOR_PIPS = 4
    private const val MIN_COLOR_RATIO = 0.20

    // Power adjustments.
    private const val INCOMPLETE_SPELL_PENALTY = 3.0 // per spell short of 23 (favors full decks)
    private const val UNFIXED_SPLASH_PENALTY = 4.0   // per splash card without a supporting fixer
    private const val SPLASH_FIXING_REWARD = 1.0     // small reward for spare fixing when splashing

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

        fun pass(minSpells: Int) = COLOR_PAIRS.mapNotNull { pair ->
            buildForPair(pair, spells, lands, metrics, meta, archetypeRating, strengthFor(pair, pairStrength), minSpells)
        }

        // Prefer real decks (≥ MIN_DECK_SPELLS); only if none exist (a pool spread thin across
        // many colors) relax so the player still gets the best available build.
        val options = pass(MIN_DECK_SPELLS).ifEmpty { pass(0) }
        return options
            .sortedByDescending { it.powerScore }
            .distinctBy { it.colors }
            .take(maxOptions)
    }

    /** Archetype tailwind for a base pair. */
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
        val base = selectSpells(eligible, metrics, meta, ::cardScore).toMutableList()

        // Fixing already in the pool (on-color/colorless lands + cards) gates how honest a
        // splash is. A short base tops up to 23 with a SINGLE off-color splash, capped so the
        // deck stays two colors + a splash rather than a rainbow.
        val baseFixers = base.count { meta(it.name)?.isFixing == true } +
            lands.count { val c = LaneDetector.colorsOf(it); (c.isEmpty() || pairSet.containsAll(c)) && meta(it.name)?.isFixing == true }
        val splashedSpells = chooseSplash(spells, pairSet, SPELL_SLOTS - base.size, ::onColor, ::cardScore)
        val chosen = base + splashedSpells
        val splashColor = splashedSpells.firstOrNull()
            ?.let { card -> LaneDetector.colorsOf(card).firstOrNull { it !in pairSet } }
        val deckColors = pairSet + setOfNotNull(splashColor)

        // Not enough castable cards to be a real deck — don't pad it out with lands.
        if (chosen.size < minSpells) return null

        // Reject "fake" pairs — a mono deck with a token off-color isn't castable as two colors.
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
        // 23 spells + 17 lands. A slightly short spell pool runs a couple more lands, never the
        // absurd land pile we used to pad with — and Arena fills the basics, so we don't list them.
        val landCount = (DECK_SIZE - chosen.size).coerceAtLeast(LAND_SLOTS)
        return DeckOption(
            colors = "WUBRG".filter { it in deckColors },
            basePair = "WUBRG".filter { it in pairSet },
            splash = splashColor,
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

    /**
     * Choose at most [MAX_SPLASH] cards of a SINGLE off-color to top a short base up toward 23.
     * Cards that would add more than one new color are skipped, so the result never turns a
     * two-color deck into a rainbow. Returns empty when the base is already full or no
     * single-color splash is available.
     */
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
            if (extra.size != 1) continue // only single-color splashes
            byColor.getOrPut(extra.first()) { mutableListOf() }.add(card)
        }
        val best = byColor.values.maxByOrNull { cards -> cards.maxOf(cardScore) } ?: return emptyList()
        return best.sortedByDescending(cardScore).take(minOf(deficit, MAX_SPLASH))
    }

    /** A copy-capped, scored candidate for deck inclusion. Identity equality (one per copy). */
    private class Candidate(
        val card: RankedCard,
        val score: Double,
        val cmc: Int,
        val isCreature: Boolean,
    )

    /**
     * Pick up to [SPELL_SLOTS] nonland cards from [eligible] to form a *coherent* deck —
     * not just the 23 highest win rates. That naive approach produced the decks players
     * complained about: top-heavy curves, three copies of mediocre commons, and too few
     * creatures. Here we (1) cap copies by card quality, (2) fill a creature mana curve,
     * then (3) fill the rest preferring creatures so the board stays dense.
     */
    private fun selectSpells(
        eligible: List<RankedCard>,
        metrics: SetMetrics,
        meta: (String) -> CardMeta?,
        cardScore: (RankedCard) -> Double,
    ): List<RankedCard> {
        val candidates = eligible
            .groupBy { it.name }
            .flatMap { (_, copies) ->
                val cap = copyCap(metrics.z(cardScore(copies.first())) ?: 0.0)
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
        var creatureCount = chosen.size // the curve pass added only creatures
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

    /** Copies of a card that belong in a deck, by its win-rate z-score (1=filler, 3=premium). */
    private fun copyCap(z: Double): Int = when {
        z >= 1.0 -> 3
        z >= 0.0 -> 2
        else -> 1
    }

    /** Mana-value bucket for the curve (0–1 -> 1, 6+ -> 6). */
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

        // A thin spell pool (couldn't reach 23) is a worse deck than a complete one — so a
        // high-win-rate-but-short pile never outranks a full build (the old scoring bug).
        if (spellCount < SPELL_SLOTS) score -= (SPELL_SLOTS - spellCount) * INCOMPLETE_SPELL_PENALTY

        // A splash costs consistency unless the pool has fixing to support it.
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
