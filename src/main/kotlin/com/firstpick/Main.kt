package com.firstpick

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
        Window(
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(720.dp, 760.dp)),
            title = "FirstPick",
        ) {
            App(state)
        }
    }
}
