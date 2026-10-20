package com.firstpick.guide

import com.firstpick.cards.*
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SetReferenceTest {
    private fun card(name: String, rarity: String, wr: Double, color: String = "U", games: Int = 1000) =
        CardRating(name = name, color = color, rarity = rarity, everDrawnWinRate = wr,
            everDrawnGameCount = games, imageUrl = "https://example.com/$name.png")

    @Test fun onlyThemeCardsOfTheRequestedRarityAndColorsCanFillTheSixSlots() {
        val profile = SetSynergyProfile(set = "TST", archetypes = listOf(SynergyArchetype(
            "WU", "Test theme", signposts = listOf("Rare bomb", "Off color", "Common 1"),
            enablers = (1..4).map { "Common $it" }, payoffs = (1..4).map { "Uncommon $it" },
            keyCards = listOf("Generic removal"), referenceTheme = "Test mechanic",
        )))
        val ratings = (1..4).map { card("Common $it", "common", 0.50 + it * 0.01) } +
            (1..4).map { card("Uncommon $it", "uncommon", 0.50 + it * 0.01) } + listOf(
                card("Rare bomb", "rare", 0.80), card("Generic removal", "common", 0.95), card("Unrelated", "common", 0.90),
                card("Off color", "common", 0.85, "R"),
            )
        val theme = buildThemeReferences(profile, ratings).single()
        assertEquals(listOf("Common 4", "Common 3", "Common 2"), theme.commons.map { it.name })
        assertEquals(listOf("Uncommon 4", "Uncommon 3", "Uncommon 2"), theme.uncommons.map { it.name })
        assertTrue(theme.commons.all { it.imageUrl != null })
        assertEquals("Test mechanic", theme.theme)
    }

    @Test fun partialCoverageIsNotFilledWithUnrelatedCardsAndReliableEvidenceWins() {
        val profile = SetSynergyProfile(set = "TST", archetypes = listOf(SynergyArchetype(
            "WU", "Theme", enablers = listOf("Known", "Small sample", "Missing"),
        )))
        val theme = buildThemeReferences(profile, listOf(
            card("Known", "common", 0.55), card("Small sample", "common", 0.90, games = 5),
            card("Unrelated", "uncommon", 0.70),
        )).single()
        assertEquals(listOf("Known", "Small sample"), theme.commons.map { it.name })
        assertTrue(theme.uncommons.isEmpty())
    }

    @Test fun allSupportedSetsAndThemesAreAvailableWithoutFetchingCardData() = runBlocking {
        val repository = SetReferenceRepository(createTempDirectory("fp-reference")) {
            error("Listing sets must not fetch card data")
        }
        val sets = repository.sets()
        assertEquals(StandardSets.codes, sets.map { it.code }.toSet())
        assertTrue(sets.all { it.name != it.code && it.themes.isNotEmpty() && !it.cardsLoaded })
        assertEquals(10, sets.single { it.code == "FRA" }.themes.size)
        assertEquals("Threshold & graveyard", sets.single { it.code == "FRA" }.themes.single { it.pair == "UB" }.theme)
    }
}
