package com.firstpick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankBadgeTest {

    @Test
    fun gradeLabelsFollowEveryTierThreshold() {
        val cases = listOf(
            null to "—",
            39.9 to "F",
            40.0 to "D",
            47.9 to "D",
            48.0 to "C",
            55.9 to "C",
            56.0 to "B",
            63.9 to "B",
            64.0 to "B+",
            71.9 to "B+",
            72.0 to "A",
            79.9 to "A",
            80.0 to "A+",
        )

        cases.forEach { (value, grade) -> assertEquals(grade, letterGrade(value)) }
    }

    @Test
    fun bombTierStartsAtTheAPlusThreshold() {
        assertTrue(!isBombTier(79.9))
        assertTrue(isBombTier(80.0))
    }

    @Test
    fun everyVisibleGradeTierHasItsOwnColor() {
        val colors = listOf(null, 39.9, 40.0, 48.0, 56.0, 64.0, 72.0, 80.0)
            .map(::valueTierColor)

        assertEquals(colors.size, colors.toSet().size)
    }
}
