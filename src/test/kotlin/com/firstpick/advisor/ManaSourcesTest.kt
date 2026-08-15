package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardRating
import com.firstpick.cards.RankedCard
import com.firstpick.guide.LimitedDeckPolicies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManaSourcesTest {
    private fun card(id: Int, name: String, color: String) = RankedCard(
        grpId = id,
        name = name,
        rating = CardRating(name = name, mtgaId = id, color = color, everDrawnWinRate = 0.57, everDrawnGameCount = 2_000),
    )

    @Test
    fun cheapSplashRequiresMoreSourcesThanLateSplash() {
        fun requirement(cmc: Int): Int {
            val splash = card(1, "Splash", "B")
            val metas = mapOf("Splash" to CardMeta("Splash", cmc, false, false, coloredPips = mapOf('B' to 1)))
            return ManaSources.estimate(
                spells = listOf(splash),
                basePair = "WU",
                splashColor = 'B',
                nonbasicLands = emptyList(),
                meta = metas::get,
                policy = LimitedDeckPolicies.SEALED,
            ).requiredSources.getValue('B')
        }

        assertEquals(7, requirement(cmc = 2))
        assertEquals(4, requirement(cmc = 6))
    }

    @Test
    fun strictestOfSeveralSplashSpellsSetsTheRequirement() {
        val early = card(1, "Early", "B")
        val late = card(2, "Late", "B")
        val metas = mapOf(
            "Early" to CardMeta("Early", 2, false, false, coloredPips = mapOf('B' to 1)),
            "Late" to CardMeta("Late", 6, true, false, coloredPips = mapOf('B' to 1)),
        )
        val report = ManaSources.estimate(
            listOf(late, early), "WU", 'B', emptyList(), metas::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(7, report.requiredSources.getValue('B'))
    }

    @Test
    fun aFixerCountsOnlyWhenItProducesTheColorBeforeTheSplashTurn() {
        fun report(fixerCmc: Int): ManaSourceReport {
            val splash = card(1, "Early", "B")
            val fixer = card(2, "Fixer", "")
            val metas = mapOf(
                "Early" to CardMeta("Early", 2, false, false, coloredPips = mapOf('B' to 1)),
                "Fixer" to CardMeta("Fixer", fixerCmc, false, false, isFixing = true, producedColors = setOf('B')),
            )
            return ManaSources.estimate(
                listOf(splash, fixer), "WU", 'B', emptyList(), metas::get, LimitedDeckPolicies.SEALED,
            )
        }

        assertEquals(1, report(fixerCmc = 2).fixerSources.getValue('B'))
        assertEquals(0, report(fixerCmc = 4).fixerSources.getValue('B'))
    }

    @Test
    fun aSplashColoredTreasureSpellCannotCountItselfAsASource() {
        val white = card(1, "White", "W")
        val blue = card(2, "Blue", "U")
        val splash = card(3, "LateRedBomb", "R")
        val circularFixer = card(4, "RedTreasureSpell", "R")
        val lands = List(3) { card(10 + it, "RedLand$it", "") }
        val metas = buildMap {
            put("White", CardMeta("White", 3, true, false, coloredPips = mapOf('W' to 1)))
            put("Blue", CardMeta("Blue", 3, true, false, coloredPips = mapOf('U' to 1)))
            put("LateRedBomb", CardMeta("LateRedBomb", 6, true, false, coloredPips = mapOf('R' to 1)))
            put(
                "RedTreasureSpell",
                CardMeta("RedTreasureSpell", 3, false, false, isFixing = true, coloredPips = mapOf('R' to 1)),
            )
            lands.forEach { land ->
                put(land.name, CardMeta(land.name, 0, false, true, producedColors = setOf('R')))
            }
        }

        val report = ManaSources.estimate(
            listOf(white, blue, splash, circularFixer), "WU", 'R', lands, metas::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(0, report.fixerSources.getValue('R'))
        assertEquals(3, report.splashShortfall)
        assertFalse(report.splashFeasible)
    }

    @Test
    fun aDualOverlapsSourcesButConsumesOnlyOneLandSlot() {
        val white = card(1, "White", "W")
        val blue = card(2, "Blue", "U")
        val dual = card(3, "Dual", "")
        val metas = mapOf(
            "White" to CardMeta("White", 3, true, false, coloredPips = mapOf('W' to 1)),
            "Blue" to CardMeta("Blue", 3, true, false, coloredPips = mapOf('U' to 1)),
            "Dual" to CardMeta("Dual", 0, false, true, isFixing = true, producedColors = setOf('W', 'U')),
        )
        val report = ManaSources.estimate(
            listOf(white, blue), "WU", null, listOf(dual), metas::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(16, report.basicSources.values.sum())
        assertEquals(1, report.nonbasicSources.getValue('W'))
        assertEquals(1, report.nonbasicSources.getValue('U'))
        assertEquals(17, report.totalSources.getValue('W') + report.totalSources.getValue('U') - 1)
    }

    @Test
    fun splashNeverStealsBasicsNeededByTheBaseColors() {
        val white = card(1, "White", "W")
        val blue = card(2, "Blue", "U")
        val splash = card(3, "Splash", "B")
        val metas = mapOf(
            "White" to CardMeta("White", 3, true, false, coloredPips = mapOf('W' to 1)),
            "Blue" to CardMeta("Blue", 3, true, false, coloredPips = mapOf('U' to 1)),
            "Splash" to CardMeta("Splash", 6, true, false, coloredPips = mapOf('B' to 1)),
        )
        val report = ManaSources.estimate(
            listOf(white, blue, splash), "WU", 'B', emptyList(), metas::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(0, report.baseShortfall)
        assertTrue(report.totalSources.getValue('W') >= report.requiredSources.getValue('W'))
        assertTrue(report.totalSources.getValue('U') >= report.requiredSources.getValue('U'))
        assertFalse(report.splashSupported)
    }

    @Test
    fun basePayableHybridPipsDoNotCreateAFalseSecondColorRequirement() {
        val mixed = card(1, "Mixed", "WU")
        val repeated = card(2, "Repeated", "WU")
        val metas = mapOf(
            "Mixed" to CardMeta(
                "Mixed", 2, true, false,
                coloredPips = mapOf('W' to 1),
                hybridColorGroups = listOf(setOf('W', 'U')),
                hybridPips = listOf(setOf('W', 'U')),
            ),
            "Repeated" to CardMeta(
                "Repeated", 2, true, false,
                hybridColorGroups = listOf(setOf('W', 'U')),
                hybridPips = listOf(setOf('W', 'U'), setOf('W', 'U')),
            ),
        )
        val mixedReport = ManaSources.estimate(
            listOf(mixed), "WU", null, emptyList(), metas::get, LimitedDeckPolicies.SEALED,
        )
        val repeatedReport = ManaSources.estimate(
            listOf(repeated), "WU", null, emptyList(), metas::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(9, mixedReport.requiredSources.getValue('W'))
        assertEquals(8, mixedReport.requiredSources.getValue('U'), "{W/U} can use the established white base")
        assertEquals(8, repeatedReport.requiredSources.getValue('W'))
        assertEquals(8, repeatedReport.requiredSources.getValue('U'), "repeated flexible pips are not one pip of each color")
    }

    @Test
    fun offBaseHybridRequiresTheOnlyPayableSplashColor() {
        val hybrid = card(1, "Hybrid", "BR")
        val meta = mapOf(
            "Hybrid" to CardMeta(
                "Hybrid", 2, true, false,
                hybridColorGroups = listOf(setOf('B', 'R')),
                hybridPips = listOf(setOf('B', 'R')),
            ),
        )
        val report = ManaSources.estimate(
            listOf(hybrid), "WU", 'B', emptyList(), meta::get, LimitedDeckPolicies.SEALED,
        )

        assertEquals(7, report.requiredSources.getValue('B'))
    }

    @Test
    fun meetingTheSplashTargetCannotHideABaseColorShortfall() {
        val white = card(1, "DoubleWhite", "W")
        val blue = card(2, "DoubleBlue", "U")
        val splash = card(3, "LateBlack", "B")
        val lands = List(4) { card(10 + it, "FourColorLand$it", "") }
        val metas = buildMap {
            put("DoubleWhite", CardMeta("DoubleWhite", 2, true, false, coloredPips = mapOf('W' to 2)))
            put("DoubleBlue", CardMeta("DoubleBlue", 2, true, false, coloredPips = mapOf('U' to 2)))
            put("LateBlack", CardMeta("LateBlack", 6, true, false, coloredPips = mapOf('B' to 1)))
            lands.forEach { land ->
                put(land.name, CardMeta(land.name, 0, false, true, isFixing = true, producedColors = setOf('W', 'U', 'B')))
            }
        }

        val report = ManaSources.estimate(
            listOf(white, blue, splash), "WU", 'B', lands, metas::get, LimitedDeckPolicies.SEALED,
        )

        assertTrue(report.splashSupported, "four lands meet the late single-pip splash target")
        assertTrue(report.baseShortfall > 0)
        assertFalse(report.splashFeasible, "a splash is infeasible when it destabilizes the base")
    }
}
