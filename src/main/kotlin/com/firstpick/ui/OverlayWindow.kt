package com.firstpick.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.firstpick.core.AppPaths
import java.awt.MouseInfo
import java.nio.file.Files

/**
 * Queries the position and size of the MTGA window on macOS using a compiled Swift binary
 * (which queries the Quartz window server directly and requires no accessibility/assistive permissions).
 * Falls back to AppleScript if Swift compilation or execution fails.
 */
fun getMTGAWindowBounds(): java.awt.Rectangle? {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("mac")) return null

    try {
        AppPaths.ensureDirectories()
        val binaryPath = AppPaths.appSupport.resolve("get_mtga_bounds")
        if (!Files.exists(binaryPath)) {
            val swiftCode = """
                import Cocoa
                import CoreGraphics

                if let windowList = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] {
                    for window in windowList {
                        let ownerName = window[kCGWindowOwnerName as String] as? String ?? ""
                        if ownerName == "MTGA" {
                            if let boundsDict = window[kCGWindowBounds as String] as? [String: Any],
                               let bounds = CGRect(dictionaryRepresentation: boundsDict as CFDictionary) {
                                print("\(Int(bounds.origin.x)),\(Int(bounds.origin.y)),\(Int(bounds.size.width)),\(Int(bounds.size.height))")
                                break
                            }
                        }
                    }
                }
            """.trimIndent()
            val tempSwift = Files.createTempFile("get_bounds", ".swift")
            Files.writeString(tempSwift, swiftCode)
            val compileProc = ProcessBuilder(
                "swiftc",
                "-o", binaryPath.toAbsolutePath().toString(),
                "-O", tempSwift.toAbsolutePath().toString()
            ).start()
            compileProc.waitFor()
            Files.deleteIfExists(tempSwift)
        }

        if (Files.exists(binaryPath)) {
            val proc = ProcessBuilder(binaryPath.toAbsolutePath().toString()).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && output.isNotEmpty()) {
                val parts = output.split(",").mapNotNull { it.toIntOrNull() }
                if (parts.size == 4) {
                    return java.awt.Rectangle(parts[0], parts[1], parts[2], parts[3])
                }
            }
        }
    } catch (e: Exception) {
        // Fall through to AppleScript fallback
    }

    // Fallback AppleScript
    return try {
        val script = "tell application \"System Events\" to tell process \"MTGA\" to get {position of window 1, size of window 1}"
        val process = ProcessBuilder("osascript", "-e", script).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val numbers = output.replace("{", "").replace("}", "").split(",").mapNotNull { it.trim().toIntOrNull() }
        if (numbers.size == 4) {
            java.awt.Rectangle(numbers[0], numbers[1], numbers[2], numbers[3])
        } else null
    } catch (e: Exception) {
        null
    }
}

/** A modifier that enables dragging a borderless Swing/Compose window by this surface. */
fun Modifier.windowDraggable(window: java.awt.Window): Modifier = this.pointerInput(window) {
    var dx = 0
    var dy = 0
    detectDragGestures(
        onDragStart = { _ ->
            val mouseLoc = MouseInfo.getPointerInfo().location
            val winLoc = window.location
            dx = mouseLoc.x - winLoc.x
            dy = mouseLoc.y - winLoc.y
        },
        onDrag = { change, _ ->
            change.consume()
            val mouseLoc = MouseInfo.getPointerInfo().location
            window.setLocation(mouseLoc.x - dx, mouseLoc.y - dy)
        }
    )
}
