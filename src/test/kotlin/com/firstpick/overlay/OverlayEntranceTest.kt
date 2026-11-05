package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OverlayEntranceTest {
    @Test fun adviceFadesInWithoutReplayingForUpdatesAndHidesImmediately() {
        val visible = mutableStateOf(true)
        val color = mutableStateOf(Color.Red)
        val scene = ImageComposeScene(100, 100) {
            OverlayEntrance(visible.value) {
                Box(Modifier.offset(20.dp, 20.dp).size(60.dp).background(color.value))
            }
        }
        var time = 1_000_000_000L
        fun image() = scene.render(time).use { rendered ->
            rendered.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
        }
        fun advance(frames: Int) { repeat(frames) { time += 16_666_667L; scene.render(time).close() } }
        fun centerAlpha() = image().getRGB(50, 50) ushr 24
        try {
            assertEquals(0, centerAlpha(), "First appearance must start transparent")
            advance(7)
            assertTrue(centerAlpha() in 1..254, "Advice must fade in, not pop directly to opaque")
            advance(24)
            assertEquals(255, centerAlpha())
            assertEquals(0, image().getRGB(10, 10) ushr 24, "Entrance must not move or grow the card geometry")
            color.value = Color.Blue
            assertEquals(255, centerAlpha(), "A routine advice update must not restart the entrance")
            assertEquals(255, image().getRGB(50, 50) and 255, "The new advice must still update during the presentation")
            visible.value = false
            assertEquals(0, centerAlpha(), "Invalid advice must disappear immediately, with no exit animation")
            visible.value = true
            assertEquals(0, centerAlpha(), "Re-entry must not flash the previous opaque state")
            advance(30)
            assertEquals(255, centerAlpha())
        } finally { scene.close() }
    }
}
