package com.firstpick.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.firstpick.advisor.ValueBreakdown
import com.firstpick.ui.AppButton
import com.firstpick.ui.AppTheme
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Manual native-window acceptance fixture; never opens an Arena authorization prompt. */
fun main() = application {
    val arena = WindowBounds(200, 200, 800, 500, frontmost = true)
    var baseVisible by remember { mutableStateOf(true) }
    var grants by remember { mutableStateOf(0) }
    var gameClicks by remember { mutableStateOf(0) }
    val marks = listOf(DraftScoreMark(NormalizedRect(.2, .2, .2, .4), 75.0, true,
        ValueBreakdown(65.0, 3.0, 5.0, -2.0, 4.0, 75.0)))
    val state = rememberWindowState(position = WindowPosition(200.dp, 200.dp), size = DpSize(800.dp, 500.dp))
    Window(onCloseRequest = ::exitApplication, title = "Overlay interaction acceptance", state = state, undecorated = true, visible = baseVisible) {
        AppTheme {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("Synthetic Arena — access clicks: $grants; game clicks: $gameClicks")
                AppButton(onClick = { gameClicks++ }) { Text("Game click target") }
                AppButton(onClick = { baseVisible = false }) { Text("Inspect interaction windows") }
            }
        }
    }
    Window(onCloseRequest = {}, title = "Acceptance click-through layer", state = state,
        transparent = true, undecorated = true, alwaysOnTop = true, focusable = false) {
        LaunchedEffect(window) {
            check(withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) })
        }
        DraftScoreBadges(marks, 800.dp, 500.dp)
        DraftScoreInteractions(marks, arena)
        OverlayAccessNotice(arena, OverlayHealth(OverlayHealthState.ACCESS_REQUIRED, "Grant access"),
            LiveDeckAccess(if (grants == 0) LiveDeckAccessStatus.ACCESS_REQUIRED else LiveDeckAccessStatus.GRANTING)) { grants++ }
    }
}
