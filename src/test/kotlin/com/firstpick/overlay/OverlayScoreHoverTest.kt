package com.firstpick.overlay

import androidx.compose.ui.window.launchApplication
import androidx.compose.runtime.mutableStateOf
import com.firstpick.advisor.ValueBreakdown
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Point
import java.awt.MouseInfo
import kotlin.test.Test
import kotlin.test.assertTrue

class OverlayScoreHoverTest {
    @Test fun stationaryPointerKeepsDetailsOpen() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Mac") && !GraphicsEnvironment.isHeadless())
        withContext(Dispatchers.Swing) {
            val arena = WindowBounds(200, 200, 800, 500, true)
            val mark = DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0))
            val previewVisible = mutableStateOf(true)
            val preview = mark.copy(bounds = NormalizedRect(.6, .1, .3, .6), slot = -1, isPreview = true)
            withContext(Dispatchers.IO) { Robot().mouseMove(10, 10) }
            val app = launchApplication {
                DraftScoreInteractions(listOf(mark.copy(slot = 0)) + if (previewVisible.value) listOf(preview) else emptyList(), arena)
            }
            fun details() = Frame.getFrames().filter { it.title == "FirstPick Score Details" && it.isVisible }
            try {
                withTimeout(5000) {
                    while (Frame.getFrames().none { it.title == "FirstPick Score 1" && it.isVisible }) delay(25)
                }
                delay(300)
                val rect = scoreBadgeBounds(mark, 800f, 500f)!!
                val target = Point((arena.x + rect.left + rect.width / 2).toInt(),
                    (arena.y + rect.top + rect.height / 2).toInt())
                withContext(Dispatchers.IO) { Robot().mouseMove(target.x, target.y) }
                withTimeout(5000) { while (details().isEmpty()) delay(10) }
                val panel = details().single()
                // Arena may withdraw/re-publish its enlarged preview while the pack badge stays put.
                previewVisible.value = false
                repeat(25) {
                    delay(20)
                    assumeTrue("Native hover check requires an undisturbed pointer",
                        MouseInfo.getPointerInfo().location == target)
                    if (it % 5 == 0) previewVisible.value = !previewVisible.value
                    assertTrue(panel.isVisible && details().singleOrNull() === panel,
                        "Score details must remain open under a stationary pointer (sample $it)")
                }
            } finally { app.cancelAndJoin() }
        }
    }
}
