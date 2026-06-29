package com.firstpick.tools

import com.firstpick.overlay.WindowLocator

fun main(args: Array<String>) {
    val app = args.firstOrNull() ?: "MTGA"
    val bounds = WindowLocator(appName = app).locate()
    if (bounds == null) {
        println("$app window not found (is Arena open? on macOS? helper built?)")
    } else {
        println("$app window: x=${bounds.x} y=${bounds.y} w=${bounds.w} h=${bounds.h}")
    }
}
