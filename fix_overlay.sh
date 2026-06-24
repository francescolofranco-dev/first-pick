#!/bin/bash
# Remove inline imports
sed -i '' '/import androidx.compose.material.icons.Icons/d' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' '/import androidx.compose.material.icons.filled.Star/d' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' '/import androidx.compose.material3.Icon/d' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' '/import androidx.compose.material3.Surface/d' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' '/import androidx.compose.material3.Divider/d' src/main/kotlin/com/firstpick/ui/Overlay.kt

# Put them at the top
sed -i '' '1i\
import androidx.compose.material.icons.Icons\
import androidx.compose.material.icons.filled.Star\
import androidx.compose.material3.Icon\
import androidx.compose.material3.Surface\
import androidx.compose.material3.Divider\
' src/main/kotlin/com/firstpick/ui/Overlay.kt

# Fix Int.dp
sed -i '' 's/windowX.dp/windowX.toFloat().dp/g' src/main/kotlin/com/firstpick/ui/Overlay.kt
sed -i '' 's/windowY.dp/windowY.toFloat().dp/g' src/main/kotlin/com/firstpick/ui/Overlay.kt

# Fix Syntax error at the end
# We'll just remove the last 4 lines and see if it compiles
tail -n 10 src/main/kotlin/com/firstpick/ui/Overlay.kt > tail_debug.txt
