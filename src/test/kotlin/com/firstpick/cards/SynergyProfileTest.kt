package com.firstpick.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SynergyProfileTest {

    private fun profile() = SetSynergyProfile(
        set = "TST",
        archetypes = listOf(
            SynergyArchetype(
                pair = "UG",
                name = "Counters",
                signposts = listOf("Signpost Guy"),
                payoffs = listOf("Payoff Guy // Other Face"),
                enablers = listOf("Enabler Guy"),
                keyCards = listOf("Key Guy"),
            ),
            SynergyArchetype(
                pair = "WB",
                name = "Equipment",
                payoffs = listOf("Payoff Guy // Other Face"),
            ),
        ),
        combos = listOf(SynergyCombo(listOf("Enabler Guy", "Payoff Guy // Other Face"), note = "engine")),
    )

    @Test
    fun indexResolvesRolesByNormalizedName() {
        val index = SynergyIndex(profile())
        assertEquals(listOf(SynergyTag("UG", SynergyRole.SIGNPOST, "Counters")), index.tags("signpost guy"))
        assertEquals(SynergyRole.ENABLER, index.tags("Enabler Guy").single().role)
        assertEquals(SynergyRole.KEY, index.tags("Key Guy").single().role)
    }

    @Test
    fun doubleFacedNamesMatchTheirFrontFace() {
        val index = SynergyIndex(profile())
        val tags = index.tags("Payoff Guy")
        assertEquals(2, tags.size, "front-face lookup should find the DFC in both archetypes")
        assertTrue(tags.any { it.pair == "UG" } && tags.any { it.pair == "WB" })
    }

    @Test
    fun comboPartnersAreSymmetricAndKeepDisplayNames() {
        val index = SynergyIndex(profile())
        val fromEnabler = index.partners("enabler guy")
        assertEquals(setOf("payoff guy"), fromEnabler.keys)
        assertEquals("Payoff Guy // Other Face", fromEnabler.values.single().name)
        assertEquals(setOf("enabler guy"), index.partners("Payoff Guy").keys)
    }

    @Test
    fun cardListedInTwoRolesOfTheSameArchetypeGetsOneTagWithTheStrongerRole() {
        val index = SynergyIndex(
            SetSynergyProfile(
                set = "TST",
                archetypes = listOf(
                    SynergyArchetype(
                        pair = "BG",
                        name = "Graveyard",
                        payoffs = listOf("Dual Guy"),
                        keyCards = listOf("Dual Guy"),
                    ),
                ),
            ),
        )
        val tags = index.tags("Dual Guy")
        assertEquals(1, tags.size, "role + keyCard overlap must not stack fuel")
        assertEquals(SynergyRole.PAYOFF, tags.single().role)
    }

    @Test
    fun unknownCardHasNoTags() {
        val index = SynergyIndex(profile())
        assertTrue(index.tags("Totally Different Card").isEmpty())
        assertTrue(index.partners("Totally Different Card").isEmpty())
    }

    @Test
    fun bundledMshProfileParsesAndCoversAllTenPairs() {
        val body = javaClass.getResourceAsStream("/synergy/MSH.json")!!.readBytes().decodeToString()
        val json = Json { ignoreUnknownKeys = true }
        val profile = json.decodeFromString<SetSynergyProfile>(body)
        assertEquals("MSH", profile.set)
        assertEquals(
            listOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG"),
            profile.archetypes.map { it.pair },
        )
        for (arch in profile.archetypes) {
            assertTrue(arch.signposts.isNotEmpty(), "${arch.pair} needs signposts")
            assertTrue(arch.payoffs.size + arch.enablers.size >= 4, "${arch.pair} archetype too thin")
        }
        assertTrue(profile.combos.isNotEmpty())
    }
}
