package com.firstpick.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

data class VisibleDeckRowObservation(
    val count: Int,
    val cardName: String,
    /** Estimated clickable Arena row, not merely the OCR glyph bounds. */
    val bounds: NormalizedRect,
    val textBounds: NormalizedRect,
    val confidence: Double,
)

data class VisiblePoolCardTitleObservation(
    val cardName: String,
    val titleBounds: NormalizedRect,
    val column: Int,
    val row: Int,
    val confidence: Double,
)

data class DeckBuilderScreenObservation(
    val deckSize: Int,
    val minimumDeckSize: Int,
    /** Conservative, visible count/name pairs. A missing card is unknown, not absent from the deck. */
    val deckRows: List<VisibleDeckRowObservation>,
    /** Conservative title reads for cards currently visible in the pool page/filter. */
    val poolCardTitles: List<VisiblePoolCardTitleObservation>,
    val deckPanelBounds: NormalizedRect,
    val confidence: Double,
)

data class DeckBuilderShellObservation(
    val deckSize: Int,
    val minimumDeckSize: Int,
    val confidence: Double,
)

/**
 * Interprets OCR from Arena's vertical deck-builder layout.
 *
 * The full interpreter deliberately returns null when the deck-size header, a
 * deck-builder anchor, or an aligned run of card rows is missing. Callers can
 * separately use [detectShell] to distinguish an obscured/animating builder
 * from a frame that has actually left the builder.
 */
object DeckBuilderScreenInterpreter {
    /**
     * Detects the stable header + Done-button shell without requiring any card
     * rows. Hover previews can obscure the rows while leaving these anchors in
     * place, which is sufficient to retain the last trusted guidance.
     */
    fun detectShell(frame: ScreenTextFrame): DeckBuilderShellObservation? {
        if (!frame.isValid()) return null
        val header = findHeader(frame.observations) ?: return null
        val done = findDone(frame.observations) ?: return null
        val confidence = (header.source.confidence * 0.65 + done.confidence * 0.35).coerceIn(0.0, 1.0)
        if (confidence < MIN_SHELL_CONFIDENCE) return null
        return DeckBuilderShellObservation(
            deckSize = header.current,
            minimumDeckSize = header.minimum,
            confidence = confidence,
        )
    }

    fun interpret(frame: ScreenTextFrame): DeckBuilderScreenObservation? {
        if (!frame.isValid()) return null
        val observations = frame.observations

        val header = findHeader(observations) ?: return null

        val sideboard = observations.filter {
            it.text.contains("sideboard", ignoreCase = true) &&
                it.bounds.centerX >= ANCHOR_MIN_X && it.bounds.centerY < header.source.bounds.centerY
        }.maxByOrNull(ScreenTextObservation::confidence)
        val done = findDone(observations)
        val anchor = listOfNotNull(sideboard, done).maxByOrNull(ScreenTextObservation::confidence) ?: return null

        val occlusionTop = done?.let {
            (it.bounds.y - maxOf(DONE_OCCLUSION_MARGIN, it.bounds.height * 1.5)).coerceAtLeast(0.0)
        } ?: 1.0
        val rawRows = observations.mapNotNull { parseRowPrefix(it, observations) }
            .filter {
                it.prefix.bounds.centerX >= ROW_MIN_X &&
                    it.prefix.bounds.centerY > header.source.bounds.bottom &&
                    it.prefix.bounds.centerY < occlusionTop
            }
            .sortedBy { it.prefix.bounds.centerY }
        if (rawRows.size < MIN_ROWS) return null

        val alignedX = rawRows.map { it.prefix.bounds.x }.median()
        val rows = rawRows.filter { abs(it.prefix.bounds.x - alignedX) <= ROW_X_TOLERANCE }
            .deduplicateByY()
        if (rows.size < MIN_ROWS) return null
        if (rows.last().prefix.bounds.centerY - rows.first().prefix.bounds.centerY < MIN_ROW_SPREAD) return null

        val centers = rows.map { it.prefix.bounds.centerY }
        val gaps = centers.zipWithNext { a, b -> b - a }.filter { it > MIN_DISTINCT_ROW_GAP }
        if (gaps.isEmpty()) return null
        val pitch = gaps.filter { it <= MAX_REASONABLE_ROW_GAP }.ifEmpty { gaps }.median()
        if (pitch !in MIN_ROW_PITCH..MAX_ROW_PITCH) return null

        val panelLeft = (alignedX - PANEL_LEFT_PADDING).coerceIn(PANEL_MIN_X, PANEL_MAX_X)
        val observedRight = rows.maxOf { it.textBounds.right }
        val panelRight = maxOf(MIN_PANEL_RIGHT, observedRight + PANEL_RIGHT_PADDING, header.source.bounds.right)
            .coerceAtMost(MAX_PANEL_RIGHT)
        if (panelRight - panelLeft < MIN_PANEL_WIDTH) return null

        val textHeight = rows.map { it.textBounds.height }.median()
        val rowHeight = maxOf(MIN_ROW_HEIGHT, textHeight * TEXT_TO_ROW_HEIGHT)
            .coerceAtMost(minOf(MAX_ROW_HEIGHT, pitch * MAX_PITCH_FILL))
        val visibleRows = rows.map { row ->
            val centerY = row.prefix.bounds.centerY
            val top = (centerY - rowHeight / 2.0).coerceIn(0.0, 1.0 - rowHeight)
            VisibleDeckRowObservation(
                count = row.count,
                cardName = row.name,
                bounds = NormalizedRect(panelLeft, top, panelRight - panelLeft, rowHeight),
                textBounds = row.textBounds,
                confidence = row.confidence,
            )
        }

        val xDeviation = rows.map { abs(it.prefix.bounds.x - alignedX) }.average()
        val geometryConfidence = (1.0 - xDeviation / ROW_X_TOLERANCE).coerceIn(0.0, 1.0)
        val screenConfidence = (
            header.source.confidence * 0.30 +
                anchor.confidence * 0.15 +
                rows.map { it.confidence }.average() * 0.40 +
                geometryConfidence * 0.15
            ).coerceIn(0.0, 1.0)
        if (screenConfidence < MIN_SCREEN_CONFIDENCE) return null

        val panelTop = minOf(header.source.bounds.y, sideboard?.bounds?.y ?: header.source.bounds.y)
        val panelBottom = maxOf(
            visibleRows.maxOf { it.bounds.bottom },
            done?.bounds?.bottom ?: visibleRows.maxOf { it.bounds.bottom },
        ).coerceAtMost(1.0)
        val panelBounds = NormalizedRect(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop)

        return DeckBuilderScreenObservation(
            deckSize = header.current,
            minimumDeckSize = header.minimum,
            deckRows = visibleRows,
            poolCardTitles = findPoolTitles(observations, panelLeft),
            deckPanelBounds = panelBounds,
            confidence = screenConfidence,
        )
    }

    private data class Header(
        val current: Int,
        val minimum: Int,
        val source: ScreenTextObservation,
    )

    private data class ParsedPrefix(
        val count: Int,
        val name: String,
        val prefix: ScreenTextObservation,
        val textBounds: NormalizedRect,
        val confidence: Double,
    )

    private fun findHeader(observations: List<ScreenTextObservation>): Header? =
        observations.mapNotNull(::parseHeader)
            .filter { it.source.bounds.centerX >= HEADER_MIN_X && it.source.bounds.centerY <= HEADER_MAX_Y }
            .maxByOrNull { it.source.confidence }

    private fun findDone(observations: List<ScreenTextObservation>): ScreenTextObservation? =
        observations.filter {
            it.text.trim().equals("done", ignoreCase = true) &&
                it.bounds.centerX >= ANCHOR_MIN_X && it.bounds.centerY >= DONE_MIN_Y
        }.maxByOrNull(ScreenTextObservation::confidence)

    private fun parseHeader(observation: ScreenTextObservation): Header? {
        val match = DECK_SIZE.find(observation.text) ?: return null
        val current = match.groupValues[1].toIntOrNull() ?: return null
        val minimum = match.groupValues[2].toIntOrNull() ?: return null
        if (current !in 0..MAX_DECK_SIZE || minimum !in 1..MAX_DECK_SIZE) return null
        return Header(current, minimum, observation)
    }

    private fun parseRowPrefix(
        prefix: ScreenTextObservation,
        observations: List<ScreenTextObservation>,
    ): ParsedPrefix? {
        val match = ROW_PREFIX.matchEntire(prefix.text) ?: return null
        val countToken = match.groupValues[1]
        val count = if (countToken.equals("I", true) || countToken == "l") 1 else countToken.toIntOrNull()
        if (count == null || count !in 1..MAX_COPIES) return null

        val initialName = match.groupValues[2].trim()
        val extra = observations.asSequence()
            .filter { it !== prefix }
            .filter { sameTextLine(prefix.bounds, it.bounds) }
            .filter { it.bounds.x >= prefix.bounds.right - TOKEN_JOIN_SLOP }
            .filter { it.bounds.x < ROW_NAME_RIGHT_LIMIT }
            .filterNot { ROW_PREFIX.matches(it.text) || isManaOnly(it.text) }
            .sortedBy { it.bounds.x }
            .toList()
        val tokens = buildList {
            if (initialName.isNotEmpty()) add(initialName)
            for (token in extra) {
                if (initialName.isNotEmpty() && token.bounds.x < prefix.bounds.right + TOKEN_JOIN_SLOP) continue
                add(token.text)
            }
        }
        val name = cleanCardName(tokens.joinToString(" ")) ?: return null
        val used = listOf(prefix) + extra
        val textBounds = used.fold(prefix.bounds) { bounds, observation -> bounds.union(observation.bounds) }
        val ocrConfidence = used.map(ScreenTextObservation::confidence).average()
        val countPenalty = if (countToken.all(Char::isDigit)) 1.0 else AMBIGUOUS_ONE_PENALTY
        return ParsedPrefix(count, name, prefix, textBounds, ocrConfidence * countPenalty)
    }

    private fun findPoolTitles(
        observations: List<ScreenTextObservation>,
        panelLeft: Double,
    ): List<VisiblePoolCardTitleObservation> {
        data class Candidate(
            val observation: ScreenTextObservation,
            val column: Int,
            val row: Int,
        )

        val candidates = observations.mapNotNull { observation ->
            if (observation.confidence < MIN_POOL_TITLE_CONFIDENCE) return@mapNotNull null
            if (observation.bounds.right >= panelLeft - POOL_PANEL_GAP) return@mapNotNull null
            if (observation.bounds.height !in MIN_POOL_TEXT_HEIGHT..MAX_POOL_TEXT_HEIGHT) return@mapNotNull null
            val row = POOL_TITLE_BANDS.indexOfFirst { observation.bounds.centerY in it }
            if (row < 0) return@mapNotNull null
            val column = ((observation.bounds.x - POOL_FIRST_TITLE_X) / POOL_COLUMN_PITCH).roundToInt()
            if (column !in 0 until POOL_COLUMNS) return@mapNotNull null
            val expectedX = POOL_FIRST_TITLE_X + column * POOL_COLUMN_PITCH
            if (abs(observation.bounds.x - expectedX) > POOL_TITLE_X_TOLERANCE) return@mapNotNull null
            if (cleanPoolCardName(observation.text) == null || isUiText(observation.text)) return@mapNotNull null
            Candidate(observation, column, row)
        }

        return candidates.groupBy { it.row to it.column }.mapNotNull { (slot, inSlot) ->
            val line = inSlot.sortedBy { it.observation.bounds.x }
            val name = cleanPoolCardName(line.joinToString(" ") { it.observation.text }) ?: return@mapNotNull null
            val bounds = line.drop(1).fold(line.first().observation.bounds) { acc, candidate ->
                acc.union(candidate.observation.bounds)
            }
            VisiblePoolCardTitleObservation(
                cardName = name,
                titleBounds = bounds,
                column = slot.second,
                row = slot.first,
                confidence = line.map { it.observation.confidence }.average(),
            )
        }.sortedWith(compareBy(VisiblePoolCardTitleObservation::row, VisiblePoolCardTitleObservation::column))
    }

    private fun List<ParsedPrefix>.deduplicateByY(): List<ParsedPrefix> {
        val output = ArrayList<ParsedPrefix>(size)
        for (row in this) {
            val previous = output.lastOrNull()
            if (previous != null && abs(previous.prefix.bounds.centerY - row.prefix.bounds.centerY) <= SAME_ROW_Y_TOLERANCE) {
                if (row.confidence > previous.confidence) output[output.lastIndex] = row
            } else {
                output += row
            }
        }
        return output
    }

    private fun ScreenTextFrame.isValid(): Boolean =
        pixelWidth > 0 && pixelHeight > 0 && observations.size <= MAX_FRAME_OBSERVATIONS &&
            observations.all {
                it.text.isNotBlank() && it.confidence.isFinite() && it.confidence in 0.0..1.0 && it.bounds.isValid()
            }

    private fun sameTextLine(a: NormalizedRect, b: NormalizedRect): Boolean {
        val tolerance = maxOf(MIN_LINE_TOLERANCE, maxOf(a.height, b.height) * LINE_HEIGHT_TOLERANCE)
        return abs(a.centerY - b.centerY) <= tolerance
    }

    private fun cleanCardName(raw: String): String? {
        val cleaned = raw.replace(WHITESPACE, " ").trim().trim('|', '-', ':', ' ')
        if (cleaned.length !in MIN_CARD_NAME_LENGTH..MAX_CARD_NAME_LENGTH) return null
        if (cleaned.count(Char::isLetter) < MIN_CARD_NAME_LETTERS) return null
        if (ROW_PREFIX.matches(cleaned) || isUiText(cleaned)) return null
        return cleaned
    }

    private fun cleanPoolCardName(raw: String): String? =
        cleanCardName(raw.replace(POOL_MANA_SUFFIX, ""))

    private fun isManaOnly(text: String): Boolean = MANA_ONLY.matches(text.trim())

    private fun isUiText(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized == "done" || normalized == "sideboard" || normalized == "search" || normalized == "cards" ||
            normalized == "craft" || " cards" in normalized || DECK_SIZE.containsMatchIn(normalized)
    }

    private fun List<Double>.median(): Double {
        val sorted = sorted()
        if (sorted.isEmpty()) return Double.NaN
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private val DECK_SIZE = Regex("""(?i)(\d{1,3})\s*/\s*(\d{1,3})\s*cards?\b""")
    private val ROW_PREFIX = Regex("""^\s*([1-9]\d?|[Il])\s*[xX×]\s*(.*?)\s*$""")
    private val MANA_ONLY = Regex("""(?i)^[0-9WUBRGCX/{}()]+$""")
    private val POOL_MANA_SUFFIX = Regex("""(?i)\s+[0-9]+[0-9WUBRGCOX/{}()\[\]<]*\s*$""")
    private val WHITESPACE = Regex("""\s+""")

    private const val MAX_FRAME_OBSERVATIONS = 2_000
    private const val MAX_DECK_SIZE = 250
    private const val MAX_COPIES = 99
    private const val MIN_ROWS = 2
    private const val MIN_SHELL_CONFIDENCE = 0.55
    private const val MIN_SCREEN_CONFIDENCE = 0.55
    private const val HEADER_MIN_X = 0.70
    private const val HEADER_MAX_Y = 0.42
    private const val ANCHOR_MIN_X = 0.70
    private const val DONE_MIN_Y = 0.70
    private const val DONE_OCCLUSION_MARGIN = 0.045
    private const val ROW_MIN_X = 0.68
    private const val ROW_NAME_RIGHT_LIMIT = 0.965
    private const val ROW_X_TOLERANCE = 0.035
    private const val SAME_ROW_Y_TOLERANCE = 0.008
    private const val MIN_ROW_SPREAD = 0.025
    private const val MIN_DISTINCT_ROW_GAP = 0.009
    private const val MIN_REASONABLE_ROW_GAP = 0.014
    private const val MAX_REASONABLE_ROW_GAP = 0.09
    private const val MIN_ROW_PITCH = 0.014
    private const val MAX_ROW_PITCH = 0.12
    // The count glyph sits only ~7 px inside Arena's row capsule at 2554 px.
    // The old value reached past the capsule to the deck-panel divider.
    private const val PANEL_LEFT_PADDING = 0.003
    private const val PANEL_RIGHT_PADDING = 0.015
    private const val PANEL_MIN_X = 0.68
    private const val PANEL_MAX_X = 0.90
    private const val MIN_PANEL_RIGHT = 0.992
    private const val MAX_PANEL_RIGHT = 0.998
    private const val MIN_PANEL_WIDTH = 0.10
    private const val MIN_ROW_HEIGHT = 0.022
    private const val MAX_ROW_HEIGHT = 0.060
    private const val TEXT_TO_ROW_HEIGHT = 1.8
    // Leave the visual gap between adjacent Arena rows outside the outline.
    private const val MAX_PITCH_FILL = 0.86
    private const val TOKEN_JOIN_SLOP = 0.004
    private const val MIN_LINE_TOLERANCE = 0.006
    private const val LINE_HEIGHT_TOLERANCE = 0.65
    private const val AMBIGUOUS_ONE_PENALTY = 0.88
    private const val MIN_CARD_NAME_LENGTH = 2
    private const val MAX_CARD_NAME_LENGTH = 120
    private const val MIN_CARD_NAME_LETTERS = 2

    private const val POOL_COLUMNS = 5
    private const val POOL_FIRST_TITLE_X = 0.025
    private const val POOL_COLUMN_PITCH = 0.153
    private const val POOL_TITLE_X_TOLERANCE = 0.045
    private const val POOL_PANEL_GAP = 0.012
    private const val MIN_POOL_TITLE_CONFIDENCE = 0.45
    private const val MIN_POOL_TEXT_HEIGHT = 0.006
    private const val MAX_POOL_TEXT_HEIGHT = 0.040
    private val POOL_TITLE_BANDS = listOf(0.245..0.325, 0.615..0.690)
}
