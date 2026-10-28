package com.firstpick.overlay

import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftScoreBadgeRenderTest {
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
