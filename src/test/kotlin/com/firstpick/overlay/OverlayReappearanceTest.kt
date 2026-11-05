package com.firstpick.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.dp
import com.firstpick.ui.PickHighlight
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OverlayReappearanceTest {
    @Test fun restoredBadgeFadesWithoutRestartingUncoveredBadges() {
        val first = DraftScoreMark(NormalizedRect(.1, .2, .2, .4), 70.0, false, slot = 0,
            highlight = PickHighlight.CLEAR)
        val second = first.copy(bounds = NormalizedRect(.6, .2, .2, .4), slot = 1)
        val marks = mutableStateOf(listOf(first, second))
        Frames { DraftScoreBadges(marks.value, 1000.dp, 700.dp) }.use { frames ->
            val a = scoreBadgeBounds(first, 1000f, 700f)!!
            val b = scoreBadgeBounds(second, 1000f, 700f)!!
            fun alpha(rect: GuidanceViewportRect) = frames.alpha((rect.left + rect.width / 2).toInt(), (rect.top + rect.height / 2).toInt())
            assertEquals(0, alpha(a))
            frames.advance(30)
            val opaque = alpha(a)
            assertTrue(opaque > 200)
            val neighboringEdge = frames.alpha(598, 250)
            assertTrue(neighboringEdge > 200)
            marks.value = listOf(first.copy(covered = true), second)
            assertEquals(0, alpha(a))
            assertEquals(opaque, alpha(b))
            assertEquals(neighboringEdge, frames.alpha(598, 250), "Covering another card must not restart this aura")
            marks.value = listOf(second, first)
            assertEquals(0, alpha(a), "An uncovered score must restart at transparent")
            assertEquals(opaque, alpha(b), "Reordering slots must not restart an already visible score")
            assertEquals(neighboringEdge, frames.alpha(598, 250), "Restoring another card must not restart this aura")
            frames.advance(7)
            assertTrue(alpha(a) in 1 until opaque)
            assertEquals(opaque, alpha(b))
            frames.advance(24)
            assertEquals(opaque, alpha(a))
            marks.value = listOf(second, first.copy(value = 76.0))
            assertTrue(alpha(a) > 200, "A score update must not replay its entrance")
        }
    }

    @Test fun restoredAuraAndNewlyUnmaskedPreviewFadeIn() {
        val mark = DraftScoreMark(NormalizedRect(.1, .2, .2, .5), 80.0, true, slot = 0,
            highlight = PickHighlight.CLEAR, isPreview = true)
        val current = mutableStateOf(mark)
        Frames { DraftCardHighlights(listOf(current.value), animationPhase = 0f) }.use { frames ->
            frames.advance(30)
            val opaque = frames.alpha(98, 300)
            assertTrue(opaque > 200)
            current.value = mark.copy(highlight = PickHighlight.NONE)
            assertEquals(0, frames.alpha(98, 300))
            current.value = mark
            assertEquals(0, frames.alpha(98, 300))
            frames.advance(7)
            assertTrue(frames.alpha(98, 300) in 1 until opaque)
            frames.advance(24)
            current.value = mark.copy(highlightOcclusions = listOf(NormalizedRect(0.0, .2, .18, .1)))
            assertEquals(0, frames.alpha(98, 180), "Occluded artwork must be masked immediately")
            frames.advance(30)
            assertEquals(0, frames.alpha(98, 180))
            current.value = mark
            assertEquals(0, frames.alpha(98, 180), "Unmasking must fade in the restored highlight")
            frames.advance(7)
            assertTrue(frames.alpha(98, 180) in 1..254)
            frames.advance(24)
            assertTrue(frames.alpha(98, 180) > 200)
        }
    }

    @Test fun restoredDeckGuidanceFadesIndependently() {
        val first = DeckGuidanceVisualMark.AddCard(NormalizedRect(.1, .4, .2, .4))
        val second = first.copy(bounds = NormalizedRect(.6, .4, .2, .4))
        val marks = mutableStateOf(listOf(first, second))
        Frames { DeckGuidanceOverlay(DeckGuidanceOverlayModel(DeckGuidanceStatus.NEEDS_CHANGES, marks.value), 1000.dp, 700.dp) }.use { frames ->
            frames.advance(30)
            val opaque = frames.alpha(200, 400)
            assertTrue(opaque > 0)
            marks.value = listOf(second)
            assertEquals(0, frames.alpha(200, 400))
            marks.value = listOf(second, first)
            assertEquals(0, frames.alpha(200, 400))
            assertEquals(opaque, frames.alpha(700, 400))
            frames.advance(7)
            assertTrue(frames.alpha(200, 400) in 1 until opaque)
            frames.advance(24)
            assertEquals(opaque, frames.alpha(200, 400))
        }
    }

    private class Frames(content: @Composable () -> Unit) : AutoCloseable {
        private val scene = ImageComposeScene(1000, 700, content = content)
        private var time = 1_000_000_000L
        fun advance(frames: Int) { repeat(frames) { time += 16_666_667L; scene.render(time).close() } }
        fun alpha(x: Int, y: Int): Int {
            Snapshot.sendApplyNotifications()
            scene.render(time).close()
            return scene.render(time).use { rendered ->
                rendered.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)).getRGB(x, y) ushr 24 }
            }
        }
        override fun close() = scene.close()
    }
}
