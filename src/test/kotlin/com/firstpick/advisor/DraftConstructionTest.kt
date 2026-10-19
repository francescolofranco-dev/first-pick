package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.guide.LimitedDeckPolicies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftConstructionTest {
    private val metrics = SetMetrics(meanGihWr = 0.55, stdDevGihWr = 0.03)

    private fun card(id: Int, name: String, wr: Double, color: String) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = color, everDrawnWinRate = wr, everDrawnGameCount = 8_000),
    )

    private fun constructionPool(withFixing: Boolean): Pair<List<RankedCard>, Map<String, CardMeta>> {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(13) { i ->
                val name = "White$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(13) { i ->
                val name = "Blue$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "BlackBomb", 0.75, "B"))
            metas["BlackBomb"] = CardMeta(
                "BlackBomb", 6, true, false, isFinisher = true, isEvasion = true, coloredPips = mapOf('B' to 1),
            )
            if (withFixing) repeat(2) { i ->
                val name = "TriLand$i"
                add(card(400 + i, name, 0.55, ""))
                metas[name] = CardMeta(name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'B'))
            }
        }
        return pool to metas
    }

    @Test
    fun reliableTwoColorDraftBuildBeatsUnsupportedBombSplash() {
        val (pool, metas) = constructionPool(withFixing = false)
        val options = DeckBuilder.build(pool, metrics, metas::get)

        assertEquals("WU", options.first().basePair)
        assertEquals(null, options.first().splash)
        assertTrue(options.first().manaSources?.allRequirementsMet == true)
        assertFalse(options.any { it.basePair == "WU" && it.splash == 'B' }, "unsupported bomb must not create a splash build")
    }

    @Test
    fun fixedSinglePipBombSplashRemainsAViableDraftBuild() {
        val (pool, metas) = constructionPool(withFixing = true)
        val options = DeckBuilder.build(pool, metrics, metas::get)
        val splash = options.firstOrNull { it.basePair == "WU" && it.splash == 'B' }

        assertTrue(splash != null, "credible fixing should preserve the splash option: ${options.map { it.colors }}")
        assertTrue(splash.spells.any { it.name == "BlackBomb" })
        assertTrue(splash.manaSources?.splashSupported == true)
        assertTrue(splash.manaSources!!.totalSources.getValue('B') >= splash.manaSources.requiredSources.getValue('B'))
    }

    @Test
    fun aSplashWithoutExactManaCostMetadataIsNotInvented() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(12) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(12) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "UnknownCostBomb", 0.80, "B"))
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)

        assertFalse(options.any { it.splash == 'B' })
        assertTrue(
            options.filter { option -> option.spells.any { it.name == "UnknownCostBomb" } }
                .all { 'B' in it.basePair },
            "unknown mana may be a base-color card, but it must never be invented as a splash",
        )
    }

    @Test
    fun aPurePipIsNotHiddenByAnOverlappingHybridPip() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(12) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(12) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "BlackHybridBomb", 0.80, "BR"))
            metas["BlackHybridBomb"] = CardMeta(
                "BlackHybridBomb", 5, true, false, isFinisher = true,
                coloredPips = mapOf('B' to 1),
                hybridColorGroups = listOf(setOf('B', 'R')),
                hybridPips = listOf(setOf('B', 'R')),
            )
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)
        val wuOptions = options.filter { it.basePair == "WU" }

        assertTrue(wuOptions.none { it.splash == 'R' }, "{B}{B/R} cannot be cast by adding only red")
        assertTrue(wuOptions.none { option -> option.spells.any { it.name == "BlackHybridBomb" } })
    }

    @Test
    fun anOffBaseHybridCanUseEitherSingleSupportedSplashColor() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(11) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(11) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "FlexibleHybridBomb", 0.75, "BR"))
            metas["FlexibleHybridBomb"] = CardMeta(
                "FlexibleHybridBomb", 6, true, false, isFinisher = true,
                hybridColorGroups = listOf(setOf('B', 'R')),
                hybridPips = listOf(setOf('B', 'R')),
            )
            repeat(3) { i ->
                val name = "EsperLand$i"
                add(card(400 + i, name, 0.55, ""))
                metas[name] = CardMeta(
                    name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'B'),
                )
            }
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)
        val splash = options.firstOrNull { it.basePair == "WU" && it.splash == 'B' }

        assertTrue(splash != null, "{B/R} should be castable through the supported black half")
        assertTrue(splash.spells.any { it.name == "FlexibleHybridBomb" })
        assertTrue(splash.manaSources?.splashFeasible == true)
    }

    @Test
    fun aSplashMeetingItsOwnTargetIsRejectedWhenBaseSourcesAreShort() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(12) { i ->
                val name = "DoubleW$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 2, true, false, coloredPips = mapOf('W' to 2))
            }
            repeat(12) { i ->
                val name = "DoubleU$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 2, true, false, coloredPips = mapOf('U' to 2))
            }
            add(card(300, "LateBlackBomb", 0.80, "B"))
            metas["LateBlackBomb"] = CardMeta(
                "LateBlackBomb", 6, true, false, isFinisher = true, coloredPips = mapOf('B' to 1),
            )
            repeat(4) { i ->
                val name = "EsperLand$i"
                add(card(400 + i, name, 0.55, ""))
                metas[name] = CardMeta(
                    name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'B'),
                )
            }
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)

        assertFalse(options.any { it.basePair == "WU" && it.splash == 'B' })
    }

    @Test
    fun unsupportedTopUpDoesNotDiscardAValidTwoColorBase() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(11) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(10) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "UnsupportedBomb", 0.78, "B"))
            metas["UnsupportedBomb"] = CardMeta(
                "UnsupportedBomb", 6, true, false, isFinisher = true, coloredPips = mapOf('B' to 1),
            )
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)
        val wu = options.firstOrNull { it.basePair == "WU" }
        assertTrue(wu != null, "the 21-card two-color base must survive an unsupported top-up")
        assertEquals(null, wu.splash)
        assertEquals(21, wu.spells.size)
    }

    @Test
    fun skipsUnsupportedBombForALowerRatedSupportedSplash() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(11) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(10) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "UnsupportedBomb", 0.78, "B"))
            metas["UnsupportedBomb"] = CardMeta(
                "UnsupportedBomb", 6, true, false, isFinisher = true, coloredPips = mapOf('B' to 1),
            )
            add(card(301, "SupportedBomb", 0.68, "R"))
            metas["SupportedBomb"] = CardMeta(
                "SupportedBomb", 6, true, false, isFinisher = true, coloredPips = mapOf('R' to 1),
            )
            repeat(2) { i ->
                val name = "JeskaiLand$i"
                add(card(400 + i, name, 0.55, ""))
                metas[name] = CardMeta(name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'R'))
            }
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)
        val splash = options.firstOrNull { it.basePair == "WU" && it.splash == 'R' }
        assertTrue(splash != null, "source-aware search should continue past the unsupported black bomb: ${options.map { it.colors }}")
        assertTrue(splash.spells.any { it.name == "SupportedBomb" })
        assertTrue(splash.spells.none { it.name == "UnsupportedBomb" })
        assertTrue(splash.nonbasicLands.size <= LimitedDeckPolicies.DRAFT.landSlots)
        assertEquals(splash.nonbasicLands.size, splash.manaSources!!.nonbasicSources.values.maxOrNull())
    }

    @Test
    fun anUnsupportedExtraSplashCardDoesNotPoisonASupportedBomb() {
        val metas = mutableMapOf<String, CardMeta>()
        val pool = buildList {
            repeat(12) { i ->
                val name = "W$i"
                add(card(i, name, 0.57, "W"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('W' to 1))
            }
            repeat(12) { i ->
                val name = "U$i"
                add(card(100 + i, name, 0.57, "U"))
                metas[name] = CardMeta(name, 3, true, false, coloredPips = mapOf('U' to 1))
            }
            add(card(300, "LateBomb", 0.75, "B"))
            metas["LateBomb"] = CardMeta(
                "LateBomb", 6, true, false, isFinisher = true, coloredPips = mapOf('B' to 1),
            )
            add(card(301, "EarlyBlack", 0.70, "B"))
            metas["EarlyBlack"] = CardMeta(
                "EarlyBlack", 2, false, false, isRemoval = true, coloredPips = mapOf('B' to 1),
            )
            repeat(2) { i ->
                val name = "TriLand$i"
                add(card(400 + i, name, 0.55, ""))
                metas[name] = CardMeta(name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'B'))
            }
        }

        val options = DeckBuilder.build(pool, metrics, metas::get)
        val splash = options.firstOrNull { it.basePair == "WU" && it.splash == 'B' }
        assertTrue(splash != null, "the supported one-card prefix should remain viable")
        assertTrue(splash.spells.any { it.name == "LateBomb" })
        assertTrue(splash.spells.none { it.name == "EarlyBlack" })
        assertEquals(4, splash.manaSources!!.requiredSources.getValue('B'))
    }

}
