package com.firstpick.overlay

internal enum class OverlayScreen { DRAFT, DECK }

/** Gates the whole window tree, including notices and interactive child windows. */
internal class OverlayScreenVisibility(private val screen: OverlayScreen) {
    var visible: Boolean = false
        private set

    fun observe(read: ArenaMemoryRead): Boolean {
        visible = when (read) {
            is ArenaMemoryRead.Ready -> screen == OverlayScreen.DECK
            is ArenaMemoryRead.PackReady -> screen == OverlayScreen.DRAFT
            // Preserve brief animations only within an already verified screen.
            is ArenaMemoryRead.Paused -> visible && !read.outsideBuilder && !read.resetPresentation
            // Errors do not establish which screen is open. Report them in FirstPick.
            is ArenaMemoryRead.Unavailable -> false
        }
        return visible
    }
}
