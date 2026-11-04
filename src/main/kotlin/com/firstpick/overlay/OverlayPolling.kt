package com.firstpick.overlay

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One bounded read at a time; the clock is injectable for timing regressions. */
internal suspend fun runOverlayReadLoop(
    foreground: () -> Boolean,
    clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    readFrame: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val started = clockMs()
        readFrame()
        val intervalMs = if (foreground()) 33L else 400L
        delay((intervalMs - (clockMs() - started)).coerceAtLeast(1L))
    }
}
