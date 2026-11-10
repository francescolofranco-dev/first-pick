package com.firstpick.overlay

import androidx.compose.ui.window.launchApplication
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.firstpick.advisor.ValueBreakdown
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real native windows: hiding a parent must also remove its independent interactive windows. */
class OverlayFocusLifecycleTest {
    @Test fun everyOverlayWindowDisappearsWhenArenaLosesFocus() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Mac") && !GraphicsEnvironment.isHeadless())
        withContext(Dispatchers.Swing) {
            val foreground = WindowBounds(200, 200, 800, 500, true)
            val reading = AtomicReference<WindowBounds?>(foreground)
            val locator = ArenaWindowLocator { reading.get() }
            val title = "Focus lifecycle regression"
            val app = launchApplication {
                ArenaMemoryOverlayWindow(title, OverlayHealth(OverlayHealthState.ACCESS_REQUIRED, "Grant access"),
                    locator = locator, onForegroundChanged = {}, onHealthChanged = {}) { arena ->
                    OverlayScoreDetails(DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                        ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0)), arena.w.toFloat(), arena.h.toFloat())
                    DraftScoreInteractions(listOf(DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                        ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0))), arena)
                    OverlayAccessNotice(arena, OverlayHealth(OverlayHealthState.ACCESS_REQUIRED, "Grant access"),
                        LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED)) {}
                }
            }
            fun windows() = Frame.getFrames().filter { it.title == title || it.title == "FirstPick Arena Access" || it.title == "FirstPick Score 1" || it.title == "FirstPick Score Details" }
            suspend fun awaitWindows() = withTimeout(5000) {
                while (windows().count { it.isVisible && it.title != "FirstPick Score Details" } != 3) delay(25)
            }
            try {
                awaitWindows()
                for (control in windows().filter { it.title != title }) {
                    assertEquals(java.awt.Window.Type.POPUP, control.type,
                        "Overlay controls must use native non-activating panels rather than normal app windows")
                    assertFalse(control.isAutoRequestFocus)
                    assertFalse(control.focusableWindowState)
                    assertNativeNonActivatingPanel(control as ComposeWindow)
                }
                delay(200)
                val overlay = windows().single { it.title == title } as ComposeWindow
                assertTrue(nativeIgnoresMouseEvents(overlay),
                    "The shared badge and details layer must pass pointer events through to the controls and Arena")
                assertFalse(windows().any { it.title == "FirstPick Score Details" },
                    "Showing details must not create another native window over the hovered badge")
                reading.set(foreground.copy(frontmost = false))
                delay(600)
                assertFalse(windows().any { it.isVisible }, "Interactive notice must not survive the hidden Arena overlay")
                reading.set(foreground)
                awaitWindows()
                reading.set(null)
                delay(600)
                assertFalse(windows().any { it.isVisible }, "An unavailable Arena observation must not retain foreground visibility")
            } finally { app.cancelAndJoin() }
        }
    }

    @Test fun accessControlAppearsBeforeAScreenCanBeVerified() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Mac") && !GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("firstpick-access-overlay").toFile()
        val helper = directory.resolve("reader").apply {
            writeText("""
                |#!/bin/sh
                |while IFS= read -r request; do
                |  printf '%s\n' '{"status":"access_required"}'
                |done
            """.trimMargin())
            check(setExecutable(true))
        }
        try {
            for (deckMode in listOf(false, true)) ArenaMemoryReader(helper).use { reader ->
                withContext(Dispatchers.Swing) {
                    val bounds = AtomicReference(WindowBounds(200, 200, 800, 500, true))
                    val locator = ArenaWindowLocator { bounds.get() }
                    val app = launchApplication {
                        if (deckMode) ArenaDeckBuilderOverlay(emptyList(), emptyList(), locator = locator,
                            memoryReader = reader)
                        else ArenaOverlayTracker(emptyList(), emptyList(), null, reader, locator)
                    }
                    fun accessVisible() = Frame.getFrames().any { it.title == "FirstPick Arena Access" && it.isVisible }
                    try {
                        withTimeout(5000) { while (!accessVisible()) delay(25) }
                        assertFalse(Frame.getFrames().any { it.title.startsWith("FirstPick Score ") && it.isVisible },
                            "An access prompt must not expose unverified card advice")
                        bounds.set(bounds.get().copy(frontmost = false))
                        delay(600)
                        assertFalse(accessVisible(), "The access control must disappear outside Arena")
                        bounds.set(bounds.get().copy(frontmost = true))
                        withTimeout(5000) { while (!accessVisible()) delay(25) }
                    } finally { app.cancelAndJoin() }
                    assertFalse(accessVisible(), "Disabling the overlay must dispose the access control")
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun recommendationsHideWithArenaAndUseANonActivatingPanel() = runBlocking {
        assumeTrue(System.getProperty("os.name").contains("Mac") && !GraphicsEnvironment.isHeadless())
        withContext(Dispatchers.Swing) {
            val foreground = WindowBounds(200, 200, 900, 650, true)
            val bounds = AtomicReference<WindowBounds?>(foreground)
            val locator = ArenaWindowLocator { bounds.get() }
            val app = launchApplication {
                DeckRecommendationsOverlay(emptyList(), true, {}, {}, {}, locator)
            }
            fun panel() = Frame.getFrames().firstOrNull { it.title == "FirstPick Deck Recommendations" && it.isVisible }
            suspend fun awaitPanel() = withTimeout(5000) { while (panel() == null) delay(25) }
            try {
                awaitPanel()
                val window = panel() as ComposeWindow
                assertEquals(java.awt.Window.Type.POPUP, window.type)
                assertFalse(window.focusableWindowState)
                assertNativeNonActivatingPanel(window)
                bounds.set(foreground.copy(frontmost = false))
                delay(600)
                assertEquals(null, panel())
                bounds.set(foreground)
                awaitPanel()
                bounds.set(null)
                delay(600)
                assertEquals(null, panel())
            } finally { app.cancelAndJoin() }
        }
    }

    private fun nativeIgnoresMouseEvents(window: ComposeWindow): Boolean {
        val objc = NativeLibrary.getInstance("objc")
        val selector = objc.getFunction("sel_registerName").invokePointer(arrayOf("ignoresMouseEvents"))
        return objc.getFunction("objc_msgSend").invokeInt(arrayOf(Pointer(window.windowHandle), selector)) != 0
    }

    private fun assertNativeNonActivatingPanel(window: ComposeWindow) {
        val objc = NativeLibrary.getInstance("objc")
        val send = objc.getFunction("objc_msgSend")
        fun selector(name: String) = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
        val panel = objc.getFunction("objc_getClass").invokePointer(arrayOf("NSPanel"))
        val handle = Pointer(window.windowHandle)
        assertTrue(send.invokeInt(arrayOf(handle, selector("isKindOfClass:"), panel)) != 0,
            "The actual Cocoa window must be an NSPanel")
        assertTrue(send.invokeLong(arrayOf(handle, selector("styleMask"))) and (1L shl 7) != 0L,
            "The actual Cocoa panel must have NSWindowStyleMaskNonactivatingPanel")
    }

}
