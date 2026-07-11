package com.firstpick.tools

import com.firstpick.cards.CardRepository
import com.firstpick.draft.DraftTracker
import com.firstpick.overlay.CardDetector
import com.firstpick.overlay.CardRecognizer
import com.firstpick.overlay.PackGeometry
import com.firstpick.overlay.WindowCapture
import com.firstpick.ui.CardImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Runs the overlay pipeline against a frame and the live Player.log. Geometry gives the seal
 * positions the overlay ships; detection+recognition give the frame's ground truth for both
 * positions (alignment deltas) and identity. The ORDER CHECK verdict measures how wrong the
 * log-order fallback would be — historically the log's pack order does NOT match the screen
 * (live-disproven 2026-06-29), which is why recognition is the shipped identity source.
 */
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
    for ((i, c) in pack.withIndex()) println("  slot#$i  ${c.grpId}  ${c.displayName}  img=${if (c.rating?.imageUrl.isNullOrBlank()) "NONE" else "ok"}")

    println()
    println("GEOMETRY (seal positions the overlay places, default fractions):")
    val predicted = PackGeometry.rects(PackGeometry.DEFAULT, frame.width, frame.height, pack.size)
    for (r in predicted) println("  slot#${r.index}  x=${r.x} y=${r.y} w=${r.w} h=${r.h}")

    println()
    val grid = CardDetector.detect(frame, pack.size)
    if (grid == null) {
        println("DETECT: no grid in this frame (hovered/animated frames are rejected by design)")
        println("        geometry placement above is unaffected — nothing to cross-check")
        return@runBlocking
    }
    val rects = grid.cards(pack.size)
    println("DETECT: ok — ${rects.size} rects; measured fractions = ${PackGeometry.fromGrid(grid)}")
    var worstDx = 0.0
    var worstDy = 0.0
    for ((d, p) in rects.zip(predicted)) {
        worstDx = maxOf(worstDx, abs(d.x - p.x).toDouble() / frame.width)
        worstDy = maxOf(worstDy, abs(d.y - p.y).toDouble() / frame.height)
    }
    println("  vs geometry: worst dx=${"%.4f".format(worstDx)}w  dy=${"%.4f".format(worstDy)}h  (alignment is fine under ~0.012/0.02)")

    val refs = pack.map { c ->
        c.rating?.imageUrl?.takeIf { it.isNotBlank() }?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) }
    }
    println("REFS: ${refs.count { it != null }}/${pack.size} reference signatures")

    val assign = CardRecognizer.match(frame, rects, refs)
    println("RECOGNIZE: ${assign.size} assignments")
    var mismatches = 0
    for ((rectIdx, cardIdx) in assign.entries.sortedBy { it.key }) {
        val ok = rectIdx == cardIdx
        if (!ok) mismatches++
        println("  rect#$rectIdx -> ${pack[cardIdx].displayName}${if (ok) "" else "   <-- LOG ORDER MISMATCH (log slot $rectIdx holds ${pack[rectIdx].displayName})"}")
    }
    println()
    if (assign.isEmpty()) {
        println("ORDER CHECK: inconclusive — recognition produced no assignments")
    } else if (mismatches == 0) {
        println("ORDER CHECK: log order matches the screen for this pack (${assign.size} cards) — fallback would have been safe")
    } else {
        println("ORDER CHECK: $mismatches of ${assign.size} cards render out of log order — the log-order fallback would mis-grade them (recognition is the shipped source)")
    }
}
