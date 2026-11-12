package com.firstpick.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.firstpick.overlay.*
import kotlin.test.*

/** Layout contracts measured from the approved HTML at the same logical window sizes. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class WorkbenchGeometryTest {
    @Test fun `context panels remain beside content at compact and desktop sizes`() {
        for ((width, height, rail, inspector) in listOf(
            listOf(600,640,148,190), listOf(720,760,148,220), listOf(1280,900,166,274), listOf(1440,960,166,274),
        )) {
            Fixture(width,height).use { ui ->
                assertEquals(rail.toFloat(),ui.tag("workbench-rail").boundsInRoot.width)
                assertFalse(ui.nodes().any{it.config.getOrNull(SemanticsProperties.TestTag)=="workbench-inspector"},"Draft starts with signals hidden")
                for (mode in listOf("Deckbuilder","Analytics")) {
                    ui.click(mode)
                    val bounds=ui.tag("workbench-inspector").boundsInRoot
                    assertEquals(inspector.toFloat(),bounds.width,"$mode context width at $width")
                    assertEquals((width-inspector).toFloat(),bounds.left,"$mode context remains on the right")
                    assertEquals(54f,bounds.top,"$mode context starts directly below toolbar")
                    assertEquals((height-33).toFloat(),bounds.bottom,"$mode context reaches the status bar")
                    assertEquals(rail.toFloat(),ui.tag("workbench-workspace").boundsInRoot.left)
                }
            }
        }
    }

    @Test fun `compact draft exposes every choice by scrolling and archived deck stays readable`() {
        Fixture(720,760).use { ui ->
            assertNotNull(ui.text("Dark Matter Manipulator"))
            val scroll=ui.nodes().first{it.config.contains(SemanticsActions.ScrollBy)&&it.children.any{child->ui.descendants(child).any{n->n.config.getOrNull(SemanticsProperties.Text)?.any{it.text=="Dark Matter Manipulator"}==true}}}
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,10000f))
            ui.render(90)
            val last=assertNotNull(ui.text("Vineslasher Adept")).boundsInRoot
            assertTrue(last.top>100f&&last.bottom<700f,"The fourteenth choice must remain reachable above the pool footer")
            ui.click("Analytics");ui.click("Draft history");ui.click("Reality Fracture")
            assertNotNull(ui.text("Draft review"));assertNotNull(ui.text("← Back to draft history"))
            assertEquals(500f,ui.tag("workbench-inspector").boundsInRoot.left)
            ui.click("Pick log");assertNotNull(ui.text("P1 · 1"))
            ui.click("← Back to draft history");assertNotNull(ui.text("Completed drafts"))
        }
    }

    @Test fun `command K opens search and escape restores the workspace`() {
        Fixture(720,760).use { ui ->
            ui.click("Analytics")
            assertTrue(ui.key(androidx.compose.ui.input.key.Key.K,meta=true))
            assertNotNull(ui.text("Find an expansion, event or date"))
            assertTrue(ui.key(androidx.compose.ui.input.key.Key.Escape))
            assertNull(ui.text("Find an expansion, event or date"))
            assertTrue(ui.key(androidx.compose.ui.input.key.Key.K,meta=true))
            assertNotNull(ui.text("Find an expansion, event or date"))
        }
    }

    @Test fun `empty deck builder waits without exposing build controls then shows available builds`() {
        for (width in listOf(600, 1280)) {
            Fixture(width, 760, DraftUiState()).use { ui ->
                ui.click("Deckbuilder")
                assertNotNull(ui.text("Your next deck starts here"))
                assertNotNull(ui.text("No deck to build yet"))
                assertNull(ui.text("Suggested deck"))
                assertNull(ui.text("Close cuts"))
                assertFalse(ui.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "workbench-inspector" })
                ui.state.value = WorkbenchVisualFixtures.state
                ui.render()
                ui.click("Deckbuilder")
                assertNull(ui.text("Your next deck starts here"))
                assertNotNull(ui.text("Suggested deck"))
                assertNotNull(ui.text("Close cuts"))
                assertNotNull(ui.tag("workbench-inspector"))
            }
        }
    }

    @Test fun `draft results distinguish missing records from event totals and individual matches`() {
        val course = WorkbenchVisualFixtures.courses.first()
        val history = listOf(WorkbenchVisualFixtures.history.first())
        val cases = listOf(
            com.firstpick.stats.PlayerStats(),
            com.firstpick.stats.PlayerStats(courses = listOf(course)),
            WorkbenchVisualFixtures.stats.copy(courses = listOf(course), matches = WorkbenchVisualFixtures.stats.matches.filter { it.courseId == course.id }),
        )
        for ((index, stats) in cases.withIndex()) {
            Fixture(1280, 900, stats = stats, history = history).use { ui ->
                ui.click("Analytics"); ui.click("Draft history"); ui.click("Reality Fracture"); ui.click("Results")
                assertNotNull(ui.text("Recorded matches"))
                assertNull(ui.text("0 wins · 0 losses"))
                if (index == 0) {
                    assertNotNull(ui.text("No event result is linked to this draft."))
                    assertNotNull(ui.text("This saved draft has no linked match details. Its cards and any recorded packs are still available in Deck and Pick log."))
                } else {
                    assertNotNull(ui.text("3 wins · 3 losses · Completed"))
                    if (index == 1) assertNotNull(ui.text("Individual match details are unavailable for this draft. Event records and match details are saved separately."))
                    else {
                        assertNotNull(ui.text("6 recorded · 3 wins · 3 losses"))
                        assertNotNull(ui.text("On the play"))
                    }
                }
            }
        }
    }

    @Test fun `match results rows are read only while draft history rows expose an open affordance`() {
        Fixture(720, 900).use { ui ->
            ui.click("Analytics")
            val matchOutcome = assertNotNull(ui.text("Win"))
            assertNull(
                generateSequence(matchOutcome) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick) },
                "Overview match rows must be read-only and non-clickable",
            )
            assertNull(ui.text("Select a match row for its context."))
            ui.click("Draft history")
            assertNotNull(ui.text("Open"))
            ui.click("Open")
            assertNotNull(ui.text("Draft review"))
        }
    }

    private class Fixture(width:Int,height:Int, initialState: DraftUiState = WorkbenchVisualFixtures.state, stats: com.firstpick.stats.PlayerStats = WorkbenchVisualFixtures.stats, history: List<com.firstpick.draft.DraftHistoryEntry> = WorkbenchVisualFixtures.history):AutoCloseable {
        val state = androidx.compose.runtime.mutableStateOf(initialState)
        private val scene=ImageComposeScene(width,height,Density(1f)) {
            Workbench(state.value,false,OverlayHealth(OverlayHealthState.INACTIVE),LiveDeckAccess(),{},{},{},null,{}, {}, {}, {}, {}, {}, {}, {},stats,history,{WorkbenchVisualFixtures.review})
        }
        private var time=0L
        init{render()}
        fun descendants(n:SemanticsNode):Sequence<SemanticsNode> = sequence{yield(n);n.children.forEach{yieldAll(descendants(it))}}
        fun nodes()=scene.semanticsOwners.asSequence().flatMap{descendants(it.rootSemanticsNode)}
        fun tag(tag:String)=nodes().first{it.config.getOrNull(SemanticsProperties.TestTag)==tag}
        fun text(text:String)=nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{a->a.text==text}==true}
        fun click(text:String){val n=assertNotNull(text(text));val action=generateSequence(n){it.parent}.firstNotNullOfOrNull{it.config.getOrNull(SemanticsActions.OnClick)?.action};assertTrue(assertNotNull(action).invoke());render()}
        fun key(key:androidx.compose.ui.input.key.Key,meta:Boolean=false):Boolean {
            val consumed=scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key,androidx.compose.ui.input.key.KeyEventType.KeyDown,isMetaPressed=meta))
            render();return consumed
        }
        fun render(frames:Int=5){repeat(frames){time+=16_000_000L;scene.render(time).close()}}
        override fun close()=scene.close()
    }
}
