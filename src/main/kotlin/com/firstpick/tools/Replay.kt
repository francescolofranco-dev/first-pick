package com.firstpick.tools

import com.firstpick.core.AppPaths
import com.firstpick.draft.DraftTracker
import com.firstpick.model.DraftState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val path: Path = args.firstOrNull()?.let(Path::of) ?: AppPaths.defaultPlayerLog
    println("Replaying: $path")

    val tracker = DraftTracker()
    var prev = tracker.state.value
    var lines = 0
    var events = 0

    Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { seq ->
        for (line in seq) {
            lines++
            if (!tracker.onLine(line)) continue
            events++
            val s = tracker.state.value
            if (s.isStepFrom(prev)) println(s.describeStep(prev))
            prev = s
        }
    }

    val s = tracker.state.value
    println("---")
    println("lines=$lines  draftEvents=$events")
    println("final: set=${s.setCode} format=${s.format} phase=${s.phase} pack=${s.pack} pick=${s.pick} poolSize=${s.pool.size}")
    println("pool grpIds: ${s.pool}")
}

private fun DraftState.isStepFrom(prev: DraftState): Boolean =
    pack != prev.pack || pick != prev.pick || pool.size != prev.pool.size || phase != prev.phase

private fun DraftState.describeStep(prev: DraftState): String {
    val justPicked = (pool - prev.pool.toSet()).takeIf { it.isNotEmpty() }
    val tag = "P${pack}P${pick}".padEnd(7)
    val picked = justPicked?.let { "  picked=$it" } ?: ""
    return "$tag pack=${packCards.size.toString().padStart(2)} pool=${pool.size.toString().padStart(2)}  " +
        "[${format} ${setCode ?: "?"}] ${phase}$picked"
}
