package com.firstpick.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.MacOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    val stay = args.contains("stay")
    application {
        val state = rememberWindowState(position = WindowPosition(240.dp, 240.dp), size = DpSize(360.dp, 220.dp))
        Window(
            onCloseRequest = { exitProcess(0) },
            state = state,
            title = "FirstPick ClickThrough Probe",
            transparent = true,
            undecorated = true,
            alwaysOnTop = true,
            focusable = false,
            resizable = false,
        ) {
            LaunchedEffect(Unit) {
                var attempts = 0
                var on = false
                while (!on && attempts < 20) {
                    attempts++
                    on = withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }
                    if (!on) delay(150)
                }
                println("CLICKTHROUGH ON:  ${if (on) "applied+verified" else "FAILED"} (attempts=$attempts)")
                val off = withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, false) }
                println("CLICKTHROUGH OFF: ${if (off) "applied+verified" else "FAILED"}")
                val onAgain = withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }
                println("CLICKTHROUGH RE-ON: ${if (onAgain) "applied+verified" else "FAILED"}")
                println(if (on && off && onAgain) "PROBE: PASS" else "PROBE: FAIL")
                if (!stay) {
                    delay(300)
                    exitProcess(if (on && off && onAgain) 0 else 1)
                }
            }
            Box(Modifier.fillMaxSize().background(Color(0x5500FF88)), contentAlignment = Alignment.Center) {
                Text("click-through probe — clicks should pass through", color = Color.White)
            }
        }
    }
}
