package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertTrue

class ScenarioSuiteTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)
    private val engine = AdvisorEngine()


    private fun card(name: String, gih: Double?, color: String = "", iwd: Double = 0.0, alsa: Double = 4.0, rarity: String = "common") =
        RankedCard(
            grpId = name.hashCode(),
            name = name,
            rating = CardRating(
                name = name, mtgaId = name.hashCode(), color = color, rarity = rarity,
                everDrawnWinRate = gih, everDrawnGameCount = 2000, drawnImprovementWinRate = iwd, avgSeen = alsa,
            ),
        )

    private fun cm(name: String, cmc: Int = 3, creature: Boolean = false, land: Boolean = false, removal: Boolean = false, fixing: Boolean = false, finisher: Boolean = false) =
        CardMeta(name, cmc, isCreature = creature, isLand = land, isRemoval = removal, isFixing = fixing, isFinisher = finisher)

    private fun metaOf(metas: List<CardMeta>): (String) -> CardMeta? {
        val m = metas.associateBy { it.name }
        return { m[it] }
    }

    private fun archOf(vararg entries: Triple<String, String, Double>): (String, String) -> CardRating? {
        val m = entries.associate { (n, p, wr) -> (n to p) to CardRating(name = n, everDrawnWinRate = wr, everDrawnGameCount = 1500) }
        return { n, p -> m[n to p] }
    }

    private fun bluePool(n: Int) = List(n) { card("Blue$it", 0.58, "U") }
    private fun ubPool(n: Int) = List(n) { card("Pool$it", 0.58, if (it % 2 == 0) "U" else "B") }


    private fun List<ScoredCard>.idx(name: String) = indexOfFirst { it.card.name == name }
    private fun List<ScoredCard>.byName(name: String) = find { it.card.name == name }

    private fun topIs(name: String): Check = { s -> if (s.firstOrNull()?.card?.name == name) null else "expected top '$name', got '${s.firstOrNull()?.card?.name}'" }
    private fun above(a: String, b: String): Check = { s -> val ia = s.idx(a); val ib = s.idx(b); if (ia in 0 until ib) null else "expected '$a' above '$b' (idx $ia vs $ib)" }
    private fun bomb(name: String): Check = { s -> if (s.byName(name)?.isBomb == true) null else "expected '$name' flagged as bomb" }
    private fun notBomb(name: String): Check = { s -> if (s.byName(name)?.isBomb == false) null else "expected '$name' NOT a bomb" }
    private fun reason(name: String, substr: String): Check = { s -> val r = s.byName(name)?.reasons; if (r != null && r.any { it.contains(substr, true) }) null else "expected '$name' reason ~'$substr', got $r" }
    private fun confidence(level: ConfidenceLevel): Check = { s -> val c = Confidence.of(s.map { it.value }).level; if (c == level) null else "expected confidence $level, got $c" }
    private fun all(vararg cs: Check): Check = { s -> cs.firstNotNullOfOrNull { it(s) } }


    private fun scenarios(): List<Scenario> = listOf(
        Scenario("P1P1 best card on top",
            pack = listOf(card("Strong", 0.61, "U"), card("Weak", 0.49, "U")),
            check = topIs("Strong")),
        Scenario("P1P1 true bomb flagged + on top",
            pack = listOf(card("Bomb", 0.64, "U", iwd = 0.06), card("Filler", 0.55, "U")),
            check = all(topIs("Bomb"), bomb("Bomb"))),
        Scenario("Unrated card ranks below a rated one, no crash",
            pack = listOf(card("Rated", 0.56, "U"), card("Unrated", null, "U")),
            check = above("Rated", "Unrated")),

        Scenario("P1 early: no off-color penalty (best card wins)",
            pool = bluePool(3), packNo = 1, pickNo = 3,
            pack = listOf(card("OffRed", 0.60, "R"), card("OnBlue", 0.555, "U")),
            check = topIs("OffRed")),
        Scenario("P2 soft lock: on-color beats marginally-better off-color",
            pool = bluePool(6), packNo = 2, pickNo = 5,
            pack = listOf(card("OnBlue", 0.57, "U"), card("OffRed", 0.575, "R")),
            check = all(above("OnBlue", "OffRed"), notBomb("OffRed"), reason("OffRed", "Off-color"))),
        Scenario("P3 hard lock: strong off-color non-bomb suppressed below on-color filler",
            pool = bluePool(6), packNo = 3, pickNo = 5,
            pack = listOf(card("OffGood", 0.60, "R"), card("OnMeh", 0.55, "U")),
            check = above("OnMeh", "OffGood")),
        Scenario("Off-color BOMB overrides the hard lock",
            pool = bluePool(6), packNo = 3, pickNo = 4,
            pack = listOf(card("RedBomb", 0.64, "R", iwd = 0.06), card("BlueFiller", 0.555, "U")),
            check = all(topIs("RedBomb"), bomb("RedBomb"))),
        Scenario("Colorless cards are never penalized",
            pool = bluePool(6), packNo = 3, pickNo = 8,
            pack = listOf(card("Artifact", 0.55, ""), card("OffRed", 0.55, "R")),
            check = above("Artifact", "OffRed")),

        run {
            val pool = List(6) { card("PC$it", 0.55, "U") }
            Scenario("P3 needed removal jumps a slightly-better creature",
                pool = pool, packNo = 3, pickNo = 6,
                pack = listOf(card("Kill", 0.55, "U"), card("Beater", 0.56, "U")),
                meta = metaOf(pool.map { cm(it.name, creature = true) } + listOf(cm("Kill", removal = true), cm("Beater", cmc = 4, creature = true))),
                check = all(above("Kill", "Beater"), reason("Kill", "Needs removal")))
        },
        Scenario("P1P1 removal does NOT get the needs boost (pure value early)",
            packNo = 1, pickNo = 1,
            pack = listOf(card("Kill", 0.55, "U"), card("Beater", 0.575, "U")),
            meta = metaOf(listOf(cm("Kill", removal = true), cm("Beater", cmc = 4, creature = true))),
            check = above("Beater", "Kill")),
        run {
            val pool = List(7) { card("Rem$it", 0.55, "U") }
            Scenario("Removal saturation dampens another removal spell",
                pool = pool, packNo = 3, pickNo = 5,
                pack = listOf(card("MoreRemoval", 0.56, "U"), card("Creature", 0.55, "U")),
                meta = metaOf(pool.map { cm(it.name, removal = true) } + listOf(cm("MoreRemoval", removal = true), cm("Creature", creature = true))),
                check = all(above("Creature", "MoreRemoval"), reason("MoreRemoval", "saturat")))
        },

        Scenario("P2 short on 2-drops boosts a cheap creature",
            packNo = 2, pickNo = 1,
            pack = listOf(card("TwoDrop", 0.55, "U"), card("FiveDrop", 0.55, "U")),
            meta = metaOf(listOf(cm("TwoDrop", cmc = 2, creature = true), cm("FiveDrop", cmc = 5, creature = true))),
            check = all(above("TwoDrop", "FiveDrop"), reason("TwoDrop", "2-drop"))),
        run {
            val pool = List(4) { card("Big$it", 0.55, "U") }
            Scenario("Top-heavy pool dampens another expensive card",
                pool = pool, packNo = 3, pickNo = 3,
                pack = listOf(card("BigSpell", 0.56, "U"), card("Cheap", 0.55, "U")),
                meta = metaOf(pool.map { cm(it.name, cmc = 5) } + listOf(cm("BigSpell", cmc = 5), cm("Cheap", cmc = 2, creature = true))),
                check = above("Cheap", "BigSpell"))
        },
        run {
            val pool = List(12) { card("Crt$it", 0.55, "U") }
            Scenario("P3 missing a finisher boosts a finisher",
                pool = pool, packNo = 3, pickNo = 3,
                pack = listOf(card("Finisher", 0.55, "U"), card("Filler", 0.55, "U")),
                meta = metaOf(pool.map { cm(it.name, creature = true) } + listOf(cm("Finisher", cmc = 6, creature = true, finisher = true), cm("Filler", creature = true))),
                check = all(above("Finisher", "Filler"), reason("Finisher", "finisher")))
        },
        run {
            val pool = List(12) { card("Nonland$it", 0.55, "U") }
            Scenario("P2 fixing hunger boosts a dual land over an equally-weak card",
                pool = pool, packNo = 2, pickNo = 3,
                pack = listOf(card("Dual", 0.50, ""), card("BadCard", 0.50, "U")),
                meta = metaOf(pool.map { cm(it.name, creature = true) } + listOf(cm("Dual", cmc = 0, land = true, fixing = true), cm("BadCard"))),
                check = all(above("Dual", "BadCard"), reason("Dual", "fixing")))
        },

        Scenario("Archetype glue: a card better in your pair is boosted",
            pool = ubPool(6), packNo = 2, pickNo = 6,
            pack = listOf(card("Glue", 0.55, "U"), card("Plain", 0.55, "U")),
            archetypeRating = archOf(Triple("Glue", "UB", 0.60)),
            check = all(above("Glue", "Plain"), reason("Glue", "glue"))),

        Scenario("Late-ALSA card flagged as a wheeler",
            pack = listOf(card("Wheeler", 0.55, "U", alsa = 8.5)),
            check = reason("Wheeler", "wheel")),

        Scenario("Two near-tied tops → TOSS-UP",
            pack = listOf(card("A", 0.575, "U"), card("B", 0.573, "U"), card("C", 0.50, "U")),
            check = confidence(ConfidenceLevel.TOSS_UP)),
        Scenario("A clear best card → CLEAR confidence",
            pack = listOf(card("Best", 0.65, "U", iwd = 0.06), card("Mid", 0.55, "U"), card("Low", 0.50, "U")),
            check = confidence(ConfidenceLevel.CLEAR)),
        Scenario("A modest edge → LEAN confidence",
            pack = listOf(card("Top", 0.565, "U"), card("Next", 0.555, "U")),
            check = confidence(ConfidenceLevel.LEAN)),
    )

    @Test
    fun draftJudgmentHolds() {
        val scenarios = scenarios()
        val failures = scenarios.mapNotNull { sc ->
            val lane = LaneDetector.detect(sc.pool, metrics, sc.strengths)
            val scored = engine.score(sc.pack, sc.pool, sc.packNo, sc.pickNo, metrics, lane, sc.archetypeRating, sc.meta)
            sc.check(scored)?.let { "• ${sc.name}: $it" }
        }
        assertTrue(
            failures.isEmpty(),
            "Scenario regressions (${failures.size}/${scenarios.size}):\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun archetypeBlendStrengthensACardLateInTheDraft() {
        val pool = ubPool(6)
        val lane = LaneDetector.detect(pool, metrics)
        val pack = listOf(card("PairCard", 0.55, "U"))
        val arch = archOf(Triple("PairCard", "UB", 0.62))
        val early = engine.score(pack, pool, 1, 1, metrics, lane, arch).first().value
        val late = engine.score(pack, pool, 3, 8, metrics, lane, arch).first().value
        assertTrue(late > early, "pair-synergy card should score higher late ($late) than early ($early)")
    }
}

private typealias Check = (List<ScoredCard>) -> String?

private class Scenario(
    val name: String,
    val pack: List<RankedCard>,
    val pool: List<RankedCard> = emptyList(),
    val packNo: Int = 1,
    val pickNo: Int = 1,
    val meta: (String) -> CardMeta? = { null },
    val archetypeRating: (String, String) -> CardRating? = { _, _ -> null },
    val strengths: Map<String, Double> = emptyMap(),
    val check: Check,
)
