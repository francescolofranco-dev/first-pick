package com.firstpick.tools

import com.firstpick.cards.CardRepository
import com.firstpick.draft.DraftTracker
import com.firstpick.overlay.CardDetector
import com.firstpick.overlay.CardRecognizer
import com.firstpick.overlay.WindowCapture
import com.firstpick.ui.CardImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO

/** Runs the overlay's capture→detect→recognize pipeline once and reports each stage. */
fun main(args: Array<String>) = runBlocking {
    val framePath = args.getOrNull(0)?.takeIf { it.isNotBlank() }
    val format = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "PremierDraft"

    val frame = if (framePath != null) {
        println("Frame: $framePath")
        ImageIO.read(File(framePath)) ?: error("cannot read $framePath")
    } else {
        println("Frame: live capture")
        WindowCapture().capture() ?: error("capture failed — check Screen Recording permission for your terminal")
    }
    println("  ${frame.width}x${frame.height}")

    val tracker = DraftTracker()
    Files.newBufferedReader(com.firstpick.core.AppPaths.defaultPlayerLog, StandardCharsets.UTF_8)
        .useLines { for (l in it) tracker.onLine(l) }
    val state = tracker.state.value
    println("Draft: set=${state.setCode} P${state.pack}P${state.pick} packCards=${state.packCards.size}")
    val set = state.setCode ?: error("no draft in log")

    val repo = CardRepository()
    repo.load(set, format)
    val pack = repo.resolvePack(state.packCards)
    for (c in pack) println("  ${c.grpId}  ${c.displayName}  img=${if (c.rating?.imageUrl.isNullOrBlank()) "NONE" else "ok"}")

    val grid = CardDetector.detect(frame, pack.size)
    if (grid == null) {
        println("DETECT: FAILED — no grid found for expectedCount=${pack.size}")
        return@runBlocking
    }
    val rects = grid.cards(pack.size)
    println("DETECT: ok — imageW=${grid.imageW} imageH=${grid.imageH} rects=${rects.size}")
    for (r in rects) println("  rect#${r.index}  x=${r.x} y=${r.y} w=${r.w} h=${r.h}")

    val refs = pack.map { c ->
        c.rating?.imageUrl?.takeIf { it.isNotBlank() }?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) }
    }
    println("REFS: ${refs.count { it != null }}/${pack.size} reference signatures")

    val assign = CardRecognizer.match(frame, rects, refs)
    println("MATCH: ${assign.size} assignments")
    for ((rectIdx, cardIdx) in assign.entries.sortedBy { it.key }) {
        println("  rect#$rectIdx -> ${pack[cardIdx].displayName}")
    }
    if (assign.isEmpty()) println("MATCH: FAILED — overlay would render an empty window")
}
