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
            assertTrue(profile.archetypes.isNotEmpty(), "${entry.code}: needs archetypes")

            val seen = mutableSetOf<String>()
            for (arch in profile.archetypes) {
                assertTrue(arch.pair in pairs, "${entry.code}: bad pair '${arch.pair}'")
                assertTrue(seen.add(arch.pair), "${entry.code}: duplicate pair '${arch.pair}'")
                assertTrue(arch.name.isNotBlank(), "${entry.code} ${arch.pair}: needs a name")
                val roleCards = arch.signposts + arch.payoffs + arch.enablers + arch.keyCards
                assertTrue(roleCards.size >= 4, "${entry.code} ${arch.pair}: archetype too thin")
                assertTrue(roleCards.none { it.isBlank() }, "${entry.code} ${arch.pair}: blank card name")
            }

            SynergyIndex(profile)
        }
    }

    @Test
    fun noOrphanProfilesOutsideTheManifest() {


        val manifest = json.decodeFromString<StandardManifest>(resource("/synergy/standard.json")!!)
        val declared = manifest.sets.mapTo(mutableSetOf()) { it.code.uppercase() }
        assertTrue("MSH" in declared, "MSH must be a declared supported set")
        assertEquals(declared, StandardSets.codes)
    }
}
