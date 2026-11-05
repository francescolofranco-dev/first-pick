package com.firstpick.overlay

internal data class MemoryOverlayFrame<T>(
    val content: T? = null,
    val geometry: ArenaCardGeometry? = null,
    val health: OverlayHealth = OverlayHealth(OverlayHealthState.READING, "Reading Arena…"),
    val showNotice: Boolean = false,
)

/** Keeps advice and positions fresh independently; called only on the UI thread. */
internal class MemoryOverlayPresentation<T>(private val retentionMs: Long = 1200) {
    private val hover = HoverAnimation()
    private var identity: Any? = null
    private var editor: String? = null
    private var verifiedAt: Long? = null
    private var positionedAt: Long? = null
    private var startedAt: Long? = null
    private var frame = MemoryOverlayFrame<T>()

    fun accepted(key: Any, editorSession: String, content: T, geometry: ArenaCardGeometry?, nowMs: Long): MemoryOverlayFrame<T> {
        if (identity != key || editor != editorSession) {
            positionedAt = null
            hover.reset()
        }
        val positions = if (geometry != null) hover.observe(geometry, nowMs)
            else hover.interrupted(frame.geometry.takeIf { identity == key && positionsFresh(nowMs) })
        if (geometry != null) positionedAt = nowMs
        identity = key; editor = editorSession; verifiedAt = nowMs
        frame = MemoryOverlayFrame(content, positions,
            if (positions != null) OverlayHealth(OverlayHealthState.ACTIVE, "Live card advice and positions are synced.")
            else OverlayHealth(OverlayHealthState.READING, "Card counts are verified; reading card positions…"),
            showNotice = delayed(nowMs))
        return frame
    }

    fun settling(key: Any, editorSession: String, geometry: ArenaCardGeometry?, nowMs: Long, newPack: Boolean = false): MemoryOverlayFrame<T> {
        if (editor != editorSession || (newPack && identity != key)) return clear(nowMs)
        if (geometry != null && frame.content != null) {
            positionedAt = nowMs
            frame = frame.copy(geometry = hover.observe(geometry, nowMs))
        } else if (identity != key) {
            positionedAt = null
            frame = frame.copy(geometry = null)
        }
        if (geometry == null) frame = frame.copy(geometry = hover.interrupted(frame.geometry))
        frame = frame.copy(health = OverlayHealth(OverlayHealthState.READING, "Confirming recent Arena changes…"))
        return expire(nowMs)
    }

    fun interrupted(read: ArenaMemoryRead, nowMs: Long): MemoryOverlayFrame<T> {
        val health = memoryReadHealth(read)
        if (read.resetsPresentation()) return clear(nowMs, health)
        frame = frame.copy(health = health, geometry = hover.interrupted(frame.geometry))
        return expire(nowMs)
    }

    fun clear(nowMs: Long, health: OverlayHealth = OverlayHealth(OverlayHealthState.READING, "Updating Arena…")): MemoryOverlayFrame<T> {
        hover.reset()
        identity = null; editor = null; verifiedAt = null; positionedAt = null
        frame = MemoryOverlayFrame(health = health, showNotice = health.needsAttention || delayed(nowMs))
        return frame
    }

    fun expire(nowMs: Long): MemoryOverlayFrame<T> {
        if (verifiedAt != null && verifiedAt?.let { nowMs - it <= retentionMs } != true) {
            frame = frame.copy(content = null, geometry = null,
                health = if (frame.health.needsAttention) frame.health else OverlayHealth(OverlayHealthState.READING, "Waiting for a fresh Arena reading…"))
        } else if (frame.content != null && !positionsFresh(nowMs)) {
            frame = frame.copy(geometry = null,
                health = OverlayHealth(OverlayHealthState.READING, "Card counts are verified; reading card positions…"))
        }
        if (positionedAt?.let { nowMs - it > 100 } == true) {
            frame = frame.copy(geometry = hover.interrupted(frame.geometry))
        }
        frame = frame.copy(showNotice = frame.health.needsAttention || delayed(nowMs))
        return frame
    }

    private fun positionsFresh(nowMs: Long) = positionedAt?.let { nowMs - it <= retentionMs } == true
    private fun delayed(nowMs: Long): Boolean {
        val start = startedAt ?: nowMs.also { startedAt = it }
        return nowMs - start >= 500
    }
}

internal fun ArenaMemoryRead.resetsPresentation(): Boolean = when (this) {
    is ArenaMemoryRead.Paused -> outsideBuilder || resetPresentation
    is ArenaMemoryRead.Unavailable -> resetPresentation || kind != MemoryUnavailableKind.WAITING
    else -> false
}

internal data class VerifiedDeckOverlay(val model: DeckGuidanceOverlayModel, val names: Map<Int, String>)
