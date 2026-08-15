package com.firstpick.overlay

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
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
private const val REQUIRED_FULL_RECOGNITIONS = 3
private const val DEV_DETECT_RETRY_MS = 900L
private const val MIN_CALIBRATION_CARDS = 10

data class OverlayCard(
    val value: Double?,
    val imageUrl: String?,
    val name: String = "",

    val originalIndex: Int = -1,
    val isRoom: Boolean = false,
)

private data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, val value: Double?, val number: Int?, val isBest: Boolean)

internal data class RecognitionAttempt(
    val match: CardRecognizer.MatchResult,
    val calibration: PackGridCalibration?,
)

internal class RecognitionSettler(private val required: Int) {
    private var best: RecognitionAttempt? = null
    private var latestCalibration: PackGridCalibration? = null
    var fullRecognitions: Int = 0
        private set

    fun observe(attempt: RecognitionAttempt): RecognitionAttempt? {
        fullRecognitions++
        if (attempt.calibration != null) latestCalibration = attempt.calibration
        val previous = best
        if (previous == null || attempt.match.totalDistance < previous.match.totalDistance) best = attempt
        return best?.copy(calibration = latestCalibration).takeIf { fullRecognitions >= required }
    }
}


@Composable
fun ArenaOverlayTracker(
    cards: List<OverlayCard> = emptyList(),
    locator: WindowLocator = WindowLocator(),
    capturer: WindowCapture = WindowCapture(),
    calibrationStore: PackGridCalibrationStore = PackGridCalibrationStore(),
    onHealthChanged: (OverlayHealth) -> Unit = {},
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
    val calibrating = cards.isEmpty() && DevFlags.overlayTrack

    val packKey = remember(cards) { cards.joinToString("|") { "${it.name}#${it.imageUrl}#${it.isRoom}" } }
    val assignmentState = remember(packKey) { mutableStateOf<Map<Int, Int>?>(null) }
    var captureFailed by remember(packKey) { mutableStateOf(false) }
    var recognitionHealth by remember(packKey) {
        mutableStateOf(
            if (cards.isEmpty() && !calibrating) {
                OverlayHealth(OverlayHealthState.INACTIVE, "Waiting for a draft pack.")
            } else {
                OverlayHealth(OverlayHealthState.READING, "Recognizing the cards in Arena's pack.")
            },
        )
    }
    var devMarks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    var clickThroughFailed by remember { mutableStateOf(false) }
    var clickThroughApplied by remember { mutableStateOf(false) }

    val workHealth = when {
        cards.isEmpty() && !calibrating ->
            OverlayHealth(OverlayHealthState.INACTIVE, "Waiting for a draft pack.")

        calibrating && devMarks.isNotEmpty() ->
            OverlayHealth(OverlayHealthState.ACTIVE, "Arena's pack layout is detected.")

        calibrating ->
            OverlayHealth(OverlayHealthState.READING, "Detecting Arena's pack layout.")

        assignmentState.value != null && clickThroughApplied ->
            OverlayHealth(OverlayHealthState.ACTIVE, "Draft overlay is synced to Arena's pack.")

        assignmentState.value != null ->
            OverlayHealth(OverlayHealthState.READING, "Preparing the draft overlay.")

        else -> recognitionHealth
    }
    val health = resolveOverlayHealth(
        arenaAvailable = bounds != null,
        arenaFrontmost = bounds?.frontmost == true,
        clickThroughFailed = clickThroughFailed,
        work = workHealth,
        allowInBackground = calibrating,
    )
    OverlayHealthEffect(health, onHealthChanged)

    val b = bounds ?: return

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


    val marks = if (calibrating) devMarks else remember(cards, b.w, b.h, cal, assignmentState.value) {
        geometryMarks(cards, cal ?: PackGeometry.DEFAULT, b.w, b.h, assignmentState.value)
    }

    val visible = (b.frontmost || calibrating) && (cards.isNotEmpty() || calibrating) && !clickThroughFailed


    LaunchedEffect(packKey, visible, b.w, b.h) {
        if (calibrating || !visible || cards.isEmpty() || assignmentState.value != null) return@LaunchedEffect
        captureFailed = false
        recognitionHealth = OverlayHealth(
            OverlayHealthState.READING,
            "Recognizing the cards in Arena's pack.",
        )
        delay(CAPTURE_DEBOUNCE_MS)
        val refs = withContext(Dispatchers.IO) {
            cards.map { c ->
                c.imageUrl
                    ?.let { CardImageLoader.loadBufferedImage(it) }
                    ?.let { CardRecognizer.ofCard(it, isRoom = c.isRoom) }
            }
        }
        val expected = refs.count { it != null }
        if (expected == 0) {
            Log.warn(TAG, "no card art available for recognition; seals stay ungraded")
            captureFailed = true
            recognitionHealth = OverlayHealth(
                OverlayHealthState.UNSUPPORTED_LAYOUT,
                "This pack cannot be matched because card art is unavailable.",
            )
            return@LaunchedEffect
        }
        var lastFailure = "no frame captured — check Screen Recording permission"
        var capturedFrames = 0
        val settler = RecognitionSettler(REQUIRED_FULL_RECOGNITIONS)
        repeat(MAX_RECOGNITION_ATTEMPTS) {
            val attempt = withContext(Dispatchers.IO) {
                val frame = cap.capture() ?: return@withContext null
                val grid = CardDetector.detect(frame, cards.size)
                val freshCal = if (cards.size >= MIN_CALIBRATION_CARDS) {
                    grid?.let(PackGeometry::fromGrid)?.takeIf(PackGeometry::isPlausible)
                } else {
                    null
                }


                val rects = grid?.cards(cards.size)
                    ?: PackGeometry.rects(store.get(b.w, b.h) ?: PackGeometry.DEFAULT, frame.width, frame.height, cards.size)
                RecognitionAttempt(CardRecognizer.matchDetailed(frame, rects, refs), freshCal)
            }
            val result = attempt?.match
            when {
                result == null -> {
                    lastFailure = "no frame captured — check Screen Recording permission"
                    recognitionHealth = OverlayHealth(
                        OverlayHealthState.CAPTURE_UNAVAILABLE,
                        "Arena could not be captured. Check Screen Recording permission.",
                    )
                }

                result.assignment.size < expected -> {
                    capturedFrames++
                    lastFailure = "recognition incomplete (${result.assignment.size}/$expected) — pack may still be animating"
                    recognitionHealth = OverlayHealth(
                        OverlayHealthState.READING,
                        "Recognizing Arena's pack (${result.assignment.size}/$expected cards).",
                    )
                }

                else -> {
                    capturedFrames++
                    val settled = settler.observe(requireNotNull(attempt))
                    if (settled != null) {
                        settled.calibration?.let {
                            store.put(b.w, b.h, it)
                            calVersion++
                        }
                        assignmentState.value = settled.match.assignment
                        recognitionHealth = OverlayHealth(
                            OverlayHealthState.ACTIVE,
                            "Draft overlay is synced to Arena's pack.",
                        )
                        return@LaunchedEffect
                    }
                    lastFailure = "recognition settling (${settler.fullRecognitions}/$REQUIRED_FULL_RECOGNITIONS)"
                    recognitionHealth = OverlayHealth(
                        OverlayHealthState.READING,
                        "Confirming Arena's pack (${settler.fullRecognitions}/$REQUIRED_FULL_RECOGNITIONS reads).",
                    )
                }
            }
            delay(RECOGNITION_RETRY_MS)
        }


        captureFailed = true
        recognitionHealth = packRecognitionFailureHealth(capturedFrames)
        Log.warn(TAG, "$lastFailure after $MAX_RECOGNITION_ATTEMPTS attempts; leaving seals ungraded")
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
        visible = visible,
        state = wState,
        title = TRACKER_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {


        LaunchedEffect(visible) {
            if (clickThroughApplied) return@LaunchedEffect
            repeat(CLICK_THROUGH_ATTEMPTS) {
                if (withContext(Dispatchers.IO) { MacOverlay.setClickThrough(window, true) }) {
                    clickThroughApplied = true


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

internal fun packRecognitionFailureHealth(capturedFrames: Int): OverlayHealth =
    if (capturedFrames == 0) {
        OverlayHealth(
            OverlayHealthState.CAPTURE_UNAVAILABLE,
            "Arena could not be captured. Check Screen Recording permission.",
        )
    } else {
        OverlayHealth(
            OverlayHealthState.UNSUPPORTED_LAYOUT,
            "The current pack layout could not be recognized.",
        )
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
            "FirstPick can't capture Arena — enable Screen Recording for FirstPick (or window-capture) in System Settings › Privacy & Security, then reopen the overlay.",
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


private const val BADGE_SCALE = 0.52f
private const val BADGE_MIN_SIZE = 48f
private const val BADGE_MAX_SIZE = 118f
private const val BADGE_CARD_OVERLAP = 0.86f

@Composable
private fun GradeSeal(m: Mark) {
    val badge = (m.w * BADGE_SCALE).coerceIn(BADGE_MIN_SIZE, BADGE_MAX_SIZE)
    val cx = m.x + m.w / 2f
    val top = m.y + m.h - BADGE_CARD_OVERLAP * badge
    val accent = valueTierColor(m.value)

    if (m.isBest) {
        val haloColor = if (isBombTier(m.value)) Color(0xFFFFC02E) else Color(0xFF7FD1C4)
        val haloD = badge * 1.18f
        val haloCenterY = top + badge * 0.49f
        Box(
            Modifier
                .offset((cx - haloD / 2f).dp, (haloCenterY - haloD / 2f).dp)
                .size(haloD.dp)
                .background(Brush.radialGradient(listOf(haloColor.copy(alpha = 0.38f), Color.Transparent))),
        )
    }

    Box(Modifier.offset((cx - badge / 2f).dp, top.dp).size(badge.dp)) {
        PickCrest(accent, Modifier.fillMaxSize())

        if (m.value == null) {
            Text(
                "—",
                modifier = Modifier.align(Alignment.Center).offset(y = (-0.02f * badge).dp),
                color = accent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (0.25f * badge).sp,
                lineHeight = (0.25f * badge).sp,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).offset(y = (-0.015f * badge).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    letterGrade(m.value),
                    color = accent,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (0.245f * badge).sp,
                    lineHeight = (0.23f * badge).sp,
                    maxLines = 1,
                )
                Text(
                    m.value.roundToInt().toString(),
                    color = Color(0xFFF2E9D8),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (0.145f * badge).sp,
                    lineHeight = (0.145f * badge).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PickCrest(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawPickCrest(accent)
    }
}

private fun DrawScope.drawPickCrest(accent: Color) {
    val keyline = Color(0xFF070B0A)
    val face = Color(0xFF0E1312)
    val rearFace = lerp(accent, face, 0.42f)

    fun card(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        degrees: Float,
        fill: Color,
    ) {
        val pivot = Offset(left + width / 2f, top + height / 2f)
        rotate(degrees = degrees, pivot = pivot) {
            drawRoundRect(
                color = keyline,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(width * 0.14f),
            )
            val inset = width * 0.055f
            drawRoundRect(
                color = fill,
                topLeft = Offset(left + inset, top + inset),
                size = Size(width - 2f * inset, height - 2f * inset),
                cornerRadius = CornerRadius(width * 0.10f),
            )
        }
    }

    card(
        left = size.width * 0.13f,
        top = size.height * 0.19f,
        width = size.width * 0.43f,
        height = size.height * 0.68f,
        degrees = -10f,
        fill = rearFace,
    )
    card(
        left = size.width * 0.44f,
        top = size.height * 0.19f,
        width = size.width * 0.43f,
        height = size.height * 0.68f,
        degrees = 10f,
        fill = rearFace,
    )

    val frontLeft = size.width * 0.25f
    val frontTop = size.height * 0.06f
    val frontWidth = size.width * 0.50f
    val frontHeight = size.height * 0.81f
    drawRoundRect(
        color = keyline,
        topLeft = Offset(frontLeft, frontTop),
        size = Size(frontWidth, frontHeight),
        cornerRadius = CornerRadius(frontWidth * 0.14f),
    )

    val rimInset = frontWidth * 0.055f
    drawRoundRect(
        color = accent,
        topLeft = Offset(frontLeft + rimInset, frontTop + rimInset),
        size = Size(frontWidth - 2f * rimInset, frontHeight - 2f * rimInset),
        cornerRadius = CornerRadius(frontWidth * 0.10f),
    )

    val faceInset = frontWidth * 0.105f
    drawRoundRect(
        color = face,
        topLeft = Offset(frontLeft + faceInset, frontTop + faceInset),
        size = Size(frontWidth - 2f * faceInset, frontHeight - 2f * faceInset),
        cornerRadius = CornerRadius(frontWidth * 0.065f),
    )
}

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
