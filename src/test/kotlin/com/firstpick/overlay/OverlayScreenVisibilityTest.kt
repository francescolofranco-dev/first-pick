package com.firstpick.overlay

import kotlin.test.*

class OverlayScreenVisibilityTest {
    private val deck = ArenaMemoryRead.Ready(ArenaMemoryDeck("editor", emptyList(), emptyList(), 0))
    private val pack = ArenaMemoryRead.PackReady(ArenaMemoryPack("draft", listOf(1), emptyList()))

    @Test fun noDialogsBeforeACompatibleScreenIsVerified() {
        for (screen in OverlayScreen.entries) {
            val visibility = OverlayScreenVisibility(screen)
            assertFalse(visibility.visible, "Startup must not display a dialog over an unknown screen")
            assertFalse(visibility.observe(ArenaMemoryRead.Paused("Loading")))
        }
    }

    @Test fun unsupportedBuildAndAccessErrorsNeverReopenAnOverlayOverAMatch() {
        for (screen in OverlayScreen.entries) for (kind in MemoryUnavailableKind.entries) {
            val visibility = OverlayScreenVisibility(screen)
            assertTrue(visibility.observe(if (screen == OverlayScreen.DRAFT) pack else deck))
            assertFalse(visibility.observe(ArenaMemoryRead.Paused("Match in progress", outsideBuilder = true)))
            repeat(3) {
                assertFalse(visibility.observe(ArenaMemoryRead.Unavailable("Cannot verify Arena", kind)))
            }
        }
    }

    @Test fun failuresHideVisibleDialogsUntilTheCorrectScreenReturns() {
        for (screen in OverlayScreen.entries) {
            val visibility = OverlayScreenVisibility(screen)
            val compatible = if (screen == OverlayScreen.DRAFT) pack else deck
            val other = if (screen == OverlayScreen.DRAFT) deck else pack
            assertTrue(visibility.observe(compatible))
            assertTrue(visibility.observe(ArenaMemoryRead.Paused("Hover animation")))
            assertFalse(visibility.observe(ArenaMemoryRead.Unavailable("Unknown screen", MemoryUnavailableKind.UNSUPPORTED)))
            assertFalse(visibility.observe(ArenaMemoryRead.Paused("Still loading")))
            assertFalse(visibility.observe(other))
            assertTrue(visibility.observe(compatible))
            assertFalse(visibility.observe(ArenaMemoryRead.Paused("Screen changed", resetPresentation = true)))
            assertFalse(visibility.observe(ArenaMemoryRead.Paused("Loading")))
            assertTrue(visibility.observe(compatible))
        }
    }
}
