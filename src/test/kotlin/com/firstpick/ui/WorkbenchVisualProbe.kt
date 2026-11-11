package com.firstpick.ui

import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.firstpick.overlay.*
import java.nio.file.Path
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat

/** Deterministic, offscreen render of the real production composables; no game, DB or native UI input. */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out=Path.of(args.firstOrNull() ?: "build/workbench-visuals");Files.createDirectories(out)
    for ((width,height) in listOf(720 to 760,1280 to 900,1440 to 960)) {
        ImageComposeScene(width,height,Density(1f)) {
            Workbench(WorkbenchVisualFixtures.state,false,OverlayHealth(OverlayHealthState.INACTIVE),LiveDeckAccess(),{},{},{},null,{}, {}, {}, {}, {}, {}, {}, {},WorkbenchVisualFixtures.stats,WorkbenchVisualFixtures.history,{WorkbenchVisualFixtures.review},{})
        }.use{ scene ->
            var frameTime=0L
            fun render(name:String){repeat(4){frameTime+=16_000_000L;scene.render(frameTime).close()};frameTime+=16_000_000L;scene.render(frameTime).use{image->image.encodeToData(EncodedImageFormat.PNG)!!.use{Files.write(out.resolve("$name-$width.png"),it.bytes)}}}
            fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
            fun click(text:String){val n=scene.semanticsOwners.flatMap{nodes(it.rootSemanticsNode)}.first{it.config.getOrElse(SemanticsProperties.Text){emptyList()}.any{a->a.text==text}&&it.config.contains(SemanticsActions.OnClick)};n.config[SemanticsActions.OnClick].action!!.invoke()}
            render("draft");click("Deckbuilder");render("deck");click("▥ Horizontal");render("deck-horizontal");click("▤ Vertical");click("Analytics");render("analytics");click("Match history");render("history")
            val row=scene.semanticsOwners.flatMap{nodes(it.rootSemanticsNode)}.firstOrNull{it.config.contains(SemanticsActions.OnClick)&&it.config.getOrElse(SemanticsProperties.Text){emptyList()}.any{a->a.text=="FRA" || a.text=="Reality Fracture"}}
            checkNotNull(row){"No archived draft row found"}.config[SemanticsActions.OnClick].action!!.invoke();render("review");click("Pick log");render("review-picks");click("Matches");render("review-matches")
        }
    }
    println("Visual snapshots: $out")
}
