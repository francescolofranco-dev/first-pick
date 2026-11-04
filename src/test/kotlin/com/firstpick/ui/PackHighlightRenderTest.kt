package com.firstpick.ui

import androidx.compose.ui.renderComposeScene
import com.firstpick.model.DraftPhase
import java.io.File
import kotlin.test.Test

class PackHighlightRenderTest {
    @Test fun renderClearTwoWayAndThreeWayPacks() {
        for ((name, values) in listOf(
            "clear" to listOf(85.0, 74.0, 68.0, 60.0),
            "two-way" to listOf(80.0, 78.0, 68.0, 60.0),
            "three-way" to listOf(80.0, 78.0, 75.0, 60.0),
        )) {
            val cards = values.mapIndexed { index, value ->
                PackCardUi(index + 1, rank = index + 1,
                    name = listOf("Sunlit Champion", "River Sage", "Canopy Guardian", "Hearth Spark")[index],
                    color = listOf("W", "U", "G", "R")[index], rarity = "uncommon", gihWr = .60,
                    alsa = 3.2, ata = null, value = value, reasons = listOf("On-color"))
            }
            val output = File("build/reports/pack-highlight-$name.png")
            output.parentFile.mkdirs()
            renderComposeScene(560, 380) {
                AppTheme { PackPane(DraftUiState(phase = DraftPhase.DRAFTING, packCards = cards)) }
            }.use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
        }
    }
}
