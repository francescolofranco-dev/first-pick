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
}
