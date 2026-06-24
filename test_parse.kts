import java.io.File
var depth = 0
var found = false
File("src/main/kotlin/com/firstpick/ui/Overlay.kt").forEachLine { line ->
    if (line.contains("fun DraftingOverlay")) {
        println("Found at depth: $depth")
        found = true
    }
    depth += line.count { it == '{' }
    depth -= line.count { it == '}' }
}
