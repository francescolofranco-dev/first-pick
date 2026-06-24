#!/bin/bash
sed -i '' 's/import androidx.compose.ui.window.WindowState/import androidx.compose.ui.window.WindowState\nimport androidx.compose.ui.window.rememberWindowState/g' src/main/kotlin/com/firstpick/ui/Overlay.kt
