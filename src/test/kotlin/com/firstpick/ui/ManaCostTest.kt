package com.firstpick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManaCostTest {
    @Test
    fun symbolsPreservePrintedOrderAndSplitCosts() {
        assertEquals(listOf("X", "2", "W/U", "U/P", "C", "S"), manaCostTokens("{X}{2}{W/U}{U/P}{C}{S}"))
        assertEquals(listOf("1", "U", "//", "2", "B"), manaCostTokens("{1}{U} // {2}{B}"))
        assertEquals(listOf("0"), manaCostTokens("{0}"))
        assertTrue(manaCostTokens("").isEmpty())
        assertEquals("white mana or blue mana", manaSymbolDescription("W/U"))
        assertEquals("blue mana or 2 life", manaSymbolDescription("U/P"))
    }

    @Test
    fun genericColoredHybridAndPhyrexianSymbolsRenderAsArtwork() {
        val output = File("build/reports/mana-cost-symbols.png")
        output.parentFile.mkdirs()
        renderComposeScene(180, 120) {
            MaterialTheme(colorScheme = DraftColorScheme) {
                Column {
                    ManaCost("{2}{U}{U}")
                    Spacer(Modifier.height(8.dp))
                    ManaCost("{X}{W/U}{U/P}{C}{S}")
                    Spacer(Modifier.height(8.dp))
                    ManaCost("{0}")
                }
            }
        }.use { image ->
            image.encodeToData()!!.use { output.writeBytes(it.bytes) }
        }
        val rendered = ImageIO.read(output)
        // The center of each 16px circular symbol must be painted, including generic/X symbols.
        for ((x, y) in listOf(8 to 8, 26 to 8, 44 to 8, 8 to 32, 26 to 32, 44 to 32, 62 to 32, 80 to 32, 8 to 56)) {
            assertTrue(rendered.getRGB(x, y) ushr 24 > 0, "Missing symbol artwork at ($x, $y)")
        }
    }
}
