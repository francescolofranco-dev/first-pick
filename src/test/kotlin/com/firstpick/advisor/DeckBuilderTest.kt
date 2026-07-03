package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckBuilderTest {

    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(id: Int, name: String, gih: Double, color: String) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = color, everDrawnWinRate = gih, everDrawnGameCount = 2000),
    )

    private fun pool(): List<RankedCard> = buildList {
        repeat(12) { add(card(it, "W$it", 0.58, "W")) }
        repeat(12) { add(card(100 + it, "U$it", 0.58, "U")) }
        repeat(8) { add(card(200 + it, "R$it", 0.50, "R")) }
    }

    private val meta: (String) -> CardMeta? = { name ->
        CardMeta(name, cmc = 3, isCreature = true, isLand = false)
    }

    @Test
    fun buildsTheStrongestPairFirst() {
        val options = DeckBuilder.build(pool(), metrics, meta)
        assertTrue(options.isNotEmpty())
        assertEquals("WU", options.first().basePair)
        assertTrue(options.first().powerScore > options.last().powerScore)
    }

    @Test
    fun fillsToTwentyThreeSpellsAndSeventeenLandsNeverPadding() {
        val top = DeckBuilder.build(pool(), metrics, meta).first()
        assertEquals(23, top.spells.size, "a complete deck runs 23 spells")
        assertEquals(17, top.landCount, "a complete deck runs 17 lands")
        assertEquals(40, top.spells.size + top.landCount)
    }

    @Test
    fun hybridManaCardIsEligibleAsBaseColorWithoutNeedingASplash() {
        val hybridMeta: (String) -> CardMeta? = { name ->
            if (name == "HybridGuy") CardMeta("HybridGuy", cmc = 5, isCreature = true, isLand = false, hybridColorGroups = listOf(setOf('U', 'W')))
            else CardMeta(name, cmc = 3, isCreature = true, isLand = false)
        }
        val pool = buildList {
            add(card(1, "HybridGuy", 0.65, "UW")) // strong, so it's a lock for inclusion
            repeat(11) { add(card(10 + it, "U$it", 0.55, "U")) }
            repeat(11) { add(card(30 + it, "G$it", 0.55, "G")) }
        }
        val options = DeckBuilder.build(pool, metrics, hybridMeta)
        val ug = options.firstOrNull { it.basePair == "UG" }
        assertTrue(ug != null, "expected a UG build: ${options.map { it.colors }}")
        assertTrue(ug.spells.any { it.name == "HybridGuy" }, "hybrid card should be included in the UG deck")
        assertEquals("UG", ug.colors, "hybrid card should not force a W splash onto the deck identity")
        assertEquals(null, ug.splash)
    }

    @Test
    fun alwaysOffersThreeBuildsEvenFromALopsidedPool() {
        val redOnly = (0 until 8).map { card(it, "R$it", 0.5, "R") }
        assertEquals(3, DeckBuilder.build(redOnly, metrics, meta).size, "the user always gets a full slate")
    }

    @Test
    fun offersThreeBuildsFromANormalPool() {
        assertEquals(3, DeckBuilder.build(pool(), metrics, meta).size)
    }

    @Test
    fun neverOffersABalancedThreeColorDeck() {
        val pool = buildList {
            listOf("W", "U", "B").forEachIndexed { ci, col ->
                repeat(12) { i -> add(card(ci * 100 + i, "$col$i", 0.60 - 0.005 * i, col)) }
            }
        }
        val options = DeckBuilder.build(pool, metrics, meta)
        assertTrue(options.isNotEmpty())
        for (o in options) {
            assertEquals(2, o.basePair.length, "base must be two colors")
            assertTrue(o.colors.length <= 3, "a deck is two colors plus at most one splash: ${o.colors}")
        }
    }

    @Test
    fun topsUpAShortBaseWithACappedSingleColorSplash() {
        val pool = buildList {
            repeat(9) { add(card(it, "W$it", 0.56, "W")) }
            repeat(9) { add(card(100 + it, "U$it", 0.56, "U")) }
            repeat(4) { add(card(200 + it, "B$it", 0.60, "B")) }
        }
        val metaFix: (String) -> CardMeta? = { name ->
            CardMeta(name, cmc = 3, isCreature = true, isLand = false)
        }
        val top = DeckBuilder.build(pool, metrics, metaFix).first()
        assertEquals("WU", top.basePair)
        assertEquals('B', top.splash)
        val splashCards = top.spells.count { LaneDetector.colorsOf(it) == setOf('B') }
        assertTrue(splashCards in 1..4, "splash should be a few cards, got $splashCards")
    }

    @Test
    fun suggestsOnlyLandsWhoseProducedColorsFitTheDeck() {
        val metaMap = mutableMapOf<String, CardMeta>()
        fun spell(id: Int, name: String, color: String): RankedCard {
            metaMap[name] = CardMeta(name, cmc = 3, isCreature = true, isLand = false)
            return card(id, name, 0.57, color)
        }
        fun land(id: Int, name: String, produces: String): RankedCard {
            metaMap[name] = CardMeta(name, cmc = 0, isCreature = false, isLand = true, producedColors = produces.toSet())
            return card(id, name, 0.56, "")
        }
        val pool = buildList {
            repeat(12) { add(spell(it, "W$it", "W")) }
            repeat(12) { add(spell(100 + it, "U$it", "U")) }
            add(land(200, "AzoriusDual", "WU"))
            add(land(201, "IzzetDual", "UR"))
            add(land(202, "RainbowTower", "WUBRG"))
        }
        val top = DeckBuilder.build(pool, metrics, { metaMap[it] }).first()
        assertEquals("WU", top.basePair)
        val landNames = top.nonbasicLands.map { it.name }
        assertTrue("AzoriusDual" in landNames, "matching dual belongs in the build")
        assertTrue("IzzetDual" !in landNames, "half-off-color dual must not be suggested")
        assertTrue("RainbowTower" in landNames, "a land covering all deck colors belongs in the build")
    }

    @Test
    fun unresolvableCardsNeverTakeASpellSlot() {
        val pool = pool() + RankedCard(grpId = 999999, name = "", rating = null)
        val top = DeckBuilder.build(pool, metrics, meta).first()
        assertTrue(top.spells.none { it.rating == null }, "a card with no rating and no meta is a land-slot basic or token")
    }

    @Test
    fun neverSplashesACardWithDoublePipsOfTheSplashColor() {
        val metaFix: (String) -> CardMeta? = { name ->
            if (name == "DoubleWhite") CardMeta(name, cmc = 5, isCreature = true, isLand = false, heavyPipColors = setOf('W'))
            else CardMeta(name, cmc = 3, isCreature = true, isLand = false)
        }
        val pool = buildList {
            repeat(9) { add(card(it, "U$it", 0.56, "U")) }
            repeat(9) { add(card(100 + it, "G$it", 0.56, "G")) }
            add(card(200, "DoubleWhite", 0.70, "W"))
            add(card(201, "SingleWhite", 0.60, "W"))
        }
        val top = DeckBuilder.build(pool, metrics, metaFix).first()
        val names = top.spells.map { it.name }
        assertTrue("DoubleWhite" !in names, "WW cards are uncastable off a splash")
        assertTrue("SingleWhite" in names, "single-pip splash is fine")
    }

    @Test
    fun offersASplashVariantWhenPremiumRemovalBeatsWeakFiller() {
        val metaFix: (String) -> CardMeta? = { name ->
            if (name == "BlackRemoval") CardMeta(name, cmc = 3, isCreature = false, isLand = false, isRemoval = true)
            else CardMeta(name, cmc = 3, isCreature = true, isLand = false)
        }
        val pool = buildList {
            repeat(13) { add(card(it, "W$it", 0.56, "W")) }
            repeat(13) { add(card(100 + it, "U$it", 0.56, "U")) }
            add(card(200, "BlackRemoval", 0.61, "B"))
        }
        val options = DeckBuilder.build(pool, metrics, metaFix)
        val plain = options.firstOrNull { it.colors == "WU" }
        val splash = options.firstOrNull { it.colors == "WUB" }
        assertTrue(plain != null, "the two-color build stays on the slate")
        assertTrue(splash != null, "a premium off-color card should produce a splash variant: ${options.map { it.colors }}")
        assertTrue(splash.spells.any { it.name == "BlackRemoval" })
        assertEquals('B', splash.splash)
    }

    @Test
    fun noSplashVariantForMarginalOffColorCards() {
        val pool = buildList {
            repeat(13) { add(card(it, "W$it", 0.56, "W")) }
            repeat(13) { add(card(100 + it, "U$it", 0.56, "U")) }
            add(card(200, "MehBlack", 0.565, "B"))
        }
        val options = DeckBuilder.build(pool, metrics, meta)
        assertTrue(options.none { it.colors == "WUB" }, "half a point of win rate does not justify a splash")
    }

    @Test
    fun capsCopiesOfMediocreCards() {
        val pool = buildList {
            repeat(4) { add(card(it, "Dup", 0.56, "W")) }
            repeat(11) { add(card(100 + it, "W$it", 0.50, "W")) }
            repeat(11) { add(card(200 + it, "U$it", 0.50, "U")) }
        }
        val meta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }

        val top = DeckBuilder.build(pool, metrics, meta).first()
        assertEquals(2, top.spells.count { it.name == "Dup" }, "should cap mediocre dupes at 2")
    }

    @Test
    fun shapesAManaCurveInsteadOfStackingTopWinRates() {
        val metaMap = mutableMapOf<String, CardMeta>()
        fun creature(id: Int, name: String, gih: Double, color: String, cmc: Int): RankedCard {
            metaMap[name] = CardMeta(name, cmc = cmc, isCreature = true, isLand = false)
            return card(id, name, gih, color)
        }
        val pool = buildList {
            repeat(6) { add(creature(it, "Two$it", 0.53, if (it % 2 == 0) "W" else "U", 2)) }
            repeat(6) { add(creature(20 + it, "Three$it", 0.54, if (it % 2 == 0) "W" else "U", 3)) }
            repeat(12) { add(creature(40 + it, "Four$it", 0.58, if (it % 2 == 0) "W" else "U", 4)) }
        }
        val meta: (String) -> CardMeta? = { metaMap[it] }

        val top = DeckBuilder.build(pool, metrics, meta).first()
        val twoDrops = top.spells.count { meta(it.name)?.cmc == 2 }
        val threeDrops = top.spells.count { meta(it.name)?.cmc == 3 }
        assertTrue(twoDrops >= 4, "expected a real 2-drop count, got $twoDrops")
        assertTrue(threeDrops >= 4, "expected a real 3-drop count, got $threeDrops")
    }

    @Test
    fun everyDeckContainsOnlyItsDeclaredColors() {
        val pool = buildList {
            repeat(8) { add(card(it, "W$it", 0.55, "W")) }
            repeat(8) { add(card(20 + it, "U$it", 0.55, "U")) }
            repeat(8) { add(card(40 + it, "B$it", 0.56, "B")) }
            repeat(8) { add(card(60 + it, "R$it", 0.54, "R")) }
            repeat(8) { add(card(80 + it, "G$it", 0.55, "G")) }
        }
        val meta: (String) -> CardMeta? = { CardMeta(it, cmc = 3, isCreature = true, isLand = false) }

        val options = DeckBuilder.build(pool, metrics, meta)
        assertTrue(options.isNotEmpty())
        for (opt in options) {
            val declared = opt.colors.toSet()
            for (s in opt.spells) {
                assertTrue(
                    LaneDetector.colorsOf(s).all { it in declared },
                    "${opt.colors} build has off-color card ${s.name} ${LaneDetector.colorsOf(s)}",
                )
            }
        }
    }

    @Test
    fun synergyProfileLabelsTheDeckAndBreaksNearTies() {
        val index = com.firstpick.cards.SynergyIndex(
            com.firstpick.cards.SetSynergyProfile(
                set = "TST",
                archetypes = listOf(
                    com.firstpick.cards.SynergyArchetype(
                        pair = "WU",
                        name = "Fliers tempo",
                        payoffs = listOf("OnTheme"),
                    ),
                ),
            ),
        )
        val pool = buildList {
            add(card(1, "OnTheme", 0.560, "U"))
            add(card(2, "OffTheme", 0.563, "U"))
            repeat(12) { add(card(10 + it, "W$it", 0.56, "W")) }
            repeat(11) { add(card(40 + it, "U$it", 0.56, "U")) }
        }
        val top = DeckBuilder.build(pool, metrics, meta, synergy = index).first()
        assertEquals("Fliers tempo", top.theme)
        val names = top.spells.map { it.name }
        assertTrue("OnTheme" in names, "near-tied on-theme card should make the deck over filler")
    }

    @Test
    fun keepsTheDeckCreatureDenseDespiteHighWinRateSpells() {
        val metaMap = mutableMapOf<String, CardMeta>()
        fun mk(id: Int, name: String, gih: Double, color: String, cmc: Int, creature: Boolean): RankedCard {
            metaMap[name] = CardMeta(name, cmc = cmc, isCreature = creature, isLand = false)
            return card(id, name, gih, color)
        }
        val cmcs = listOf(2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 6)
        val pool = buildList {
            cmcs.forEachIndexed { i, c -> add(mk(i, "Cre$i", 0.54, if (i % 2 == 0) "W" else "U", c, true)) }
            repeat(12) { add(mk(100 + it, "Spell$it", 0.60, if (it % 2 == 0) "W" else "U", 2, false)) }
        }
        val meta: (String) -> CardMeta? = { metaMap[it] }

        val top = DeckBuilder.build(pool, metrics, meta).first()
        assertTrue(top.creatures >= 15, "expected a creature-dense deck, got ${top.creatures}")
    }
}
