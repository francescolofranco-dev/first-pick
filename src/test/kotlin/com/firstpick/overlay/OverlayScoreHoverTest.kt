package com.firstpick.overlay

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.firstpick.advisor.ValueBreakdown
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class OverlayScoreHoverTest {
    @Test fun hoverDetailsStayInTheExistingOverlayAcrossPreviewChanges() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Mac") && !GraphicsEnvironment.isHeadless())
        withContext(Dispatchers.Swing) {
            // Deliver events to the badge's real Compose input listener without moving the user's mouse.
            val arena = WindowBounds(200, 200, 800, 500, true)
            val mark = DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0), slot = 0)
            val previewVisible = mutableStateOf(true)
            val preview = mark.copy(bounds = NormalizedRect(.6, .1, .3, .6), slot = -1, isPreview = true)
            val app = launchApplication {
                Window(onCloseRequest = {}, title = "Score hover regression", undecorated = true,
                    transparent = true, alwaysOnTop = true, focusable = false,
                    state = rememberWindowState(position = WindowPosition(arena.x.dp, arena.y.dp), size = DpSize(800.dp, 500.dp))) {
                    LaunchedEffect(window) { withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) } }
                    DraftScoreBadges(listOf(mark), 800.dp, 500.dp)
                    DraftScoreInteractions(listOf(mark) + if (previewVisible.value) listOf(preview) else emptyList(), arena)
                }
            }
            fun detailsWindows() = Frame.getFrames().filter { it.title == "FirstPick Score Details" && it.isVisible }
            try {
                withTimeout(5000) {
                    while (Frame.getFrames().none { it.title == "FirstPick Score 1" && it.isVisible }) delay(25)
                }
                val parent = Frame.getFrames().single { it.title == "Score hover regression" && it.isVisible } as ComposeWindow
                val badge = Frame.getFrames().single { it.title == "FirstPick Score 1" && it.isVisible } as ComposeWindow
                delay(200)
                val input = components(badge).first { c ->
                    c.mouseListeners.any { it.javaClass.name.contains("ComposeSceneMediator") }
                }
                fun send(id: Int, x: Int, y: Int) {
                    val event = MouseEvent(input, id, System.currentTimeMillis(), 0, x, y,
                        arena.x + x, arena.y + y, 0, false, MouseEvent.NOBUTTON)
                    input.dispatchEvent(event)
                }
                fun hasDetails(): Boolean {
                    return parent.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                        .any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "SCORE BREAKDOWN" } }
                }
                send(MouseEvent.MOUSE_ENTERED, input.width / 2, input.height / 2)
                send(MouseEvent.MOUSE_MOVED, input.width / 2, input.height / 2)
                withTimeout(5000) { while (!hasDetails() && detailsWindows().isEmpty()) delay(10) }
                assertTrue(hasDetails(), "Details must draw in the existing overlay so showing them cannot disturb native badge hover")
                assertTrue(detailsWindows().isEmpty(), "A new native details window must never cover the hovered badge")
                repeat(10) {
                    previewVisible.value = !previewVisible.value
                    delay(40)
                    assertTrue(hasDetails(), "Preview changes must preserve the hovered badge's details")
                }
                send(MouseEvent.MOUSE_EXITED, -1, -1)
                withTimeout(5000) { while (hasDetails()) delay(10) }
                assertFalse(hasDetails(), "Leaving the badge must still close its details")
            } finally { app.cancelAndJoin() }
        }
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::components) else emptyList()

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
}
