package com.firstpick.overlay

import kotlin.math.abs

/** Masks observed motion immediately. Three fresh readings spanning at least 50 ms
 * within one screen pixel publish a fixed advice anchor; cached frames never count.
 */
internal class HoverAnimation {
    private data class Key(val order: Int, val grpId: Int, val source: Int?)
    private data class Candidate(
        val anchor: NormalizedRect,
        val quietSince: Long,
        val observedAt: Long,
        val samples: Int,
        val settled: Boolean,
        val swept: NormalizedRect,
    )

    private var screen: Pair<Int, Int>? = null
    private var candidates = emptyMap<Key, Candidate>()
    private var tooltipSweep = emptyList<NormalizedRect>()

    fun reset() {
        screen = null
        candidates = emptyMap()
        tooltipSweep = emptyList()
    }

    fun observe(geometry: ArenaCardGeometry, nowMs: Long): ArenaCardGeometry {
        val viewport = geometry.screenWidth to geometry.screenHeight
        if (screen != viewport) reset()
        screen = viewport
        val previews = geometry.cards.filter { it.zone == ArenaCardZone.PREVIEW }
        if (previews.isEmpty()) {
            reset()
            return geometry
        }
        val pack = geometry.cards.filter { it.zone == ArenaCardZone.PACK }
        val next = linkedMapOf<Key, Candidate>()
        val presented = previews.mapIndexed { order, preview ->
            val key = Key(order, preview.grpId, preview.sourceIndex)
            val previous = candidates[key]
            val sameAnchor = previous != null && nowMs - previous.observedAt <= 100 &&
                close(previous.anchor, preview.bounds, geometry)
            val sourceMatches = preview.sourceIndex?.let { pack.getOrNull(it)?.grpId == preview.grpId } ?: true
            val state = if (previous != null && sameAnchor && sourceMatches) {
                val settled = previous.settled || (previous.samples + 1 >= 3 && nowMs - previous.quietSince >= 50)
                previous.copy(observedAt = nowMs, samples = previous.samples + 1,
                    settled = settled, swept = if (settled) previous.anchor else previous.swept)
            } else {
                Candidate(preview.bounds, nowMs, nowMs, 1, false,
                    previous?.swept?.union(preview.bounds) ?: preview.bounds)
            }
            next[key] = state
            preview.copy(bounds = if (state.settled) state.anchor else preview.bounds,
                previewSettled = state.settled,
                occlusionBounds = if (state.settled) state.anchor else state.swept)
        }
        candidates = next
        // Arena reports rollover first and the foreground details preview last.
        // Keep both masks, but never publish advice on the preview behind it.
        val foreground = presented.lastIndex
        tooltipSweep = if (presented[foreground].previewSettled) geometry.occlusions
            else (tooltipSweep + geometry.occlusions).distinct().takeLast(64)
        val visible = presented.mapIndexed { order, preview ->
            if (order == foreground) preview else preview.copy(previewSettled = false)
        }
        var index = 0
        return geometry.copy(cards = geometry.cards.map { if (it.zone == ArenaCardZone.PREVIEW) visible[index++] else it },
            occlusions = tooltipSweep)
    }

    /** A cached rectangle cannot confirm animation completion. Keep its mask, hide its advice. */
    fun interrupted(geometry: ArenaCardGeometry?): ArenaCardGeometry? {
        candidates = emptyMap()
        return geometry?.copy(cards = geometry.cards.map {
            if (it.zone == ArenaCardZone.PREVIEW) it.copy(previewSettled = false) else it
        })
    }

    private fun close(a: NormalizedRect, b: NormalizedRect, geometry: ArenaCardGeometry): Boolean =
        maxOf(abs(a.x - b.x) * geometry.screenWidth, abs(a.right - b.right) * geometry.screenWidth,
            abs(a.y - b.y) * geometry.screenHeight, abs(a.bottom - b.bottom) * geometry.screenHeight) <= 1.0
}
