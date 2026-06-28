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
    private const val DECK_SIZE = 40
    private const val MIN_PLAYABLES = 18

    // A short on-color pool may splash a SINGLE off-color, and only with fixing — never an
    // incoherent multi-color pile. Capped, and limited by how much fixing the pool has.
    private const val SPLASH_CAP = 3
    private const val SPLASH_MIN_FIXERS = 2

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

    /** Reject pairs where the second color is only a token splash (uncastable as 2-color). */
    private const val MIN_COLOR_PIPS = 4
    private const val MIN_COLOR_RATIO = 0.20

    // A 3-color deck pays a flat consistency tax (it's two-color decks by default), plus a
    // steep extra penalty per missing fixer so it only surfaces with real fixing AND a clear
    // card-quality edge over the player's 2-color lane.
    private const val TRI_FIXERS_NEEDED = 3
    private const val TRI_FIXER_PENALTY = 5.0
    private const val TRI_CONSISTENCY_PENALTY = 6.0

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
            .mapNotNull { pair ->
                buildForPair(pair, spells, lands, metrics, meta, archetypeRating, strengthFor(pair, pairStrength))
            }
            .sortedByDescending { it.powerScore }
            .take(maxOptions)
    }

    /** Archetype tailwind for a pair. A 3-color deck inherits its strongest 2-color core. */
    private fun strengthFor(pair: String, pairStrength: Map<String, Double>): Double? {
        pairStrength[pair]?.let { return it }
        if (pair.length != 3) return null
        val subPairs = listOf("${pair[0]}${pair[1]}", "${pair[0]}${pair[2]}", "${pair[1]}${pair[2]}")
        return subPairs.mapNotNull { pairStrength[it] }.maxOrNull()
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
        val chosen = selectSpells(eligible, metrics, meta, ::cardScore).toMutableList()

        // Fixing already in the pool (on-color/colorless lands + cards). A splash is only
        // honest if we can actually cast it, so this gates the off-color top-up below.
        val baseFixers = chosen.count { meta(it.name)?.isFixing == true } +
            lands.count { val c = LaneDetector.colorsOf(it); (c.isEmpty() || pairSet.containsAll(c)) && meta(it.name)?.isFixing == true }

        // Top up a short pool with a single off-color splash — never an incoherent multi-color
        // pile — and only when the pool has fixing for it. The deck's color identity then
        // includes that splash color, so the label/pips/basics match what's actually in it.
        // Only 2-color decks splash; a 3-color deck stays 3 colors rather than going rainbow.
        val splashedSpells = if (pairSet.size == 2)
            chooseSplash(spells, pairSet, SPELL_SLOTS - chosen.size, baseFixers, ::onColor, ::cardScore)
        else emptyList()
        chosen.addAll(splashedSpells)
        val splashColor = splashedSpells.firstOrNull()
            ?.let { card -> LaneDetector.colorsOf(card).firstOrNull { it !in pairSet } }
        val deckColors = pairSet + setOfNotNull(splashColor)

        // Not enough castable cards in these colors to build a real deck — don't offer one
        // (otherwise we'd pad it out to 40 with an absurd number of lands).
        if (chosen.size < MIN_PLAYABLES) return null

        val onColorLands = lands.filter { card ->
            val colors = LaneDetector.colorsOf(card)
            colors.isEmpty() || deckColors.containsAll(colors)
        }

        // Reject "fake" pairs — a mono deck with a token splash isn't castable as two colors.
        val pips = mutableMapOf<Char, Int>()
        for (card in chosen) {
            for (ch in LaneDetector.colorsOf(card)) {
                if (ch in deckColors) pips.merge(ch, 1, Int::plus)
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

        val power = powerScore(deckWr, metrics, strength, creatures, removal, twoDrops, splashCount, fixersCount, deckColors.size)
        // Fill the deck to 40 cards with lands (a short spell pool means more lands, not
        // off-color filler). landTarget is ≥ LAND_SLOTS and only exceeds it for thin pools.
        val landTarget = (DECK_SIZE - chosen.size).coerceAtLeast(LAND_SLOTS)
        return DeckOption(
            pair = "WUBRG".filter { it in deckColors },
            powerScore = power,
            tier = tierOf(power),
            type = typeOf(avgCmc, twoDrops, removal),
            outlook = outlookOf(power),
            deckWinRate = deckWr,
            spells = chosen.sortedWith(compareBy({ meta(it.name)?.cmc ?: 9 }, { -(it.gihWr ?: 0.0) })),
            nonbasicLands = onColorLands,
            basics = basicSplit(pips, deckColors, (landTarget - onColorLands.size).coerceAtLeast(0)),
            creatures = creatures,
            removal = removal,
            curve = curveOf(metas),
        )
    }

    /**
     * Choose at most [SPLASH_CAP] cards of a SINGLE off-color to top up a short pool, and
     * only when the pool has fixing ([fixers] ≥ [SPLASH_MIN_FIXERS]). Cards that would add
     * more than one new color are skipped, so the result never turns a 2-/3-color deck into
     * an uncastable rainbow pile. Returns empty when no honest splash is available.
     */
    private fun chooseSplash(
        spells: List<RankedCard>,
        pairSet: Set<Char>,
        deficit: Int,
        fixers: Int,
        onColor: (RankedCard) -> Boolean,
        cardScore: (RankedCard) -> Double,
    ): List<RankedCard> {
        if (deficit <= 0 || fixers < SPLASH_MIN_FIXERS) return emptyList()
        val byColor = LinkedHashMap<Char, MutableList<RankedCard>>()
        for (card in spells.filterNot(onColor)) {
            val extra = LaneDetector.colorsOf(card).filter { it !in pairSet }
            if (extra.size != 1) continue // only single-color splashes
            byColor.getOrPut(extra.first()) { mutableListOf() }.add(card)
        }
        val best = byColor.values.maxByOrNull { cards -> cards.maxOf(cardScore) } ?: return emptyList()
        return best.sortedByDescending(cardScore).take(minOf(deficit, SPLASH_CAP, fixers))
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
        // 1. Copy cap: a card's quality (win-rate z-score) limits how many copies belong in
        //    a deck. Mediocre commons get 1, solid cards 2, only strong cards 3.
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

        // 2. Curve pass: fill each mana-value bucket toward a healthy creature curve.
        val chosen = LinkedHashSet<Candidate>()
        val byBucket = creatures.groupBy { curveBucket(it.cmc) }
        for ((bucket, target) in CREATURE_CURVE) {
            byBucket[bucket].orEmpty().take(target).forEach { chosen.add(it) }
        }

        // 3. Biased merge: fill the remaining slots, preferring creatures until the deck is
        //    creature-dense, then taking the best card available. The non-creature cap keeps
        //    a pile of high-win-rate tricks/removal from crowding out the board — but only
        //    while creatures remain to take instead (so it never forces a needless splash).
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
        fixersCount: Int,
        colorCount: Int
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

        // A 3-color deck is inherently less consistent than two, so it always pays a
        // consistency tax — and a much steeper one when the fixing isn't there. This keeps
        // suggestions aligned with the player's 2-color lane unless a tri is clearly better.
        if (colorCount >= 3) {
            val shortfall = (TRI_FIXERS_NEEDED - fixersCount).coerceAtLeast(0)
            score -= TRI_CONSISTENCY_PENALTY + shortfall * TRI_FIXER_PENALTY
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
