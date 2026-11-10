package com.firstpick.overlay

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.firstpick.ui.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DeckRecommendationsContentTest {
    @Test fun comparesBuildsScrollsToLandsAndSelectsTheChosenBuild() {
        val options = listOf("WR", "WU", "WG").map(::deck)
        var chosen: DeckOptionUi? = null
        var closed = false
        var preference: Boolean? = null
        val scene = ImageComposeScene(1200, 760, content = {
            AppTheme {
                DeckRecommendationsContent(options, true, { preference = it }, { chosen = it }, { closed = true })
            }
        })
        var time = 1_000_000_000L
        fun render() { repeat(12) { scene.render(time).close(); time += 16_666_667L } }
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun texts(text: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        fun click(node: SemanticsNode) {
            val action = generateSequence(node) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            assertTrue(assertNotNull(action).invoke())
            render()
        }
        try {
            render()
            assertEquals(3, texts("Use this deck").size)
            assertNotNull(texts("Boros").singleOrNull())
            assertNotNull(texts("Azorius").singleOrNull())
            val output = File("build/reports/deck-recommendations.png")
            output.parentFile.mkdirs()
            scene.render(time).use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            time += 16_666_667L
            val scroll = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            assertTrue(scroll.size >= 3)
            // LazyRow is the first scroll container; each following list scrolls independently.
            assertTrue(scroll[1].invoke(0f, 10_000f))
            render()
            assertTrue(texts("Plains").isNotEmpty(), "Basic lands must be reachable in a recommendation")
            click(texts("Use this deck")[1])
            assertEquals(options[1], chosen)
            val checkbox = nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            click(checkbox)
            assertEquals(false, preference)
            click(texts("Close").single())
            assertTrue(closed)
        } finally { scene.close() }
    }

    @Test fun narrowArenaCanScrollToAndChooseTheLastBuild() {
        val options = listOf("WR", "WU", "WG").map(::deck)
        var chosen: DeckOptionUi? = null
        val scene = ImageComposeScene(520, 500, content = {
            AppTheme { DeckRecommendationsContent(options, false, {}, { chosen = it }, {}) }
        })
        var time = 1_000_000_000L
        fun render() { repeat(24) { scene.render(time).close(); time += 16_666_667L } }
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        try {
            render()
            val scroll = nodes().firstNotNullOf { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            assertTrue(scroll.invoke(10_000f, 0f))
            render()
            val title = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Selesnya" } == true }
            assertTrue(title.boundsInRoot.left >= 0 && title.boundsInRoot.right <= 520)
            val column = generateSequence(title) { it.parent }.first { parent ->
                descendants(parent).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Use this deck" } == true }
            }
            val button = descendants(column).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Use this deck" } == true }
            val action = generateSequence(button) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            assertTrue(action.invoke())
            assertEquals(options.last(), chosen)
            val output = File("build/reports/deck-recommendations-compact.png")
            output.parentFile.mkdirs()
            scene.render(time).use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
        } finally { scene.close() }
    }

    private fun deck(colors: String) = DeckOptionUi(
        colors = colors, basePair = colors, tier = "B", type = "Midrange", outlook = "Balanced build",
        power = 74, identityConfidence = "Medium", identityReasons = emptyList(),
        powerConfidence = "Medium", powerReasons = emptyList(), creatures = 15, removal = 4,
        landLine = "17 lands",
        spells = (1..20).map { DeckSpellUi("Creature $it", 2, colors, null, count = if (it <= 3) 2 else 1, manaCost = "{1}{W}") },
        lands = listOf(DeckSpellUi("Plains", 0, "W", null, count = 17, isLand = true, isBasicLand = true)),
    )
}
