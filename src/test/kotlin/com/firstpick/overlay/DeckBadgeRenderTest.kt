package com.firstpick.overlay

import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckBadgeRenderTest {
    @Test
    fun badgesRenderOutsideCardNamesAndArtwork() {
        val model = DeckGuidanceOverlayModel(
            status = DeckGuidanceStatus.NEEDS_CHANGES,
            remainingAdds = 2,
            remainingRemovals = 1,
            marks = listOf(
                DeckGuidanceVisualMark.AddCard(NormalizedRect(0.78, 0.40, 0.21, 0.04), inDeckList = true),
                DeckGuidanceVisualMark.RemoveRow(NormalizedRect(0.78, 0.45, 0.21, 0.04), 2),
                DeckGuidanceVisualMark.AddCard(NormalizedRect(0.03, 0.28, 0.13, 0.33)),
            ),
        )
        val output = File("build/reports/deck-badges.png")
        output.parentFile.mkdirs()
        renderComposeScene(1280, 740) {
            DeckGuidanceOverlay(model, 1280.dp, 740.dp)
        }.use { image ->
            image.encodeToData()!!.use { output.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(output)
        // Card interiors and names stay completely clear, including pool artwork.
        for ((x, y) in listOf(1040 to 310, 1040 to 345, 90 to 250, 90 to 400)) {
            assertEquals(0, image.getRGB(x, y) ushr 24)
        }
        assertTrue(image.getRGB(965, 310) ushr 24 > 0)
        assertTrue(image.getRGB(965, 344) ushr 24 > 0)
        assertTrue(image.getRGB(60, 191) ushr 24 > 0)
    }
}
