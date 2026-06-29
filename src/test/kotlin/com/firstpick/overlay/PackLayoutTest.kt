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
        assertEquals(s[0].x, s[5].x)
        assertEquals(s[0].x, s[10].x)
        assertTrue(s[5].y > s[0].y)
        assertTrue(s[10].y > s[5].y)
        for (c in 1 until PackLayout.COLS) assertTrue(s[c].x > s[c - 1].x)
        for (c in 1 until PackLayout.COLS) assertEquals(s[0].y, s[c].y)
    }

    @Test
    fun scalesWithWindowSize() {
        val small = PackLayout.slots(1280, 748, 5)
        val big = PackLayout.slots(2560, 1496, 5)
        assertTrue(kotlin.math.abs(small[4].x * 2 - big[4].x) <= 1)
        assertTrue(kotlin.math.abs(small[0].w * 2 - big[0].w) <= 1)
    }
}
