import sys
content = open('src/main/kotlin/com/firstpick/ui/Overlay.kt', 'r').read()
content = content.replace("Icons.Default.Star", "")
content = content.replace("Icon(", "Text(")
open('src/main/kotlin/com/firstpick/ui/Overlay.kt', 'w').write(content)
