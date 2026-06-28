package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackLayoutTest {

    @Test
    fun producesOneSlotPerCard() {
        assertEquals(13, PackLayout.slots(1280, 748, 13).size)
        assertEquals(0, PackLayout.slots(1280, 748, 0).size)
    }

    @Test
    fun wrapsLeftAlignedInFiveColumns() {
        val s = PackLayout.slots(1280, 748, 13)
        // Column 0 of every row shares the same x; row advances down.
        assertEquals(s[0].x, s[5].x)        // index 5 = row 1, col 0
        assertEquals(s[0].x, s[10].x)       // index 10 = row 2, col 0
        assertTrue(s[5].y > s[0].y)
        assertTrue(s[10].y > s[5].y)
        // Within a row, x strictly increases across the 5 columns.
        for (c in 1 until PackLayout.COLS) assertTrue(s[c].x > s[c - 1].x)
        // Row 0 shares one y.
        for (c in 1 until PackLayout.COLS) assertEquals(s[0].y, s[c].y)
    }

    @Test
    fun scalesWithWindowSize() {
        val small = PackLayout.slots(1280, 748, 5)
        val big = PackLayout.slots(2560, 1496, 5) // 2x
        // Geometry is fractional, so doubling the window ~doubles the coordinates
        // (±1px from independent integer truncation).
        assertTrue(kotlin.math.abs(small[4].x * 2 - big[4].x) <= 1)
        assertTrue(kotlin.math.abs(small[0].w * 2 - big[0].w) <= 1)
    }
}
