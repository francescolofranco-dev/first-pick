package com.firstpick.overlay

/** A card's predicted rectangle in window-local points (origin = Arena window top-left). */
data class CardSlot(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Predicts where MTG Arena draws each draft-pack card, so the overlay can put a grade badge
 * on it. Arena lays the pack out as a fixed left-aligned grid of [COLS] columns that wraps
 * top-to-bottom (observed: a 13-card P1P1 renders 5 / 5 / 3). Everything is a fraction of the
 * Arena window (title bar included, matching WindowLocator), so it scales to any window size.
 *
 * The fractions below are a first estimate from a P1P1 screenshot — calibrate against an
 * overlay-over-draft capture (the visible slot outlines vs. the actual cards).
 */
object PackLayout {
    const val COLS = 5

    // Window-fraction geometry (calibration constants).
    private const val LEFT = 0.110     // left edge of column 0
    private const val TOP = 0.190      // top edge of row 0
    private const val CARD_W = 0.115   // card width
    private const val CARD_H = 0.200   // card height
    private const val COL_PITCH = 0.133
    private const val ROW_PITCH = 0.245

    /** Predicted card rectangles (window-local points) for a pack of [count] cards. */
    fun slots(windowW: Int, windowH: Int, count: Int): List<CardSlot> =
        (0 until count.coerceAtLeast(0)).map { i ->
            val col = i % COLS
            val row = i / COLS
            CardSlot(
                index = i,
                x = ((LEFT + col * COL_PITCH) * windowW).toInt(),
                y = ((TOP + row * ROW_PITCH) * windowH).toInt(),
                w = (CARD_W * windowW).toInt(),
                h = (CARD_H * windowH).toInt(),
            )
        }
}
