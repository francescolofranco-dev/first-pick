#!/bin/bash
sed -i '' 's/import androidx.compose.ui.window.Window/import androidx.compose.ui.window.Window\nimport com.firstpick.ui.DraftPhase\nimport com.firstpick.ui.DraftingOverlay\nimport com.firstpick.ui.DeckBuilderOverlay/g' src/main/kotlin/com/firstpick/Main.kt

sed -i '' 's/val scoreText = card.score?.toString() ?: "N\/A"/val scoreText = String.format("%.1f", card.value ?: 0.0)/g' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' 's/val scoreVal = card.score?.toFloat() ?: 0f/val scoreVal = ((card.value ?: 0.0) * 10).toInt()/g' src/main/kotlin/com/firstpick/ui/Overlay.kt
