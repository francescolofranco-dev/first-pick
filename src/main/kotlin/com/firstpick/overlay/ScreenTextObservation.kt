package com.firstpick.overlay

/** A rectangle in top-left-origin coordinates, normalized to the observed image. */
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0

    internal fun isValid(): Boolean =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            x >= 0.0 && y >= 0.0 && width > 0.0 && height > 0.0 &&
            right <= 1.0 && bottom <= 1.0

    internal fun union(other: NormalizedRect): NormalizedRect {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val right = maxOf(right, other.right)
        val bottom = maxOf(bottom, other.bottom)
        return NormalizedRect(left, top, right - left, bottom - top)
    }
}

data class ScreenTextObservation(
    val text: String,
    val confidence: Double,
    val bounds: NormalizedRect,
)

/** Raw OCR for one image. Coordinates are independent of Retina scale and window size. */
data class ScreenTextFrame(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val observations: List<ScreenTextObservation>,
)
