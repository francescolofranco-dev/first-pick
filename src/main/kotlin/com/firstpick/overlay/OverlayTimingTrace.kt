package com.firstpick.overlay

import com.firstpick.core.Log

/** Opt-in local diagnostics; composition is not OS presentation or action latency. */
internal class OverlayTimingTrace(private val surface: String) {
    private val enabled = System.getenv("FIRSTPICK_OVERLAY_TRACE") == "1"
    private var emitted = 0
    private var pending: Any? = null
    private var readStarted = 0L
    private var readEnded = 0L

    fun observed(read: ArenaMemoryRead, frame: Any, startedNs: Long, endedNs: Long) {
        if (!enabled || emitted >= 512) return
        pending = frame; readStarted = startedNs; readEnded = endedNs
        val diagnostics = when (read) {
            is ArenaMemoryRead.Ready -> read.diagnostics
            is ArenaMemoryRead.PackReady -> read.diagnostics
            else -> ArenaMemoryDiagnostics()
        }
        emitted++
        Log.info("OverlayTrace", "surface=$surface read_ms=${(endedNs-startedNs)/1_000_000.0} helper_ms=${diagnostics.elapsedMs} state=${read::class.simpleName} positions_reason=${diagnostics.geometryReason.orEmpty()}")
    }

    fun composed(frame: Any, foreground: Boolean) {
        if (!enabled || !foreground || pending !== frame) return
        pending = null
        Log.info("OverlayTrace", "surface=$surface read_to_compose_ms=${(System.nanoTime()-readStarted)/1_000_000.0} validation_to_compose_ms=${(System.nanoTime()-readEnded)/1_000_000.0}")
    }
}
