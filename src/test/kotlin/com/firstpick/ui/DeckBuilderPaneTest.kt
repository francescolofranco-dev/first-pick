package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckBuilderPaneTest {

    @Test
    fun strongestBuildIsTheInitialPreview() {
        assertEquals(
            "WU",
            resolveDeckPreviewKey(
                optionKeys = listOf("WU", "UB", "RG"),
                previewKey = null,
                committedKey = null,
            ),
        )
    }

    @Test
    fun previewFollowsItsStableKeyWhenOptionsReorder() {
        assertEquals(
            "RG",
            resolveDeckPreviewKey(
                optionKeys = listOf("UB", "RG", "WU"),
                previewKey = "RG",
                committedKey = "WU",
            ),
        )
    }

    @Test
    fun committedBuildIsPreviewedWhenThePreviousPreviewDisappears() {
        assertEquals(
            "UB",
            resolveDeckPreviewKey(
                optionKeys = listOf("WU", "UB", "RG"),
                previewKey = "BR",
                committedKey = "UB",
            ),
        )
    }

    @Test
    fun deckSpellUiSupportsMultipleTacticalRoles() {
        val singleRole = DeckSpellUi(
            name = "Fell",
            cmc = 2,
            color = "B",
            gihWr = 0.595,
            roles = listOf("Removal"),
        )
        assertEquals(listOf("Removal"), singleRole.roles)
        assertEquals("Removal", singleRole.role)

        val multiRole = DeckSpellUi(
            name = "Beza, the Bounding Spring",
            cmc = 4,
            color = "W",
            gihWr = 0.626,
            roles = listOf("Fixing", "Draw"),
        )
        assertEquals(listOf("Fixing", "Draw"), multiRole.roles)
        assertEquals("Fixing", multiRole.role)

        val legacyConstructed = DeckSpellUi(
            name = "Valley Mightcaller",
            cmc = 1,
            color = "G",
            gihWr = 0.604,
            imageUrl = null,
            count = 1,
            typeLabel = "Creature",
            role = "Finisher",
            isLand = false,
            isBasicLand = false,
            manaCost = "{G}",
        )
        assertEquals(listOf("Finisher"), legacyConstructed.roles)
        assertEquals("Finisher", legacyConstructed.role)
    }

    @Test
    fun deckRowHeightIsFixedConstant() {
        assertEquals(32, DeckRowHeight.value.toInt())
        assertEquals(168, DeckTypeRoleWidth.value.toInt())
    }
}
