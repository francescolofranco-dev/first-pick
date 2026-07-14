package com.firstpick.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.core.Log
import com.firstpick.ui.CardImageLoader
import com.firstpick.ui.DevFlags
import com.firstpick.ui.MacOverlay
import com.firstpick.ui.isBombTier
import com.firstpick.ui.rankBasename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "Overlay"
private const val TRACKER_TITLE = "FirstPick Arena Tracker"
private const val POLL_MS = 300L
private const val BOUNDS_GRACE_MS = 2_000L
private const val CLICK_THROUGH_ATTEMPTS = 40
private const val CLICK_THROUGH_RETRY_MS = 150L
private const val CAPTURE_DEBOUNCE_MS = 600L
private const val RECOGNITION_RETRY_MS = 500L
private const val MAX_RECOGNITION_ATTEMPTS = 12
private const val HOVER_POLL_MS = 500L
private const val DEV_DETECT_RETRY_MS = 900L
private const val MIN_CALIBRATION_CARDS = 10

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",

    val originalIndex: Int = -1,
)

private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean)


@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
    calibrationStore: PackGridCalibrationStore = PackGridCalibrationStore(),
) {
    val loc = remember { locator }
    val cap = remember { capturer }
    val store = remember { calibrationStore }


    var bounds by remember { mutableStateOf<WindowBounds?>(null) }
    LaunchedEffect(loc) {
        var lastSeen = 0L
        while (true) {
            val b = withContext(Dispatchers.IO) { loc.locate() }
            val now = System.currentTimeMillis()
            if (b != null) {
                bounds = b
                lastSeen = now
            } else if (now - lastSeen > BOUNDS_GRACE_MS) {
                bounds = null
            }
            delay(POLL_MS)
        }
    }
    val b = bounds ?: return
    val calibrating = cards.isEmpty() && DevFlags.overlayTrack

    val wState = rememberWindowState(
        position = WindowPosition(b.x.dp, b.y.dp),
        size = DpSize(b.w.dp, b.h.dp),
    )
    LaunchedEffect(b.x, b.y, b.w, b.h) {
        wState.position = WindowPosition(b.x.dp, b.y.dp)
        wState.size = DpSize(b.w.dp, b.h.dp)
    }

    var calVersion by remember { mutableStateOf(0) }
    val cal = remember(b.w, b.h, calVersion) { store.get(b.w, b.h) }


    val packKey = remember(cards) { cards.joinToString("|") { "${it.name}#${it.imageUrl}" } }
    val assignmentState = remember(packKey) { mutableStateOf<Map<Int, Int>?>(null) }
    var captureFailed by remember(packKey) { mutableStateOf(false) }

    var devMarks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    val marks = if (calibrating) devMarks else remember(cards, b.w, b.h, cal, assignmentState.value) {
        geometryMarks(cards, cal ?: PackGeometry.DEFAULT, b.w, b.h, assignmentState.value)
    }

    var clickThroughFailed by remember { mutableStateOf(false) }
    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating) && !clickThroughFailed


    LaunchedEffect(packKey, visible, b.w, b.h) {
        if (calibrating || !visible || cards.isEmpty() || assignmentState.value != null) return@LaunchedEffect
        captureFailed = false
        delay(CAPTURE_DEBOUNCE_MS)
        val refs = withContext(Dispatchers.IO) {
            cards.map { c -> c.imageUrl?.let { CardImageLoader.loadBufferedImage(it) }?.let { CardRecognizer.ofCard(it) } }
        }
        val expected = refs.count { it != null }
        if (expected == 0) {
            Log.warn(TAG, "no card art available for recognition; seals stay ungraded")
            captureFailed = true
            return@LaunchedEffect
        }
        var lastFailure = "no frame captured — check Screen Recording permission"
        repeat(MAX_RECOGNITION_ATTEMPTS) {
            val attempt = withContext(Dispatchers.IO) {
                val frame = cap.capture() ?: return@withContext null
                val grid = CardDetector.detect(frame, cards.size)
                val freshCal = if (cards.size >= MIN_CALIBRATION_CARDS) grid?.let { PackGeometry.fromGrid(it) } else null


                val rects = grid?.cards(cards.size)
                    ?: PackGeometry.rects(store.get(b.w, b.h) ?: PackGeometry.DEFAULT, frame.width, frame.height, cards.size)
                Pair(CardRecognizer.match(frame, rects, refs), freshCal)
            }
            val assign = attempt?.first
            when {
                assign == null -> lastFailure = "no frame captured — check Screen Recording permission"
                assign.size < expected -> lastFailure = "recognition incomplete (${assign.size}/$expected) — pack may still be animating"
                else -> {
                    attempt.second?.let {
                        store.put(b.w, b.h, it)
                        calVersion++
                    }
                    assignmentState.value = assign
                    return@LaunchedEffect
                }
            }
            delay(RECOGNITION_RETRY_MS)
        }


        captureFailed = true
        Log.warn(TAG, "$lastFailure after $MAX_RECOGNITION_ATTEMPTS attempts; leaving seals ungraded")
    }


    var hovering by remember(packKey) { mutableStateOf(false) }
    LaunchedEffect(packKey, visible, b.w, b.h) {
        if (calibrating || !visible) {
            hovering = false
            return@LaunchedEffect
        }
        while (true) {
            val frame = withContext(Dispatchers.IO) { cap.capture() }
            hovering = frame != null && CardDetector.isHoverMagnified(frame, cards.size)
            delay(HOVER_POLL_MS)
        }
    }


    LaunchedEffect(calibrating, b.w, b.h) {
        if (!calibrating) return@LaunchedEffect
        while (true) {
            val grid = withContext(Dispatchers.IO) { cap.capture()?.let { CardDetector.detect(it, 15) } }
            if (grid != null) {
                val sx = b.w.toFloat() / grid.imageW
                val sy = b.h.toFloat() / grid.imageH
                devMarks = grid.cards(15).map { r ->
                    Mark((r.x * sx).roundToInt(), (r.y * sy).roundToInt(), (r.w * sx).roundToInt(), (r.h * sy).roundToInt(), null, r.index + 1, false)
                }
            }
            delay(DEV_DETECT_RETRY_MS)
        }
    }

    Window(
        onCloseRequest = {},
        visible = visible && !hovering,
        state = wState,
        title = TRACKER_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {


        var applied by remember { mutableStateOf(false) }
        LaunchedEffect(visible) {
            if (applied) return@LaunchedEffect
            repeat(CLICK_THROUGH_ATTEMPTS) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }) {
                    applied = true


                    clickThroughFailed = false
                    return@LaunchedEffect
                }
                delay(CLICK_THROUGH_RETRY_MS)
            }
            if (visible) {
                clickThroughFailed = true
                Log.warn(TAG, "click-through could not be applied — hiding overlay so clicks reach Arena")
            }
        }
        Box(Modifier.fillMaxSize()) {
            for (m in marks) {
                if (m.number != null) NumberBox(m) else GradeSeal(m)
            }
            if (captureFailed && !calibrating) CaptureHint(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun CaptureHint(modifier: Modifier) {
    Box(
        modifier
            .offset(y = 44.dp)
            .background(Color(0xF01A1512), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFFC02E), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "FirstPick can't read the pack — turn on Screen Recording for it in System Settings › Privacy & Security, then reopen the overlay.",
            color = Color(0xFFF0E6D8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun geometryMarks(
    cards: List<OverlayCard>,
    cal: PackGridCalibration,
    winW: Int,
    winH: Int,
    assignment: Map<Int, Int>?,
): List<Mark> {
    if (cards.isEmpty()) return emptyList()
    val rects = PackGeometry.rects(cal, winW, winH, cards.size)

    if (assignment == null) return rects.map { Mark(it.x, it.y, it.w, it.h, null, null, false) }
    val bestCard = assignment.values.maxByOrNull { cards[it].value ?: Double.NEGATIVE_INFINITY }
    return rects.map { r ->
        val ci = assignment[r.index]
        if (ci == null) Mark(r.x, r.y, r.w, r.h, null, null, false)
        else Mark(r.x, r.y, r.w, r.h, cards[ci].value, null, ci == bestCard)
    }
}


private const val BADGE_SCALE = 0.72f


private fun emblemAnchorY(value: Double?): Float = if (isBombTier(value)) 0.61f else 0.50f

@Composable
private fun GradeSeal(m: Mark) {
    val fire = isBombTier(m.value)
    val badge = (m.w * BADGE_SCALE).coerceIn(60f, 176f)
    val cx = m.x + m.w / 2f


    val cy = m.y + m.h - 0.44f * badge


    val haloAlpha = if (fire) 0.5f else if (m.isBest) 0.34f else 0f
    if (haloAlpha > 0f) {
        val haloColor = if (fire) Color(0xFFFFB020) else Color(0xFF7FD1C4)
        val haloD = badge * 0.72f
        Box(
            Modifier
                .offset((cx - haloD / 2f).dp, (cy - haloD / 2f).dp)
                .size(haloD.dp)
                .background(Brush.radialGradient(listOf(haloColor.copy(alpha = haloAlpha), Color.Transparent))),
        )
    }

    rankPainter(m.value)?.let { painter ->
        val top = cy - emblemAnchorY(m.value) * badge
        Box(Modifier.offset((cx - badge / 2f).dp, top.dp).size(badge.dp)) {
            Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }

    ScoreChip(m.value, badge, cx, cy + 0.34f * badge)
}


@Composable
private fun ScoreChip(value: Double?, badge: Float, cx: Float, cy: Float) {
    val chipH = 0.20f * badge
    Box(
        Modifier.offset((cx - badge / 2f).dp, (cy - chipH / 2f).dp).size(badge.dp, chipH.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xE60E1312))
                .padding(horizontal = (0.09f * badge).dp, vertical = (0.02f * badge).dp),
        ) {
            Text(
                value?.roundToInt()?.toString() ?: "—",
                color = Color(0xFFF2E9D8),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (0.135f * badge).sp,
            )
        }
    }
}

@Composable
private fun rankPainter(value: Double?): Painter? {
    val density = LocalDensity.current
    val base = rankBasename(value)

    return remember(base, density) {
        loadBadge("$base.png", density) ?: loadBadge("$base.svg", density)
    }
}

private fun loadBadge(path: String, density: Density): Painter? = runCatching {
    if (path.endsWith(".png")) useResource(path) { BitmapPainter(loadImageBitmap(it)) }
    else useResource(path) { loadSvgPainter(it, density) }
}.getOrNull()

@Composable
private fun NumberBox(m: Mark) {
    val color = Color(0xFFFF5C8A)
    Box(
        Modifier.offset(m.x.dp, m.y.dp).size(m.w.dp, m.h.dp).border(2.dp, color, RoundedCornerShape(6.dp))
    ) {
        Box(
            Modifier.align(Alignment.TopStart)
                .background(color, RoundedCornerShape(bottomEnd = 6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text("${m.number}", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
