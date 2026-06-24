
package com.firstpick.ui
import androidx.compose.material3.Surface
import androidx.compose.material3.Divider

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.core.AppPaths
import com.firstpick.model.DraftPhase
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.MouseInfo
import java.nio.file.Files
import kotlin.io.path.exists

@Serializable
data class OverlaySettings(
    val x: Int = 100,
    val y: Int = 100,
    val width: Int = 1000,
    val height: Int = 650,
    val gridLeft: Float = 0.072f,
    val gridTop: Float = 0.095f,
    val gridColGap: Float = 0.150f,
    val gridRowGap: Float = 0.275f,
    val isLocked: Boolean = false
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(): OverlaySettings {
            AppPaths.ensureDirectories()
            val file = AppPaths.configFile
            if (!file.exists()) return OverlaySettings()
            return runCatching {
                val content = Files.readString(file)
                json.decodeFromString<OverlaySettings>(content)
            }.getOrElse { OverlaySettings() }
        }

        fun save(settings: OverlaySettings) {
            runCatching {
                AppPaths.ensureDirectories()
                val content = json.encodeToString(serializer(), settings)
                Files.writeString(AppPaths.configFile, content)
            }
        }
    }
}

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

/**
 * A modifier that enables dragging a borderless Swing window.
 */
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

@Composable
fun DeckBuilderOverlay(
    composeWindow: java.awt.Window,
    state: DraftUiState,
    windowState: WindowState,
    onClose: () -> Unit
) {
    var isMinimized by remember { mutableStateOf(false) }
    var selectedOptionIndex by remember(state.deckOptions) { mutableStateOf<Int?>(null) }
    
    // Auto-resize / adjust layout when switching modes or minimized
    LaunchedEffect(isMinimized, selectedOptionIndex) {
        if (isMinimized) {
            windowState.size = DpSize(150.dp, 40.dp)
        } else {
            // Deck building checklist mode
            if (selectedOptionIndex != null) {
                windowState.size = DpSize(920.dp, 280.dp)
            } else {
                windowState.size = DpSize(620.dp, 190.dp)
            }
        }
    }

    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x3B7FD1C4), RoundedCornerShape(12.dp)),
            color = Color(0xF20F1413)
        ) {
            if (isMinimized) {
                // Minimized floating pill
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (composeWindow != null) Modifier.windowDraggable(composeWindow) else Modifier),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "FP Overlay",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OverlayIconButton(
                            text = "＋",
                            onClick = { isMinimized = false },
                            tooltip = "Expand overlay"
                        )
                        OverlayIconButton(
                            text = "✕",
                            onClick = onClose,
                            tooltip = "Close overlay"
                        )
                    }
                }
            } else {
                // Deck builder mode (Companion Window UI)
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .fillMaxHeight()
                            .background(Color(0x1A7FD1C4))
                            .then(if (composeWindow != null) Modifier.windowDraggable(composeWindow) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            repeat(3) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape))
                                    Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape))
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Draft Complete · Deck Builder Companion",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OverlayIconButton(
                                    text = "－",
                                    onClick = { isMinimized = true },
                                    tooltip = "Minimize overlay"
                                )
                                OverlayIconButton(
                                    text = "✕",
                                    onClick = onClose,
                                    tooltip = "Close overlay"
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            DeckHelperView(
                                options = state.deckOptions,
                                selectedIdx = selectedOptionIndex,
                                onSelectIdx = { selectedOptionIndex = it }
                            )
                        }
                    }
                }
            }
        }
    }
}
/**
 * Sleek integrated banner that sits at the bottom of the card frame.
 */
@Composable
fun OverlayCardBanner(
    scoreText: String,
    isBomb: Boolean,
    scoreVal: Int,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val tierColor = valueTierColor(scoreVal.toDouble())
    
    Box(
        modifier = modifier
            .width(width)
            .height(24.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0x00000000), Color(0xE6000000)),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // A thin accent line at the bottom of the banner
        Box(Modifier.fillMaxWidth().height(2.dp).background(tierColor).align(Alignment.BottomCenter))

        Row(
            modifier = Modifier.padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isBomb) {
                Text(
                    text = "★",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = scoreText,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

@Composable
private fun DeckHelperView(
    options: List<DeckOptionUi>,
    selectedIdx: Int?,
    onSelectIdx: (Int?) -> Unit
) {
    if (options.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Building deck options...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (selectedIdx == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Select a deck build to follow:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                options.forEachIndexed { idx, opt ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .clickable { onSelectIdx(idx) }
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PipRow(opt.pair.toList())
                                Spacer(Modifier.width(6.dp))
                                Text(opt.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.tertiary)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(opt.tier, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Power: ${opt.power}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(opt.type, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(opt.outlook, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    } else {
        val sel = options.getOrElse(selectedIdx) { options.first() }
        val checkedCounts = remember(selectedIdx) { mutableStateMapOf<String, Int>() }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PipRow(sel.pair.toList())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${sel.title} Build Checklist",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${sel.spells.size} spells · lands ${sel.landLine}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Change Build",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable { onSelectIdx(null) }
                        .padding(horizontal = 4.dp)
                )
            }

            val groupedSpells = remember(sel.spells) {
                val groups = sel.spells.groupBy { spell ->
                    val c = spell.color.trim()
                    if (c.length == 1) c.first() else 'M'
                }
                listOf('W', 'U', 'B', 'R', 'G', 'M')
                    .map { it to (groups[it] ?: emptyList()) }
                    .filter { it.second.isNotEmpty() }
            }

            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groupedSpells) { (colorChar, spells) ->
                    val colorName = when (colorChar) {
                        'W' -> "White"
                        'U' -> "Blue"
                        'B' -> "Black"
                        'R' -> "Red"
                        'G' -> "Green"
                        else -> "Multi/Other"
                    }
                    val badgeColor = pipColor(colorChar)

                    Column(
                        modifier = Modifier
                            .width(170.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x0F7FD1C4))
                            .border(1.dp, Color(0x08FFFFFF), RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Box(Modifier.size(8.dp).background(badgeColor, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "$colorName (${spells.size})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val aggregatedSpells = remember(spells) {
                            spells.groupBy { it.name }.map { (name, copies) ->
                                val first = copies.first()
                                Triple(name, copies.size, first.imageUrl)
                            }.sortedBy { it.first }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(aggregatedSpells) { (name, totalCount, imageUrl) ->
                                val checkedCount = checkedCounts[name] ?: 0
                                val isComplete = checkedCount >= totalCount

                                CardPreview(imageUrl) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isComplete) Color(0x1A5FD08C)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                            )
                                            .clickable {
                                                val next = if (checkedCount >= totalCount) 0 else checkedCount + 1
                                                checkedCounts[name] = next
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isComplete) "✓ " else "$checkedCount/$totalCount ",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isComplete) Color(0xFF5FD08C) else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = name,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textDecoration = if (isComplete) TextDecoration.LineThrough else null,
                                            color = if (isComplete) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayIconButton(
    text: String,
    onClick: () -> Unit,
    tooltip: String
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
fun DraftingOverlay(state: DraftUiState) {
    if (state.packCards.isEmpty()) return

    var mtgaBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
    
    // Polling MTGA window bounds on macOS
    LaunchedEffect(Unit) {
        var lastBounds: java.awt.Rectangle? = null
        while (true) {
            val bounds = getMTGAWindowBounds()
            if (bounds != null && bounds != lastBounds) {
                mtgaBounds = bounds
                lastBounds = bounds
            }
            delay(1000)
        }
    }

    val bounds = mtgaBounds ?: return
    
    // Check if window has a titlebar to apply offset
    val ratioWithTitleBar = bounds.width.toFloat() / (bounds.height - 28f)
    val hasTitleBar = kotlin.math.abs(ratioWithTitleBar - 1.777f) < 0.05f || 
                      kotlin.math.abs(ratioWithTitleBar - 1.6f) < 0.05f || 
                      kotlin.math.abs(ratioWithTitleBar - 1.54f) < 0.05f ||
                      kotlin.math.abs(ratioWithTitleBar - 1.33f) < 0.05f
                      
    val clientYOffset = if (hasTitleBar) 28 else 0
    
    // Sleek single list panel positioned top-right of MTGA client
    val panelWidth = 260.dp
    val panelHeight = 420.dp
    
    val windowX = bounds.x + bounds.width - 270 // 260 width + 10 margin
    val windowY = bounds.y + clientYOffset + 40 // offset below top elements
    
    val wState = rememberWindowState(
        position = WindowPosition(windowX.dp, windowY.dp),
        size = DpSize(panelWidth, panelHeight)
    )
    
    // Ensure position updates if bounds change
    LaunchedEffect(windowX, windowY) {
        wState.position = WindowPosition(windowX.dp, windowY.dp)
    }
    
    androidx.compose.ui.window.Window(
        onCloseRequest = {},
        state = wState,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false
    ) {
        MaterialTheme(colorScheme = DraftColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFA121212), // Sleek, almost completely opaque dark background
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header / Lane Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "FirstPick Recommendations",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f)
                        )
                        if (state.laneColors.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.laneColors.forEach { color ->
                                    Pip(color, size = 16.dp)
                                }
                            }
                        }
                    }
                    
                    Divider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // List of cards
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Show top 10 recommended cards
                        items(state.packCards.take(10)) { card ->
                            OverlayListItem(card)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayListItem(card: PackCardUi) {
    val scoreText = card.value?.let { "%.0f".format(it) } ?: "—"
    val isHighTier = (card.value ?: 0.0) >= 70.0
    val isBomb = card.isBomb || isHighTier
    
    val tierColor = if (isBomb) Color(0xFFFFD700) else valueTierColor(card.value ?: 0.0)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isBomb) tierColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
            .border(if (isBomb) 2.dp else 0.dp, if (isBomb) tierColor.copy(alpha = 0.8f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Score Badge
        if (card.breakdown != null) {
            TooltipArea(
                tooltip = { BreakdownTooltip(card.breakdown) }
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tierColor.copy(alpha = if (isBomb) 0.3f else 0.2f))
                        .border(1.dp, tierColor.copy(alpha = if (isBomb) 1.0f else 0.5f), RoundedCornerShape(4.dp))
                        .padding(vertical = 4.dp)
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = scoreText,
                        color = tierColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tierColor.copy(alpha = if (isBomb) 0.3f else 0.2f))
                    .border(1.dp, tierColor.copy(alpha = if (isBomb) 1.0f else 0.5f), RoundedCornerShape(4.dp))
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = scoreText,
                    color = tierColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.width(10.dp))
        
        // Card Name & Colors
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.name,
                    color = Color.White.copy(alpha = if (isBomb) 1.0f else 0.9f),
                    fontWeight = if (isBomb) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (card.isBomb) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "★",
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = valueTierLabel(card.value),
                    color = tierColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            // Show colors if any
            if (card.color.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    card.color.forEach { color ->
                        Pip(color, size = 14.dp)
                    }
                }
            }
        }
    }
}
