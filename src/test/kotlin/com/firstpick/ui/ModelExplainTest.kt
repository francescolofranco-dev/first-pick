package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelExplainTest {

    @Test
    fun explainsRankPreModelGradeAndPickTiming() {
        val lines = modelExplainLines(ModelExplain(rank = 1, packSize = 14, soloValue = 30.6, ata = 2.3, alsa = 5.0))
        assertEquals("Model rank #1 of 14 · without model F", lines[0])
        assertEquals("Drafters usually take it ~pick 2", lines[1])
    }

    @Test
    fun demotedBombShowsItsOwnHighGrade() {
        // Falcon-style: stats grade A+, model buries it — the caption keeps the A+ context.
        val lines = modelExplainLines(ModelExplain(rank = 7, packSize = 14, soloValue = 84.9, ata = null, alsa = 4.6))
        assertTrue(lines[0].endsWith("without model A+"), lines[0])
        // ATA missing -> fall back to ALSA phrasing.
        assertEquals("Usually still there ~pick 5", lines[1])
    }

    @Test
    fun omitsTimingWhenNoStats() {
        val lines = modelExplainLines(ModelExplain(rank = 2, packSize = 8, soloValue = 60.0, ata = null, alsa = null))
        assertEquals(1, lines.size)
    }

    @Test
    fun bombTierGatesTheStar() {
        // The star/label consistency rule the UI applies: a demoted bomb (C-tier) is no longer a bomb.
        assertTrue(isBombTier(85.0))
        assertTrue(!isBombTier(52.6))
    }
}
