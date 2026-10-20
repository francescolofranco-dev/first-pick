package com.firstpick.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.firstpick.guide.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class GuidePaneTest {
    @Test fun setAccordionsExpandCollapseAndShowOnlyThemesAndCardNames() {
        val cards = listOf(GuideCard("Mechanic common", 'U', "common", 0.6, 1000, null))
        val themes = listOf(ThemeReference("WU", "Test mechanic", cards, emptyList()))
        val state = DraftUiState(setReferences = listOf(
            SetReference("TST", "Test Set", themes, cardsLoaded = true),
            SetReference("OTH", "Other Set", themes),
        ))
        val requested = mutableListOf<String>()
        val scene = ImageComposeScene(650, 650) { MaterialTheme { GuidePane(state, requested::add) } }
        var frame = 0L
        fun render() { repeat(3) { frame += 16_666_667; scene.render(frame) } }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(descendants(it)) }
        }
        fun find(text: String): SemanticsNode? = scene.semanticsOwners.asSequence()
            .flatMap { descendants(it.unmergedRootSemanticsNode) }.firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
        fun click(text: String) {
            val action = generateSequence(assertNotNull(find(text))) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            assertTrue(assertNotNull(action).invoke())
            render()
        }
        try {
            render()
            assertNotNull(find("Test Set"))
            assertNotNull(find("Other Set"))
            assertNull(find("Test mechanic"))
            click("Test Set")
            assertEquals(listOf("TST"), requested)
            assertNotNull(find("Test mechanic"))
            assertNotNull(find("Commons"))
            assertNotNull(find("Uncommons"))
            assertNotNull(find("Mechanic common"))
            assertNull(find("Sources"))
            click("Test Set")
            assertNull(find("Test mechanic"))
            click("Other Set")
            assertEquals(listOf("TST", "OTH"), requested)
        } finally { scene.close() }
    }
}
