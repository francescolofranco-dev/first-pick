package com.firstpick.ui

import androidx.compose.ui.res.loadImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankBadgeTest {

    @Test
    fun rankBasenameFollowsTheGradeThresholds() {
        assertEquals("seals/neutral", rankBasename(null))
        assertEquals("seals/iron", rankBasename(47.9))
        assertEquals("seals/bronze", rankBasename(48.0))
        assertEquals("seals/silver", rankBasename(56.0))
        assertEquals("seals/gold", rankBasename(64.0))
        assertEquals("seals/fire", rankBasename(80.0))
    }

    @Test
    fun fireRankStaysInLockStepWithTheBombTier() {

        assertEquals("seals/gold", rankBasename(79.9))
        assertTrue(!isBombTier(79.9))
        assertEquals("seals/fire", rankBasename(80.0))
        assertTrue(isBombTier(80.0))
    }

    @Test
    fun everyRankBadgeIsBundled() {

        for (v in listOf(null, 30.0, 50.0, 58.0, 70.0, 88.0)) {
            val path = "${rankBasename(v)}.png"
            val stream = this::class.java.classLoader.getResourceAsStream(path)
            assertTrue(stream != null, "missing rank badge: $path")
            stream!!.close()
        }
    }

    @Test
    fun everyRankBadgeIsASquarePng() {


        for (v in listOf(null, 30.0, 50.0, 58.0, 70.0, 88.0)) {
            val path = "${rankBasename(v)}.png"
            val bmp = this::class.java.classLoader.getResourceAsStream(path)!!.use { loadImageBitmap(it) }
            assertTrue(bmp.width == bmp.height && bmp.width >= 128, "not a square badge: $path is ${bmp.width}x${bmp.height}")
        }
    }
}
