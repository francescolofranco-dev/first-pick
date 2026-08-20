package com.firstpick.cards

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledProfilesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val pairs = setOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG")

    private fun resource(path: String): String? =
        javaClass.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    @Test
    fun everyManifestSetHasAParsableProfileCoveringRealPairs() {
        val manifest = json.decodeFromString<StandardManifest>(resource("/synergy/standard.json")!!)
        assertTrue(manifest.sets.size >= 15, "manifest should list the supported Standard sets")

        for (entry in manifest.sets) {
            val body = resource("/synergy/${entry.code}.json")
            assertTrue(body != null, "missing bundled profile for ${entry.code}")
            val profile = json.decodeFromString<SetSynergyProfile>(body)
            assertEquals(entry.code, profile.set, "${entry.code}: profile.set must match its filename")
            assertEquals(
                emptyList(),
                SynergyProfileValidator.errors(profile, entry.code),
                "${entry.code}: provenance schema must be complete and internally consistent",
            )
            assertTrue(profile.archetypes.isNotEmpty(), "${entry.code}: needs archetypes")

            val sourceIds = profile.sources.mapTo(mutableSetOf()) { it.id }
            assertTrue(profile.mechanics.all { it.sourceIds.isNotEmpty() && sourceIds.containsAll(it.sourceIds) })
            assertTrue(profile.archetypes.all { it.sourceIds.isNotEmpty() && sourceIds.containsAll(it.sourceIds) })
            assertTrue(profile.combos.all { it.sourceIds.isNotEmpty() && sourceIds.containsAll(it.sourceIds) })

            val sourceKinds = profile.sources.associate { it.id to it.kind }
            assertTrue(
                profile.mechanics.all { mechanic ->
                    mechanic.sourceIds.mapNotNull(sourceKinds::get).containsAll(
                        setOf(SynergySourceKind.OFFICIAL_MECHANICS, SynergySourceKind.OFFICIAL_RELEASE_NOTES),
                    )
                },
                "${entry.code}: every mechanic must cite official mechanics and release notes",
            )
            assertTrue(
                profile.archetypes.all { archetype ->
                    SynergySourceKind.LIMITED_DATA in archetype.sourceIds.mapNotNull(sourceKinds::get)
                },
                "${entry.code}: every archetype must cite Limited data",
            )
            assertTrue(
                profile.combos.all { combo ->
                    SynergySourceKind.OFFICIAL_RELEASE_NOTES in combo.sourceIds.mapNotNull(sourceKinds::get)
                },
                "${entry.code}: every combo must cite official release notes",
            )

            val seen = mutableSetOf<String>()
            assertTrue(
                profile.mechanics.all { it.summary.isNotBlank() },
                "${entry.code}: every mechanic needs usable guide prose",
            )
            for (arch in profile.archetypes) {
                assertTrue(arch.pair in pairs, "${entry.code}: bad pair '${arch.pair}'")
                assertTrue(seen.add(arch.pair), "${entry.code}: duplicate pair '${arch.pair}'")
                assertTrue(arch.name.isNotBlank(), "${entry.code} ${arch.pair}: needs a name")
                assertTrue(arch.playstyle.isNotBlank(), "${entry.code} ${arch.pair}: needs a plan")
                assertTrue(
                    arch.speed.lowercase() in setOf("aggro", "tempo", "midrange", "control", "ramp"),
                    "${entry.code} ${arch.pair}: needs a supported speed label",
                )
                val roleCards = arch.signposts + arch.payoffs + arch.enablers + arch.keyCards
                assertTrue(roleCards.size >= 4, "${entry.code} ${arch.pair}: archetype too thin")
                assertTrue(roleCards.none { it.isBlank() }, "${entry.code} ${arch.pair}: blank card name")
            }

            SynergyIndex(profile)
        }
    }

    @Test
    fun hobProfileMatchesTheOfficialLimitedStructureAtResearchedDepth() {
        val profile = json.decodeFromString<SetSynergyProfile>(resource("/synergy/HOB.json")!!)

        assertTrue(StandardSets.isSupported("hob"))
        assertEquals(SynergyTierLevel.RESEARCHED, StandardSets.tier("HOB"))
        assertEquals(setOf("WU", "BR", "BG", "WR", "UG"), profile.archetypes.mapTo(mutableSetOf()) { it.pair })

        val mechanics = profile.mechanics.map { it.name.lowercase() }
        for (required in listOf("storied", "recruit", "hone", "adventure", "amass", "landfall")) {
            assertTrue(mechanics.any { required in it }, "HOB must cover the $required mechanic")
        }

        val officialHighlights = mapOf(
            "WU" to setOf("Bard the Bowman", "Long Lake Nuisance"),
            "BR" to setOf("Bolg of the North", "Misty Mountains Raider"),
            "BG" to setOf("The Chief Warg", "Nighthowl Pursuer"),
            "WR" to setOf("Thorin Oakenshield", "Iron Hills Blacksmith"),
            "UG" to setOf("Silvan Reveler", "Mirkwood Pathmaker"),
        )
        val archetypes = profile.archetypes.associateBy { it.pair }
        for ((pair, highlighted) in officialHighlights) {
            val archetype = archetypes.getValue(pair)
            val roleCards = archetype.signposts + archetype.payoffs + archetype.enablers + archetype.keyCards
            assertTrue(roleCards.containsAll(highlighted), "HOB $pair must include Wizards' highlighted cards")
            assertTrue(roleCards.size >= 12, "HOB $pair needs researched-depth role coverage")
            assertTrue(
                profile.combos.any { combo -> combo.cards.all(roleCards::contains) },
                "HOB $pair needs a researched interaction within the lane",
            )
        }
        assertTrue(profile.combos.size >= 5, "HOB needs researched interaction depth")
    }

    @Test
    fun noOrphanProfilesOutsideTheManifest() {


        val manifest = json.decodeFromString<StandardManifest>(resource("/synergy/standard.json")!!)
        val declared = manifest.sets.mapTo(mutableSetOf()) { it.code.uppercase() }
        assertTrue("MSH" in declared, "MSH must be a declared supported set")
        assertEquals(declared, StandardSets.codes)
    }
}
