package com.firstpick.cards

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory

class SynergyProfileTest {

    private val source = SynergySource(
        id = "tst-mechanics",
        title = "Test Set Mechanics",
        publisher = "Wizards of the Coast",
        author = "Test Author",
        date = "2026-08-15",
        url = "https://magic.wizards.com/en/news/feature/test-set-mechanics",
        kind = SynergySourceKind.OFFICIAL_MECHANICS,
    )
    private val releaseSource = SynergySource(
        id = "tst-release-notes",
        title = "Test Set Release Notes",
        publisher = "Wizards of the Coast",
        author = "Test Author",
        date = "2026-08-15",
        url = "https://magic.wizards.com/en/news/feature/test-set-release-notes",
        kind = SynergySourceKind.OFFICIAL_RELEASE_NOTES,
    )
    private val dataSource = SynergySource(
        id = "tst-limited-data",
        title = "Test Set Limited Data",
        publisher = "17Lands",
        author = "17Lands contributors",
        date = "2026-08-15",
        url = "https://www.17lands.com/public_datasets",
        kind = SynergySourceKind.LIMITED_DATA,
    )

    private fun validCitedProfile() = SetSynergyProfile(
        schemaVersion = SynergyProfileValidator.CURRENT_SCHEMA_VERSION,
        set = "TST",
        setName = "Test Set",
        updatedAt = "2026-08-15",
        sources = listOf(source, releaseSource, dataSource),
        mechanics = listOf(
            SynergyMechanic(
                "Test mechanic",
                "A complete explanation of the test mechanic.",
                listOf(source.id, releaseSource.id),
            ),
        ),
        archetypes = listOf(
            SynergyArchetype(
                pair = "UG",
                name = "Test archetype",
                playstyle = "Use enablers to turn on payoffs and finish the game.",
                speed = "midrange",
                sourceIds = listOf(source.id, dataSource.id),
                signposts = listOf("Signpost"),
                payoffs = listOf("Payoff"),
                enablers = listOf("Enabler"),
                keyCards = listOf("Key card"),
            ),
        ),
        combos = listOf(SynergyCombo(listOf("One", "Two"), "Test combo", listOf(releaseSource.id))),
    )

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

    @Test
    fun provenanceValidatorAcceptsACompleteCitedProfile() {
        assertEquals(emptyList(), SynergyProfileValidator.errors(validCitedProfile(), "TST"))
    }

    @Test
    fun provenanceValidatorRejectsUnsafeDuplicateAndDanglingSourcesForEveryClaimType() {
        val invalid = validCitedProfile().copy(
            schemaVersion = 1,
            updatedAt = "August 15",
            sources = listOf(source.copy(url = "http://example.com"), source, source.copy(id = "same-url")),
            mechanics = listOf(SynergyMechanic("Mechanic", sourceIds = listOf("missing-source"))),
            archetypes = listOf(SynergyArchetype("UG", "Archetype")),
            combos = listOf(SynergyCombo(listOf("One", "Two"), sourceIds = emptyList())),
        )

        val errors = SynergyProfileValidator.errors(invalid, "OTHER")
        assertTrue(errors.any { "schemaVersion" in it })
        assertTrue(errors.any { "updatedAt" in it })
        assertTrue(errors.any { "does not match" in it })
        assertTrue(errors.any { "duplicate source id" in it })
        assertTrue(errors.any { "HTTPS" in it })
        assertTrue(errors.any { "duplicate source URL" in it })
        assertTrue(errors.any { "mechanics[0] references unknown source" in it })
        assertTrue(errors.any { "archetypes[0].sourceIds must not be empty" in it })
        assertTrue(errors.any { "combos[0].sourceIds must not be empty" in it })
    }

    @Test
    fun provenanceValidatorRejectsIncompleteSourceMetadataAndDuplicateClaimReferences() {
        val invalid = validCitedProfile().copy(
            sources = listOf(
                source.copy(
                    id = "NOT STABLE",
                    title = "",
                    publisher = "",
                    author = "",
                    date = "yesterday",
                ),
            ),
            mechanics = listOf(
                SynergyMechanic("Mechanic", sourceIds = listOf("NOT STABLE", "NOT STABLE")),
            ),
            archetypes = listOf(SynergyArchetype("UG", "Archetype", sourceIds = listOf("NOT STABLE"))),
            combos = listOf(SynergyCombo(listOf("One", "Two"), sourceIds = listOf("NOT STABLE"))),
        )

        val errors = SynergyProfileValidator.errors(invalid, "TST")
        assertTrue(errors.any { "stable source id" in it })
        assertTrue(errors.any { "title must not be blank" in it })
        assertTrue(errors.any { "publisher must not be blank" in it })
        assertTrue(errors.any { "author must not be blank" in it })
        assertTrue(errors.any { "date must be an ISO-8601 date" in it })
        assertTrue(errors.any { "mechanics[0].sourceIds contains duplicates" in it })
    }

    @Test
    fun provenanceValidatorRejectsMalformedClaimsAndWrongSourceKinds() {
        val invalid = validCitedProfile().copy(
            setName = "",
            mechanics = listOf(SynergyMechanic("", "", listOf(dataSource.id))),
            archetypes = listOf(
                SynergyArchetype(
                    pair = "ZZ",
                    name = "",
                    playstyle = "",
                    speed = "fast-ish",
                    sourceIds = listOf(releaseSource.id),
                    signposts = listOf("", "Duplicate", "Duplicate"),
                ),
            ),
            combos = listOf(SynergyCombo(listOf("Only one"), "", listOf(dataSource.id))),
        )

        val errors = SynergyProfileValidator.errors(invalid, "TST")

        assertTrue(errors.any { "setName" in it })
        assertTrue(errors.any { "summary" in it })
        assertTrue(errors.any { "supported canonical color pair" in it })
        assertTrue(errors.any { "playstyle" in it })
        assertTrue(errors.any { "speed" in it })
        assertTrue(errors.any { "blank card name" in it })
        assertTrue(errors.any { "duplicate card names" in it })
        assertTrue(errors.any { "at least two distinct cards" in it })
        assertTrue(errors.any { "source kind OFFICIAL_RELEASE_NOTES" in it })
        assertTrue(errors.any { "must cite Limited data" in it })
    }

    @Test
    fun invalidCacheOverrideCannotReplaceAValidBundledProfile() = runBlocking {
        val cache = createTempDirectory("fp-invalid-synergy")
        Files.writeString(cache.resolve("synergy_BLB.json"), """{"set":"BLB"}""")

        val repository = SynergyRepository(cache)
        repository.load("BLB")

        val loaded = repository.index?.profile
        assertEquals("Bloomburrow", loaded?.setName)
        assertFalse(loaded?.sources.isNullOrEmpty())
        assertEquals(emptyList(), loaded?.let { SynergyProfileValidator.errors(it, "BLB") })
    }
}
