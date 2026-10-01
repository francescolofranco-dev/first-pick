package com.firstpick.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.firstpick.overlay.DeckSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DeckPaneScrollTest {
    @Test
    fun shortBuilderScrollsPastWrappedOptionsToLandsAndBackToConfirmation() {
        val options = listOf("WU", "UG", "BR").map(::deck)
        var confirmed: DeckOptionUi? = null
        PaneScene(width = 500, height = 180) {
            DeckBuilderPane(
                options = options,
                savedDeck = DeckSnapshot(emptyList(), 0),
                committedDeck = null,
                onUseDeck = { confirmed = it },
                onStopGuidance = {},
            )
        }.use { pane ->
            val firstOption = assertNotNull(pane.textNode(options.first().title))
            val lastOption = assertNotNull(pane.textNode(options.last().title))
            assertTrue(lastOption.positionInRoot.y > firstOption.positionInRoot.y, "Build options must wrap for this regression")
            assertFalse(pane.isTextVisible("Use this build"))
            assertFalse(pane.isTextVisible("Island"))

            pane.scrollBy(10_000f)

            assertTrue(pane.isTextVisible("Island"), "The final land must be reachable in a short pane")
            assertFalse(pane.isTextVisible("Draft complete — choose your build"))

            pane.scrollUntilVisible("Use this build", -100f)
            val action = generateSequence(assertNotNull(pane.textNode("Use this build"))) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            assertTrue(assertNotNull(action).invoke())
            assertEquals(options.first(), confirmed, "Scrolling must preserve the selected build")
        }
    }

    @Test
    fun shortDeckSoFarScrollsPastSummaryAndLandsToTheFinalCut() {
        val cuts = (1..6).map { card("Cut $it") }
        PaneScene(width = 500, height = 96) {
            DeckSoFarPane(deck("WU"), cuts)
        }.use { pane ->
            assertTrue(pane.isTextVisible("Deck so far"))
            assertFalse(pane.isTextVisible("Cut 6"))

            pane.scrollBy(10_000f)

            assertTrue(pane.isTextVisible("Cut 6"), "The final cut must remain reachable below the summary and lands")
            pane.scrollBy(-10_000f)
            assertTrue(pane.isTextVisible("Deck so far"))
        }
    }

    private class PaneScene(width: Int, private val height: Int, content: @Composable () -> Unit) : AutoCloseable {
        private val scene = ImageComposeScene(width, height) { MaterialTheme { content() } }
        private var frameTime = 0L

        init {
            renderFrames(2)
        }

        fun textNode(text: String): SemanticsNode? = nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

        fun isTextVisible(text: String): Boolean {
            val node = textNode(text) ?: return false
            val bounds = node.boundsInRoot
            return bounds.width > 0f && bounds.height > 0f && bounds.top >= 0f && bounds.bottom <= height &&
                abs(bounds.height - node.size.height) < 1f
        }

        fun scrollBy(distance: Float) {
            val scroll = nodes().firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            assertTrue(assertNotNull(scroll, "The pane must expose vertical scrolling").invoke(0f, distance))
            renderFrames(90)
        }

        fun scrollUntilVisible(text: String, distance: Float) {
            repeat(40) {
                if (isTextVisible(text)) return
                scrollBy(distance)
            }
            assertTrue(isTextVisible(text), "Could not scroll back to $text")
        }

        private fun nodes(): Sequence<SemanticsNode> = scene.semanticsOwners.asSequence()
            .flatMap { descendants(it.unmergedRootSemanticsNode) }

        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }

        private fun renderFrames(count: Int) {
            repeat(count) {
                frameTime += 16_666_667L
                scene.render(frameTime).close()
            }
        }

        override fun close() = scene.close()
    }

    private companion object {
        fun card(name: String, land: Boolean = false) = DeckSpellUi(
            name = name,
            cmc = if (land) 0 else 2,
            color = "U",
            gihWr = null,
            isLand = land,
            isBasicLand = land,
        )

        fun deck(colors: String) = DeckOptionUi(
            colors = colors,
            basePair = colors,
            tier = "C+",
            type = "Tempo",
            outlook = "Average build · medium confidence",
            power = 48,
            identityConfidence = "Medium",
            identityReasons = listOf("Play efficient creatures and protect them"),
            powerConfidence = "Medium",
            powerReasons = listOf("A balanced mix of creatures and interaction"),
            creatures = 15,
            removal = 4,
            landLine = "17 lands · 9W · 8U",
            spells = (1..23).map { card("Spell $it") },
            lands = listOf(card("Plains", land = true), card("Island", land = true)),
        )
    }
}
