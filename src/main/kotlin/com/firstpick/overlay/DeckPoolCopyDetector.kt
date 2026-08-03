package com.firstpick.overlay

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry for Arena's supported vertical, compact deck-builder layout. */
internal object DeckPoolGeometry {
    private const val CARD_LEFT_FROM_TITLE = 0.009
    private const val CARD_TOP_FROM_TITLE = 0.021
    private const val CARD_WIDTH = 0.135
    private const val CARD_PIXEL_ASPECT = 0.714
    private const val REFERENCE_IMAGE_ASPECT = 2560.0 / 1496.0

    fun cardBounds(
        card: VisiblePoolCardTitleObservation,
        imageAspectRatio: Double = REFERENCE_IMAGE_ASPECT,
    ): NormalizedRect? {
        if (card.column !in 0..4 || card.row !in 0..1 || imageAspectRatio <= 0.0) return null
        val horizontalScale = min(1.0, REFERENCE_IMAGE_ASPECT / imageAspectRatio)
        val verticalScale = min(1.0, imageAspectRatio / REFERENCE_IMAGE_ASPECT)
        val cardWidth = CARD_WIDTH * horizontalScale
        val cardHeight = cardWidth * imageAspectRatio / CARD_PIXEL_ASPECT
        val bounds = NormalizedRect(
            x = card.titleBounds.x - CARD_LEFT_FROM_TITLE * horizontalScale,
            y = card.titleBounds.y - CARD_TOP_FROM_TITLE * verticalScale,
            width = cardWidth,
            height = cardHeight,
        )
        return bounds.takeIf(NormalizedRect::isValid)
    }
}

/**
 * Reads the centered diamond run above a visible pool card. Hollow outlined
 * diamonds are copies still outside the main deck; some transition frames can
 * briefly retain filled copies as well, which are deliberately not counted.
 * The returned value is therefore a sideboard/outside-deck count.
 */
internal object DeckPoolCopyDetector {
    private const val DIAMOND_ROW_CENTER_FROM_TITLE_X = 0.0585
    private const val DIAMOND_ROW_FROM_TITLE_Y = 0.0370
    private const val DIAMOND_PITCH = 0.0147
    private const val MAX_DIAMONDS = 4
    private const val SEARCH_HALF_WIDTH_IN_PITCHES = 2.65
    private const val SEARCH_HALF_HEIGHT_IN_PITCHES = 0.62
    private const val MIN_FOREGROUND_LUMINANCE = 100.0
    private const val MIN_COMPONENT_WIDTH_IN_PITCHES = 0.28
    private const val MAX_COMPONENT_WIDTH_IN_PITCHES = 0.95
    private const val MIN_COMPONENT_HEIGHT_IN_PITCHES = 0.28
    private const val MAX_COMPONENT_HEIGHT_IN_PITCHES = 0.95
    private const val MIN_COMPONENT_DENSITY = 0.07
    private const val MAX_COMPONENT_DENSITY = 0.78
    private const val MIN_COMPONENT_PIXELS_IN_PITCHES = 0.65
    private const val MIN_ASPECT = 0.48
    private const val MAX_ASPECT = 2.10
    private const val MAX_ROW_CENTER_ERROR_IN_PITCHES = 0.62
    private const val MAX_RUN_CENTER_ERROR_IN_PITCHES = 0.72
    private const val MIN_GAP_IN_PITCHES = 0.58
    private const val MAX_GAP_IN_PITCHES = 1.42
    private const val CENTER_SAMPLE_RADIUS_RATIO = 0.13
    private const val REFERENCE_IMAGE_ASPECT = 2560.0 / 1496.0

    fun count(image: BufferedImage, card: VisiblePoolCardTitleObservation): Int? {
        if (image.width <= 0 || image.height <= 0) return null
        if (card.column !in 0..4 || card.row !in 0..1) return null
        val imageAspect = image.width.toDouble() / image.height
        val horizontalScale = min(1.0, REFERENCE_IMAGE_ASPECT / imageAspect)
        val verticalScale = min(1.0, imageAspect / REFERENCE_IMAGE_ASPECT)
        val pitch = DIAMOND_PITCH * horizontalScale * image.width
        if (pitch < 4.0) return null
        val expectedCenterX = (
            (card.titleBounds.x + DIAMOND_ROW_CENTER_FROM_TITLE_X * horizontalScale) * image.width
            )
        val expectedCenterY = (
            (card.titleBounds.y - DIAMOND_ROW_FROM_TITLE_Y * verticalScale) * image.height
            )
        val region = PixelRegion.around(
            centerX = expectedCenterX,
            centerY = expectedCenterY,
            halfWidth = pitch * SEARCH_HALF_WIDTH_IN_PITCHES,
            halfHeight = pitch * SEARCH_HALF_HEIGHT_IN_PITCHES,
            imageWidth = image.width,
            imageHeight = image.height,
        ) ?: return null

        val components = connectedComponents(image, region)
            .filter { it.looksLikeDiamond(image, pitch, expectedCenterY) }
            .sortedBy(Component::centerX)
        if (components.size !in 1..MAX_DIAMONDS) return null
        if (abs(components.map(Component::centerX).average() - expectedCenterX) > pitch * MAX_RUN_CENTER_ERROR_IN_PITCHES) {
            return null
        }
        if (components.zipWithNext().any { (left, right) ->
                (right.centerX - left.centerX) !in pitch * MIN_GAP_IN_PITCHES..pitch * MAX_GAP_IN_PITCHES
            }
        ) {
            return null
        }

        return components.count { it.isHollow(image) }
    }

    private fun connectedComponents(image: BufferedImage, region: PixelRegion): List<Component> {
        val width = region.width
        val height = region.height
        val foreground = BooleanArray(width * height)
        for (localY in 0 until height) {
            for (localX in 0 until width) {
                foreground[localY * width + localX] =
                    luminance(image.getRGB(region.left + localX, region.top + localY)) >= MIN_FOREGROUND_LUMINANCE
            }
        }

        val visited = BooleanArray(foreground.size)
        val queue = IntArray(foreground.size)
        val output = ArrayList<Component>()
        for (start in foreground.indices) {
            if (!foreground[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var pixels = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            while (head < tail) {
                val current = queue[head++]
                val localX = current % width
                val localY = current / width
                pixels++
                minX = minOf(minX, localX)
                minY = minOf(minY, localY)
                maxX = maxOf(maxX, localX)
                maxY = maxOf(maxY, localY)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = localX + dx
                    val nextY = localY + dy
                    if (nextX !in 0 until width || nextY !in 0 until height) continue
                    val next = nextY * width + nextX
                    if (!foreground[next] || visited[next]) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            output += Component(
                left = region.left + minX,
                top = region.top + minY,
                right = region.left + maxX,
                bottom = region.top + maxY,
                pixels = pixels,
            )
        }
        return output
    }

    private fun Component.looksLikeDiamond(
        image: BufferedImage,
        pitch: Double,
        expectedCenterY: Double,
    ): Boolean {
        if (width !in pitch * MIN_COMPONENT_WIDTH_IN_PITCHES..pitch * MAX_COMPONENT_WIDTH_IN_PITCHES) return false
        if (height !in pitch * MIN_COMPONENT_HEIGHT_IN_PITCHES..pitch * MAX_COMPONENT_HEIGHT_IN_PITCHES) return false
        if (pixels < pitch * MIN_COMPONENT_PIXELS_IN_PITCHES) return false
        val aspect = width / height
        if (aspect !in MIN_ASPECT..MAX_ASPECT) return false
        if (density !in MIN_COMPONENT_DENSITY..MAX_COMPONENT_DENSITY) return false
        if (abs(centerY - expectedCenterY) > pitch * MAX_ROW_CENTER_ERROR_IN_PITCHES) return false

        // A diamond has little foreground in its bounding-box corners. This
        // rejects nearby text strokes and horizontal card chrome.
        val insetX = (width * 0.18).roundToInt().coerceAtLeast(1)
        val insetY = (height * 0.18).roundToInt().coerceAtLeast(1)
        val corners = listOf(
            left + insetX to top + insetY,
            right - insetX to top + insetY,
            left + insetX to bottom - insetY,
            right - insetX to bottom - insetY,
        )
        return corners.count { (x, y) -> luminance(image.getRGB(x, y)) >= MIN_FOREGROUND_LUMINANCE } <= 1
    }

    private fun Component.isHollow(image: BufferedImage): Boolean {
        val radius = (minOf(width, height) * CENTER_SAMPLE_RADIUS_RATIO).roundToInt().coerceAtLeast(1)
        var total = 0.0
        var samples = 0
        val sampleCenterX = centerX.roundToInt()
        val sampleCenterY = centerY.roundToInt()
        for (y in sampleCenterY - radius..sampleCenterY + radius) {
            for (x in sampleCenterX - radius..sampleCenterX + radius) {
                total += luminance(image.getRGB(x, y))
                samples++
            }
        }
        return total / samples < MIN_FOREGROUND_LUMINANCE
    }

    private fun luminance(rgb: Int): Double {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return red * 0.2126 + green * 0.7152 + blue * 0.0722
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixels: Int,
    ) {
        val width: Double get() = (right - left + 1).toDouble()
        val height: Double get() = (bottom - top + 1).toDouble()
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (top + bottom) / 2.0
        val density: Double get() = pixels / (width * height)
    }

    private data class PixelRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1

        companion object {
            fun around(
                centerX: Double,
                centerY: Double,
                halfWidth: Double,
                halfHeight: Double,
                imageWidth: Int,
                imageHeight: Int,
            ): PixelRegion? {
                val left = (centerX - halfWidth).roundToInt()
                val top = (centerY - halfHeight).roundToInt()
                val right = (centerX + halfWidth).roundToInt()
                val bottom = (centerY + halfHeight).roundToInt()
                if (left < 0 || top < 0 || right >= imageWidth || bottom >= imageHeight) return null
                return PixelRegion(left, top, right, bottom)
            }
        }
    }
}
