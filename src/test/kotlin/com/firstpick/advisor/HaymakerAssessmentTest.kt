package com.firstpick.advisor

import com.firstpick.cards.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class HaymakerAssessmentTest {
    private val metrics = SetMetrics(0.55, 0.03)
    private val profile = Json.decodeFromString<SetSynergyProfile>(
        javaClass.getResourceAsStream("/synergy/FRA.json")!!.bufferedReader().use { it.readText() },
    )
    private val index = SynergyIndex(profile)
    private fun card(name: String, id: Int = 1, wr: Double? = 0.65) = RankedCard(
        grpId = id, name = name, rating = CardRating(
            name = name, everDrawnWinRate = wr, everDrawnGameCount = if (wr == null) 0 else 2000,
        ),
    )
    private fun reason(name: String, cmc: Int, creature: Boolean = false, deck: List<RankedCard> = emptyList()) =
        HaymakerAssessment.reason(card(name), metrics, CardMeta(name, cmc, creature, false), index, deck)

    @Test
    fun reviewedCreatureAndPlaneswalkerUseTheirActualImpact() {
        assertTrue(reason("Uldaros Theorix", 6, true)!!.contains("graveyard"))
        assertTrue(reason("The Theorist, Jace Beleren", 4)!!.contains("planeswalker"))
        assertTrue(reason("Ajani Unrelenting", 6)!!.contains("sweep"))
    }

    @Test
    fun conditionalEffectsCannotBypassMissingSetupWithGreatStatistics() {
        assertNull(reason("Kindred Judgment", 7))
        assertNull(reason("Draconic Visitor", 5, true))
        assertNull(reason("Draconic Visitor", 5, true, listOf(card("Aerid Konstrari", 2))))
        assertNotNull(reason("Draconic Visitor", 5, true, listOf(
            card("Aerid Konstrari", 2), card("Aerid Konstrari", 3),
        )))
    }

    @Test
    fun cheapStandoutsNeedAnExplicitReviewButReviewedExceptionsArePossible() {
        val cheap = card("Cheap engine")
        val meta = CardMeta(cheap.name, 3, false, false, isCardDraw = true)
        assertNull(HaymakerAssessment.reason(cheap, metrics, meta, null, emptyList()))
        val reviewed = SynergyIndex(profile.copy(haymakers = listOf(HaymakerReview(
            cheap.name, HaymakerImpact.ENGINE, "Repeated independent card advantage", listOf("fra-release-notes"),
        ))))
        assertNotNull(HaymakerAssessment.reason(cheap, metrics, meta, reviewed, emptyList()))
    }

    @Test
    fun expensiveBodiesAndOrdinaryRemovalAreNotAutomaticHaymakers() {
        val spell = card("Strong card")
        assertNull(HaymakerAssessment.reason(spell, metrics, CardMeta(spell.name, 7, true, false), null, emptyList()))
        assertNull(HaymakerAssessment.reason(spell, metrics,
            CardMeta(spell.name, 5, false, false, isRemoval = true), null, emptyList()))
        val reviewed = SynergyIndex(profile.copy(haymakers = listOf(HaymakerReview(
            spell.name, HaymakerImpact.SWEEPER, "Clears the opposing board", listOf("fra-release-notes"),
        ))))
        assertNotNull(HaymakerAssessment.reason(spell, metrics,
            CardMeta(spell.name, 5, false, false, isRemoval = true), reviewed, emptyList()))
    }

    @Test
    fun curatedImpactWorksWithoutRatingsButPoorReliableResultsVetoIt() {
        val name = "Uldaros Theorix"
        val meta = CardMeta(name, 6, true, false)
        assertNotNull(HaymakerAssessment.reason(card(name, wr = null), metrics, meta, index, emptyList()))
        assertNull(HaymakerAssessment.reason(card(name, wr = 0.54), metrics, meta, index, emptyList()))
    }

    @Test
    fun reviewsHaveValidatedReasonsSourcesAndSupportRequirements() {
        assertTrue(SynergyProfileValidator.isValid(profile, "FRA"))
        val broken = profile.haymakers.first().copy(reason = "", sourceIds = listOf("missing"), minimumSupport = -1)
        val errors = SynergyProfileValidator.errors(profile.copy(haymakers = listOf(broken, broken)))
        assertTrue(errors.any { "reason" in it })
        assertTrue(errors.any { "minimumSupport" in it })
        assertTrue(errors.any { "duplicates a reviewed" in it })
        assertTrue(errors.any { "missing" in it })
    }
}
