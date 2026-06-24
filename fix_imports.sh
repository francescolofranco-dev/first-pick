#!/bin/bash
sed -i '' 's/import androidx.compose.ui.window.WindowPosition/import androidx.compose.ui.window.WindowPosition\nimport androidx.compose.ui.window.application\nimport androidx.compose.ui.window.rememberWindowState/g' src/main/kotlin/com/firstpick/Main.kt
