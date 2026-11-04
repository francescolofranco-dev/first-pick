package com.firstpick.overlay

import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftScoreBadgeRenderTest {
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
        // The glow stays outside the card: no wash over artwork or rules.
        assertEquals(0, image.getRGB(160, 250) ushr 24)
        assertTrue(image.getRGB(62, 250) ushr 24 > 220)
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
