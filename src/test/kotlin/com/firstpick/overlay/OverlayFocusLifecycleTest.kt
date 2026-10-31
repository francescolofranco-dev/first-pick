package com.firstpick.overlay

import androidx.compose.ui.window.launchApplication
import com.firstpick.advisor.ValueBreakdown
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse

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
                    DraftScoreInteractions(listOf(DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
                        ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0))), arena)
                    OverlayAccessNotice(arena, OverlayHealth(OverlayHealthState.ACCESS_REQUIRED, "Grant access"),
                        LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED)) {}
                }
            }
            fun windows() = Frame.getFrames().filter { it.title == title || it.title == "FirstPick Arena Access" || it.title == "FirstPick Score 1" }
            suspend fun awaitWindows() = withTimeout(5000) {
                while (windows().count { it.isVisible } != 3) delay(25)
            }
            try {
                awaitWindows()
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
}
