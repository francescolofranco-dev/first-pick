package com.firstpick.overlay

import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckBadgeRenderTest {
    @Test
    fun highlightsFillCardsRowsAndFrontCardsWithoutDetachedLabels() {
        val model = DeckGuidanceOverlayModel(
            status = DeckGuidanceStatus.NEEDS_CHANGES,
            remainingAdds = 2,
            remainingRemovals = 1,
            basicLandChanges = listOf(CardGuidance("Forest", 3, 2)),
            marks = listOf(
                DeckGuidanceVisualMark.AddCard(NormalizedRect(0.78, 0.40, 0.21, 0.04), inDeckList = true),
                DeckGuidanceVisualMark.RemoveRow(NormalizedRect(0.78, 0.45, 0.21, 0.04), 2),
                DeckGuidanceVisualMark.AddCard(NormalizedRect(0.03, 0.28, 0.13, 0.33)),
                DeckGuidanceVisualMark.RemoveRow(NormalizedRect(0.54, 0.40, 0.1, 0.035), 1, compact = true),
                DeckGuidanceVisualMark.RemoveRow(NormalizedRect(0.54, 0.5, 0.1, 0.25), 1, compact = true),
            ),
        )
        val output = File("build/reports/deck-badges.png")
        output.parentFile.mkdirs()
        renderSettledOverlay(1280, 740) {
            DeckGuidanceOverlay(model, 1280.dp, 740.dp)
        }.use { image ->
            image.encodeToData()!!.use { output.writeBytes(it.bytes) }
        }
        val image = ImageIO.read(output)
        for ((x, y) in listOf(1040 to 310, 1040 to 345, 90 to 250, 90 to 400, 720 to 308, 720 to 420)) {
            assertTrue(image.getRGB(x, y) ushr 24 in 40..60)
        }
        for ((x,y) in listOf(980 to 310, 980 to 344, 60 to 191, 687 to 308, 850 to 420))
            assertEquals(0, image.getRGB(x,y) ushr 24)
        assertEquals("Add 2 cards · Cut 1 card · Add 1 Forest", summaryText(model).text)
    }
}
