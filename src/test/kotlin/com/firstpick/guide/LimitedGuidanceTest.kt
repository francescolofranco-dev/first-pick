package com.firstpick.guide

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckNeeds
import com.firstpick.cards.CardRating
import com.firstpick.cards.SetSynergyProfile
import com.firstpick.cards.SynergyArchetype
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergyMechanic
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitedGuidanceTest {

    private fun rating(
        id: Int,
        name: String,
        color: String,
        rarity: String,
        gihWr: Double?,
        games: Int = 1_000,
        types: List<String> = listOf("Creature"),
        alsa: Double? = 4.0,
    ) = CardRating(
        name = name,
        mtgaId = id,
        color = color,
        rarity = rarity,
        types = types,
        avgSeen = alsa,
        everDrawnWinRate = gihWr,
        everDrawnGameCount = games,
    )

    @Test
    fun limitedPolicyKeepsTheSharedFortyCardBaseline() {
        assertEquals(40, LimitedPolicy.DECK_SIZE)
        assertEquals(17, LimitedPolicy.LAND_SLOTS)
        assertEquals(23, LimitedPolicy.SPELL_SLOTS)
        assertEquals(LimitedPolicy.DECK_SIZE, LimitedPolicy.LAND_SLOTS + LimitedPolicy.SPELL_SLOTS)
    }

    @Test
    fun advisorAndDeckNeedsUseTheGuidePolicyDefaults() {
        val advisor = AdvisorEngine.Config()

        assertEquals(LimitedPolicy.DRAFT_TOTAL_PICKS, advisor.totalPicks)
        assertEquals(LimitedPolicy.PICKS_PER_PACK, advisor.picksPerPack)
        assertEquals(LimitedPolicy.COLOR_COMMITMENT_RAMP_START, advisor.penaltyRampStart)
        assertEquals(LimitedPolicy.POOL_REMOVAL_TARGET, DeckNeeds.TARGET_REMOVAL)
        assertEquals(LimitedPolicy.POOL_TWO_DROP_TARGET, DeckNeeds.TARGET_TWO_DROPS)
        assertEquals(LimitedPolicy.POOL_FIXING_TARGET, DeckNeeds.TARGET_FIXING)
    }

    @Test
    fun principlesHaveUniqueIdsAndSourcesUseHttps() {
        val principles = LimitedGuidance.draftPrinciples + LimitedGuidance.sealedPrinciples
        val ids = principles.map { it.id }
        assertTrue(ids.none(String::isBlank), "principle ids must not be blank")
        assertEquals(ids.size, ids.toSet().size, "principle ids must be unique across both guides")

        val sources = LimitedGuidance.draftSources + LimitedGuidance.sealedSources
        assertTrue(sources.isNotEmpty())
        for (source in sources) {
            val uri = URI(source.url)
            assertEquals("https", uri.scheme, "${source.title} must use HTTPS")
            assertFalse(uri.host.isNullOrBlank(), "${source.title} must have a valid host")
        }
    }

    @Test
    fun builderGroupsAndSortsTopMonoColorCardsWhileExcludingGoldCardsAndLands() {
        val ratings = listOf(
            rating(1, "Best White Common", "W", "common", 0.63),
            rating(2, "Second White Common", "W", "COMMON", 0.60),
            rating(3, "Third White Common", "W", "common", 0.58),
            rating(4, "Tiny Sample Mirage", "W", "common", 0.99, games = 12),
            rating(5, "Best White Uncommon", "W", "uncommon", 0.65),
            rating(6, "Best White Rare", "W", "rare", 0.62),
            rating(7, "Best White Mythic", "W", "mythic rare", 0.61),
            rating(8, "Tiny Sample Bomb", "W", "mythic", 0.99, games = 12),
            rating(9, "Gold Common", "WU", "common", 0.75),
            rating(10, "White Land", "W", "rare", 0.75, types = listOf("Land")),
            rating(11, "Blue Common", "U", "common", 0.57),
        )

        val guide = SetDraftGuideBuilder.build(
            setCode = "tst",
            dataFormat = "PremierDraft",
            ratings = ratings,
            synergy = null,
        )

        assertEquals("TST", guide.setCode)
        assertEquals("WUBRG".toList(), guide.topCards.map { it.color })

        val white = guide.topCards.single { it.color == 'W' }
        assertEquals(
            listOf("Best White Common", "Second White Common", "Third White Common"),
            white.commons.map { it.name },
        )
        assertEquals(
            listOf("Best White Uncommon", "Best White Rare", "Best White Mythic"),
            white.nonCommons.map { it.name },
        )
        assertEquals(listOf("Blue Common"), guide.topCards.single { it.color == 'U' }.commons.map { it.name })

        val includedNames = guide.topCards.flatMap { it.commons + it.nonCommons }.map { it.name }
        assertFalse("Gold Common" in includedNames)
        assertFalse("White Land" in includedNames)
        assertFalse("Tiny Sample Mirage" in includedNames, "reliable cards should outrank tiny samples")
        assertFalse("Tiny Sample Bomb" in includedNames, "reliable cards should outrank tiny samples")
    }

    @Test
    fun builderMapsSetProfileMechanicsAndArchetypes() {
        val synergy = SynergyIndex(
            SetSynergyProfile(
                set = "TST",
                setName = "Test Set",
                generated = "2026-08-14",
                mechanics = listOf(SynergyMechanic("Investigate", "Create Clue tokens.")),
                archetypes = listOf(
                    SynergyArchetype(
                        pair = "UG",
                        name = "Clue Value",
                        playstyle = "Accumulate artifacts, then turn them into cards and threats.",
                        speed = "midrange",
                        signposts = listOf("Signpost Sleuth"),
                        enablers = listOf("Clue Maker"),
                        payoffs = listOf("Evidence Expert"),
                        keyCards = listOf("Case Cracker"),
                    ),
                ),
            ),
        )

        val guide = SetDraftGuideBuilder.build(
            setCode = "tst",
            dataFormat = "PremierDraft",
            ratings = emptyList(),
            synergy = synergy,
            pairStrength = mapOf("UG" to 0.574),
        )

        assertEquals("Test Set", guide.setName)
        assertEquals("2026-08-14", guide.generated)
        assertEquals(listOf(GuideMechanic("Investigate", "Create Clue tokens.")), guide.mechanics)
        assertEquals(
            GuideArchetype(
                pair = "UG",
                name = "Clue Value",
                speed = "midrange",
                plan = "Accumulate artifacts, then turn them into cards and threats.",
                winRate = 0.574,
                signposts = listOf("Signpost Sleuth"),
                enablers = listOf("Clue Maker"),
                payoffs = listOf("Evidence Expert"),
                keyCards = listOf("Case Cracker"),
            ),
            guide.archetypes.single(),
        )
    }
}
