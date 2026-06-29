package com.firstpick

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.DraftingOverlay
import com.firstpick.ui.DeckBuilderOverlay
import com.firstpick.ui.DevFlags
import com.firstpick.overlay.ArenaOverlayTracker
import com.firstpick.overlay.CardGrade
import com.firstpick.model.DraftPhase
import com.firstpick.core.AppPaths
import com.firstpick.ui.App
import com.firstpick.ui.DraftViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.nio.file.Path

fun main() {
    AppPaths.ensureDirectories()

    // Allow pointing at a custom log (custom Arena install, or replaying a capture).
    val logPath = System.getenv("FIRSTPICK_LOG")?.let(Path::of) ?: AppPaths.defaultPlayerLog

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val viewModel = DraftViewModel(appScope, logPath = logPath)
    viewModel.start()

    application {
        // Smoke-test hook: `FIRSTPICK_SMOKE=1 ./gradlew run` opens then self-exits.
        if (System.getenv("FIRSTPICK_SMOKE") == "1") {
            LaunchedEffect(Unit) {
                delay(4000)
                appScope.cancel()
                exitApplication()
            }
        }

        val state by viewModel.ui.collectAsState()
        var isOverlayOpen by remember { mutableStateOf(false) }


        Window(
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(720.dp, 760.dp)),
            title = "FirstPick",
        ) {
            App(
                state = state,
                isOverlayOpen = isOverlayOpen,
                onToggleOverlay = { isOverlayOpen = !isOverlayOpen },
                onSelectFormat = { viewModel.setFormatChoice(it) },
                onSimulate = { viewModel.startSimulation(it) },
                onStopSim = { viewModel.stopSimulation() },
                onTogglePause = { viewModel.toggleSimulationPause() }
            )
        }

        if (isOverlayOpen) {
            val isDrafting = state.phase == DraftPhase.DRAFTING || state.phase == DraftPhase.IDLE
            if (isDrafting) {
                // Compact ranked list (top-right) + the embedded on-card grades pinned to Arena.
                DraftingOverlay(state)
                val grades = remember(state.packCards) {
                    state.packCards.map { CardGrade(it.originalIndex, it.value) }
                }
                ArenaOverlayTracker(grades = grades)
            } else {
                val overlayWindowState = rememberWindowState(
                    size = DpSize(1000.dp, 650.dp),
                    position = WindowPosition(Alignment.BottomCenter)
                )
                Window(
                    onCloseRequest = { isOverlayOpen = false },
                    state = overlayWindowState,
                    title = "FirstPick Overlay",
                    undecorated = true,
                    transparent = true,
                    alwaysOnTop = true,
                    // Don't steal keyboard/mouse focus from Arena. Mouse clicks still
                    // reach this window's controls (the checklist needs them); we only
                    // want to avoid grabbing focus away from the game.
                    focusable = false,
                ) {
                    DeckBuilderOverlay(
                        composeWindow = window,
                        state = state,
                        windowState = overlayWindowState,
                        onClose = { isOverlayOpen = false }
                    )
                }
            }
        }

        // Dev calibration: with no draft/overlay open, show the tracker's numbered card boxes
        // to verify alignment against a live Arena window. See DevFlags.overlayTrack.
        else if (DevFlags.overlayTrack) ArenaOverlayTracker()
    }
}
