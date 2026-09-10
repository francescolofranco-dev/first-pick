package com.firstpick.cards

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchetypeRepositoryTest {

    @Test
    fun allObservedPairsUseSampleAwareStrengths() = runBlocking {
        val pairRows = listOf(
            row("Azorius (WU)", wins = 81, games = 191),
            row("Dimir (UB)", wins = 123, games = 259),
            row("Rakdos (BR)", wins = 92, games = 196),
            row("Gruul (RG)", wins = 103, games = 210),
            row("Selesnya (GW)", wins = 103, games = 195),
            row("Orzhov (WB)", wins = 101_847, games = 175_923),
            row("Golgari (BG)", wins = 52_264, games = 94_979),
            row("Simic (GU)", wins = 19_219, games = 35_676),
            row("Izzet (UR)", wins = 55_529, games = 103_083),
            row("Boros (RW)", wins = 110_126, games = 191_326),
        )
        val aggregate = row("Two-color", wins = 339_487, games = 602_038)
        val rows = listOf(aggregate) + pairRows + listOf(
            row("Zero sample (WU)", wins = 0, games = 0),
            row("Temur (RGU)", wins = 20_591, games = 39_927),
            row("Two-color + Splash", wins = 165_579, games = 307_570),
        )
        val repo = repositoryWith(rows)

        repo.loadStrengths("SOS", "PremierDraft")

        val strengths = repo.rankedPairs().associateBy { it.pair }
        assertEquals(setOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG"), strengths.keys)

        val prior = aggregate.wins.toDouble() / aggregate.games
        val rg = strengths.getValue("RG")
        val bg = strengths.getValue("BG")
        val expectedRg = posterior(wins = 103, games = 210, prior)
        val expectedBg = posterior(wins = 52_264, games = 94_979, prior)

        assertEquals(210, rg.games, "sample count must remain the observed count")
        assertEquals(94_979, bg.games, "sample count must remain the observed count")
        assertEquals(expectedRg, rg.winRate, 1e-12)
        assertEquals(expectedBg, bg.winRate, 1e-12)
        assertTrue(rg.winRate > 103.0 / 210, "low-sample RG should shrink toward the field prior")
        assertTrue(rg.winRate < bg.winRate, "weak low-sample RG must remain below supported BG")
        assertTrue(abs(bg.winRate - 52_264.0 / 94_979) < 0.0002, "high-sample BG should barely move")
        assertTrue(repo.strengthMap().values.all(Double::isFinite))
    }

    @Test
    fun zeroGameRowsCannotProduceANaNStrength() = runBlocking {
        val repo = repositoryWith(
            listOf(
                row("Azorius (WU)", wins = 0, games = 0),
                row("Gruul (RG)", wins = 1, games = 1),
            ),
        )

        repo.loadStrengths("SOS", "PremierDraft")

        val only = repo.rankedPairs().single()
        assertEquals("RG", only.pair)
        assertEquals(1, only.games)
        assertEquals(posterior(wins = 1, games = 1, prior = 0.5), only.winRate, 1e-12)
        assertTrue(only.winRate < 0.51, "a lone one-game result must shrink toward a neutral prior")
        assertTrue(only.winRate.isFinite())
    }

    @Test
    fun malformedRowsCannotContaminateThePrior() = runBlocking {
        val repo = repositoryWith(
            listOf(
                row("Two-color", wins = 2, games = 1),
                row("Azorius (WU)", wins = -1, games = 10),
                row("Gruul (RG)", wins = 50, games = 100),
            ),
        )

        repo.loadStrengths("SOS", "PremierDraft")

        val only = repo.rankedPairs().single()
        assertEquals("RG", only.pair)
        assertEquals(0.5, only.winRate, 1e-12)
    }

    private fun repositoryWith(rows: List<ColorRatingRow>): ArchetypeRepository {
        val cache = createTempDirectory("fp-archetypes")
        val json = rows.joinToString(prefix = "[", postfix = "]") { row ->
            """{"color_name":"${row.colorName}","wins":${row.wins},"games":${row.games}}"""
        }
        Files.writeString(cache.resolve("colorratings_SOS_PremierDraft.json"), json)
        return ArchetypeRepository(SeventeenLandsClient(cacheDir = cache))
    }

    private fun row(name: String, wins: Int, games: Int): ColorRatingRow =
        ColorRatingRow(colorName = name, wins = wins, games = games)

    private fun posterior(wins: Int, games: Int, prior: Double): Double =
        (wins + PRIOR_GAMES * prior) / (games + PRIOR_GAMES)

    companion object {
        private const val PRIOR_GAMES = 800.0
    }
}
