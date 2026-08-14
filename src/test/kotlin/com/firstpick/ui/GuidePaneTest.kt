package com.firstpick.ui

import com.firstpick.model.DraftPhase
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
}
