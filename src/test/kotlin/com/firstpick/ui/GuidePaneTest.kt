package com.firstpick.ui

import com.firstpick.guide.GuideSource
import com.firstpick.model.DraftPhase
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidePaneTest {
    private val card = PackCardUi(
        grpId = 1,
        rank = 1,
        name = "Guide Test Card",
        color = "U",
        rarity = "common",
        gihWr = 0.58,
        alsa = 4.0,
        ata = 3.5,
    )

    @Test
    fun setGuideStarterOnlyAppearsAtTheFirstLivePick() {
        val firstPick = DraftUiState(
            phase = DraftPhase.DRAFTING,
            setCode = "DSK",
            pack = 1,
            pick = 1,
            packCards = listOf(card),
        )

        assertTrue(shouldShowGuideStarter(firstPick))
        assertFalse(shouldShowGuideStarter(firstPick.copy(pick = 2)))
        assertFalse(shouldShowGuideStarter(firstPick.copy(pack = 2)))
        assertFalse(shouldShowGuideStarter(firstPick.copy(phase = DraftPhase.IDLE)))
        assertFalse(shouldShowGuideStarter(firstPick.copy(packCards = emptyList())))
    }

    @Test
    fun claimCitationsResolveInDeclaredOrderWithoutDanglingOrDuplicateSources() {
        val official = GuideSource(
            id = "official-guide",
            title = "Official Draft Guide",
            publisher = "Wizards of the Coast",
            url = "https://magic.wizards.com/example",
        )
        val data = GuideSource(
            id = "observed-data",
            title = "Observed Limited Data",
            publisher = "17Lands",
            url = "https://www.17lands.com/example",
        )

        val citations = guideSourcesFor(
            listOf("observed-data", "missing", "official-guide", "observed-data"),
            listOf(official, data),
        )

        assertEquals(listOf("observed-data", "official-guide"), citations.map { it.id })
    }

    @Test
    fun guideColorFiltersHaveFullAccessibleNames() {
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green"), "WUBRG".map(::guideColorName))
    }
}
