package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftScoreBadgeRenderTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun productionFrameClockActuallyAdvancesTheEnergy() {
        val mark = DraftScoreMark(NormalizedRect(.2, .2, .4, .6), 80.0, true,
            highlight = com.firstpick.ui.PickHighlight.CLEAR)
        val scene = androidx.compose.ui.ImageComposeScene(500, 400) { DraftCardHighlights(listOf(mark)) }
        var time = 1_000_000_000L
        fun advance() { time += 100_000_000L; scene.render(time).close() }
        fun pixels(): ByteArray = scene.render(time).use { image -> image.encodeToData()!!.use { it.bytes } }
        try {
            repeat(4) { advance() }
            val before = pixels()
            repeat(12) { advance() }
            assertTrue(!before.contentEquals(pixels()), "Production animation must advance with the Compose frame clock")
        } finally { scene.close() }
    }

    @Test fun previewAuraSurvivesACoveredBadgeWithoutPaintingOverTheTooltip() {
        val mark = DraftScoreMark(NormalizedRect(.1, .2, .2, .5), 80.0, true,
            highlight = com.firstpick.ui.PickHighlight.CLEAR, isPreview = true, covered = true,
            highlightOcclusions = listOf(NormalizedRect(0.0, .2, .18, .1)))
        val file = File("build/reports/preview-highlight-clipping.png")
        file.parentFile.mkdirs()
        renderComposeScene(1000, 700) { DraftScoreBadges(listOf(mark), 1000.dp, 700.dp) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        assertEquals(0, image.getRGB(98, 180) ushr 24, "Tooltip must not be painted over")
        assertTrue(image.getRGB(98, 300) ushr 24 > 200, "Uncovered preview edge must stay visible")
        assertEquals(0, image.getRGB(200, 300) ushr 24, "Card artwork must remain clear")
    }

    @Test fun animationChangesTheAuraWithoutMovingTheCardEdgeOrPaintingItsArtwork() {
        val mark = DraftScoreMark(NormalizedRect(.15, .2, .3, .6), 80.0, true,
            highlight = com.firstpick.ui.PickHighlight.CLEAR)
        fun frame(phase: Float): java.awt.image.BufferedImage {
            val file = File("build/reports/animated-aura-$phase.png")
            file.parentFile.mkdirs()
            renderComposeScene(800, 600) { DraftCardHighlights(listOf(mark), animationPhase = phase) }.use { image ->
                image.encodeToData()!!.use { file.writeBytes(it.bytes) }
            }
            return ImageIO.read(file)
        }
        val before = frame(0f); val after = frame(.25f)
        var changes = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before.getRGB(x, y) != after.getRGB(x, y)) changes++
        }
        assertTrue(changes > 500, "The rendered energy must move, rather than staying static")
        assertEquals(before.getRGB(117, 300), after.getRGB(117, 300), "The hard card edge must stay fixed")
        assertEquals(0, before.getRGB(230, 300) ushr 24)
        assertEquals(0, after.getRGB(230, 300) ushr 24)
    }

    @Test fun interactiveBadgesRenderInTheSameFrameAsAllOtherMarks() {
        val mark = DraftScoreMark(NormalizedRect(.1, .2, .2, .4), 70.0, false,
            breakdown = com.firstpick.advisor.ValueBreakdown(70.0, 0.0, 0.0, 0.0, 0.0, 70.0))
        val file = File("build/reports/interactive-score-frame.png")
        file.parentFile.mkdirs()
        renderComposeScene(1000, 700) { DraftScoreBadges(listOf(mark), 1000.dp, 700.dp) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        val badge = scoreBadgeBounds(mark, 1000f, 700f)!!
        assertTrue(image.getRGB((badge.left + badge.width / 2).toInt(), (badge.top + badge.height / 2).toInt()) ushr 24 > 0,
            "Interactive score badges must be drawn atomically in the shared overlay, not separate fading windows")
    }

    @Test fun retainedCoveredInputSlotsDoNotPaintBadgesOrHighlights() {
        val mark = DraftScoreMark(NormalizedRect(.1, .2, .2, .4), 70.0, false,
            highlight = com.firstpick.ui.PickHighlight.CLEAR, covered = true)
        val file = File("build/reports/covered-score-frame.png")
        file.parentFile.mkdirs()
        renderComposeScene(1000, 700) { DraftScoreBadges(listOf(mark), 1000.dp, 700.dp) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        assertTrue((0 until image.height).all { y -> (0 until image.width).all { x -> image.getRGB(x, y) ushr 24 == 0 } })
    }

    @Test fun broadAuraLeavesAdjacentUnhighlightedArtworkClear() {
        val marks = listOf(
            DraftScoreMark(NormalizedRect(.1, .2, .2, .5), 80.0, true, highlight = com.firstpick.ui.PickHighlight.CLEAR),
            DraftScoreMark(NormalizedRect(.31, .2, .2, .5), 60.0, false),
        )
        val file = File("build/reports/adjacent-pick-aura.png")
        file.parentFile.mkdirs()
        renderComposeScene(1000, 700) { DraftCardHighlights(marks) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        for (x in 315..350) for (y in 200..400) {
            assertEquals(0, image.getRGB(x, y) ushr 24, "Aura painted adjacent artwork at $x, $y")
        }
    }

    @Test fun fullCardBordersDistinguishClearAndSharedChoices() {
        val marks = listOf(
            DraftScoreMark(NormalizedRect(.05, .22, .16, .4), 80.0, true, highlight = com.firstpick.ui.PickHighlight.CLEAR),
            DraftScoreMark(NormalizedRect(.25, .22, .16, .4), 78.0, false, highlight = com.firstpick.ui.PickHighlight.CONTENDER),
            DraftScoreMark(NormalizedRect(.45, .22, .16, .4), 60.0, false),
        )
        val file = File("build/reports/draft-pick-highlights.png")
        file.parentFile.mkdirs()
        renderComposeScene(1280, 740) { DraftScoreBadges(marks, 1280.dp, 740.dp) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        val clearAlpha = image.getRGB(56, 250) ushr 24
        val softAlpha = image.getRGB(312, 250) ushr 24
        assertTrue(clearAlpha > softAlpha && softAlpha > 0)
        assertEquals(0, image.getRGB(577, 250) ushr 24)
        // The aura must remain visible well beyond the previous thin border.
        val redAura = java.awt.Color(image.getRGB(41, 250), true)
        val tealAura = java.awt.Color(image.getRGB(297, 250), true)
        assertTrue(redAura.alpha > 10 && redAura.red > redAura.green)
        assertTrue(tealAura.alpha > 10 && tealAura.green > tealAura.red)
        val goldAura = java.awt.Color(image.getRGB(542, 250), true)
        assertTrue(goldAura.alpha > 10 && goldAura.red > goldAura.blue)
        // The glow stays outside the card: no wash over artwork or rules.
        assertEquals(0, image.getRGB(160, 250) ushr 24)
        assertTrue(image.getRGB(62, 250) ushr 24 > 220)
        val preview = File("build/reports/draft-pick-aura-on-background.png")
        renderComposeScene(1280, 740) {
            Box(Modifier.fillMaxSize().background(Color(0xFF39271F))) {
                DraftScoreBadges(marks, 1280.dp, 740.dp)
            }
        }.use { rendered ->
            rendered.encodeToData()!!.use { preview.writeBytes(it.bytes) }
        }
        // The frame encloses the footer too, instead of ending at the printed rules box.
        assertTrue(image.getRGB(235, 463) ushr 24 > 200)
    }

    @Test fun scoreCrestsSitAtCardBottomsAndLeaveTitlesClear() {
        val marks = listOf(
            DraftScoreMark(NormalizedRect(.05, .22, .16, .4), 78.0, true),
            DraftScoreMark(NormalizedRect(.25, .22, .16, .4), 44.0, false),
            DraftScoreMark(NormalizedRect(.45, .22, .16, .4), null, false),
        )
        val file = File("build/reports/draft-score-badges.png")
        file.parentFile.mkdirs()
        renderComposeScene(1280, 740) { DraftScoreBadges(marks, 1280.dp, 740.dp) }.use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(file)
        for (x in listOf(166, 422, 678)) {
            assertEquals(0, image.getRGB(x, 190) ushr 24)
            assertTrue(image.getRGB(x, 410) ushr 24 > 0)
        }
    }
}
