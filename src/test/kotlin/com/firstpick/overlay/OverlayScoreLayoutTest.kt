package com.firstpick.overlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.firstpick.advisor.ValueBreakdown
import com.firstpick.ui.AppTheme
import com.firstpick.ui.ModelExplain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OverlayScoreLayoutTest {
    @Test fun breakdownIsReadableWhenBadgeWindowIsOnlyOneHundredPixelsWide() = runBlocking {
        withContext(Dispatchers.Swing) {
            val mark = DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0, modelShift = 3.8),
                ModelExplain(rank = 4, packSize = 6, soloValue = 71.2), gihWr = .575)
            val hovered = mutableStateOf(false)
            val badgeScene = ImageComposeScene(100, 100) {
                AppTheme { OverlayScoreBadge(mark, 100f) { hovered.value = it } }
            }
            val overlayScene = ImageComposeScene(800, 500) {
                AppTheme { if (hovered.value) OverlayScoreDetails(mark, 800f, 500f) }
            }
            try {
                badgeScene.render().close()
                assertTrue(badgeScene.semanticsOwners.flatMap { allNodes(it.rootSemanticsNode) }
                    .none { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains("GIH") || it.text.contains("%") } },
                    "GIH belongs only in hover details, not the card badge")
                overlayScene.render().close()
                badgeScene.sendPointerEvent(PointerEventType.Enter, Offset(50f, 50f), type = PointerType.Mouse)
                badgeScene.sendPointerEvent(PointerEventType.Move, Offset(51f, 50f), type = PointerType.Mouse)
                badgeScene.render().close()
                delay(250)
                overlayScene.render().close()
                val preview = java.nio.file.Path.of("build/reports/overlay-score-hover.png")
                java.nio.file.Files.createDirectories(preview.parent)
                overlayScene.render().use { image ->
                    image.encodeToData()!!.use { data -> java.nio.file.Files.write(preview, data.bytes) }
                }
                val nodes = overlayScene.semanticsOwners.flatMap { allNodes(it.rootSemanticsNode) }
                for (text in listOf("SCORE BREAKDOWN", "GIH win rate", "57.5%", "Base power", "AI pick adjustment", "Final score")) {
                    val node = nodes.first { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertTrue(layouts.single().lineCount == 1, "$text must fit on one readable line")
                    val layout = layouts.single()
                    assertTrue(layout.getLineRight(0) - layout.getLineLeft(0) <= layout.size.width + 1f,
                        "$text must fit within its measured width (allowing pixel rounding)")
                    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 800)
                    assertTrue(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 500)
                }
                for (text in listOf("AI preference: #4 of 6 in this pack", "Score before AI adjustment: 71.2")) {
                    val node = nodes.first { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    val layout = layouts.single()
                    assertTrue(!layout.didOverflowHeight, "$text must fit vertically")
                    for (line in 0 until layout.lineCount) {
                        assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f,
                            "$text must fit within its measured width (allowing pixel rounding)")
                    }
                }
                badgeScene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f), type = PointerType.Mouse)
                badgeScene.render().close()
                delay(50)
                overlayScene.render().close()
                assertTrue(overlayScene.semanticsOwners.flatMap { allNodes(it.rootSemanticsNode) }
                    .none { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "SCORE BREAKDOWN" } },
                    "Details must disappear when the pointer leaves the badge")
            } finally { badgeScene.close(); overlayScene.close() }
        }
    }

    @Test fun detailsStayVisibleAtEveryViewportCorner() = runBlocking {
        withContext(Dispatchers.Swing) {
            for (x in listOf(0.0, .8)) for (y in listOf(0.0, .6)) {
                val mark = DraftScoreMark(NormalizedRect(x, y, .2, .4), 75.0, true,
                    ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0, modelShift = 3.8),
                    ModelExplain(rank = 4, packSize = 6, soloValue = 71.2), gihWr = .575)
                val scene = ImageComposeScene(800, 500) { AppTheme { OverlayScoreDetails(mark, 800f, 500f) } }
                try {
                    scene.render().close()
                    val labels = scene.semanticsOwners.flatMap { allNodes(it.rootSemanticsNode) }
                        .filter { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isNotEmpty() }
                    assertTrue(labels.isNotEmpty())
                    for (label in labels) {
                        val bounds = label.boundsInRoot
                        assertTrue(bounds.left >= 8 && bounds.right <= 792, "Panel at $x,$y must fit horizontally")
                        assertTrue(bounds.top >= 8 && bounds.bottom <= 492, "Panel at $x,$y must fit vertically")
                        assertTrue(bounds.width > 0 && bounds.height > 0)
                    }
                } finally { scene.close() }
            }
        }
    }

    private fun allNodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::allNodes)
}
