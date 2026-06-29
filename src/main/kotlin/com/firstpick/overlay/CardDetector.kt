package com.firstpick.overlay

import java.awt.image.BufferedImage

/**
 * Finds the draft-pack card grid in a captured MTG Arena frame by brightness projection.
 *
 * This replaces fixed-fraction guessing ([PackLayout]), which drifts across window sizes and
 * aspect ratios because Arena's title bar is a fixed height while the rest of the UI is
 * responsive — so the cards are not at constant fractions of the whole window. Detecting the
 * actual card rectangles from the pixels is immune to that.
 *
 * Columns: over the card band, the gutters between columns are the dark game board, so the
 * per-column mean brightness alternates bright (card) / dark (gutter) — a clean signal giving
 * up to five columns (fewer late in a pack). Rows: measured over the LEFTMOST detected column
 * only. That is the one column always present in every occupied row (Arena fills the grid
 * left-first, so even a partial last row has a card in column 0), which keeps a partial bottom
 * row from being diluted by empty columns. Over a single column the inter-row board gaps fall
 * near zero while a card's internal art/text stripe stays well above, so a low threshold keeps
 * each card whole yet still cuts cleanly at the row gaps.
 *
 * Every limit is a fraction of the frame, so detection is resolution-independent. Returned
 * coordinates are in capture pixels; divide by (captureWidth / windowPoints) for overlay points.
 * Returns null when no plausible grid is found (e.g. not on a draft screen).
 *
 * Tuned and validated against real ScreenCaptureKit frames (see CardDetectorTest fixtures).
 */
object CardDetector {

    /** One card's rectangle in capture-pixel coordinates (origin = frame top-left). */
    data class CardRect(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    data class Grid(
        val cols: List<IntRange>,
        val rows: List<IntRange>,
        val imageW: Int,
        val imageH: Int,
    ) {
        /** The first [count] card rectangles in pick order (row-major, left-to-right). */
        fun cards(count: Int): List<CardRect> {
            val out = ArrayList<CardRect>(count.coerceAtLeast(0))
            var i = 0
            for (r in rows) {
                for (c in cols) {
                    if (i >= count) return out
                    out.add(CardRect(i, c.first, r.first, c.last - c.first + 1, r.last - r.first + 1))
                    i++
                }
            }
            return out
        }
    }

    const val MAX_COLS = 5

    // Card band (skip the top menu bar and bottom chrome) and the right Deck/Sideboard panel.
    private const val BAND_TOP = 0.20
    private const val BAND_BOTTOM = 0.92
    private const val RIGHT_PANEL_CUT = 0.78
    // Column projection.
    private const val COL_SMOOTH = 0.008
    private const val COL_THRESH = 0.35f
    private const val COL_MIN_LEN = 0.04
    private const val COL_MAX_GAP = 0.01
    // Row projection (over the leftmost column).
    private const val ROW_SMOOTH = 0.01
    private const val ROW_THRESH = 0.20f
    private const val ROW_MIN_LEN = 0.10
    private const val ROW_MAX_GAP = 0.005

    fun detect(img: BufferedImage): Grid? {
        val w = img.width
        val h = img.height
        if (w < 200 || h < 200) return null
        val gray = toGray(img, w, h)

        // --- Columns: per-column mean over the card band, left region only. ---
        val by0 = (BAND_TOP * h).toInt()
        val by1 = (BAND_BOTTOM * h).toInt()
        val colProj = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            var y = by0
            while (y < by1) { s += gray[y * w + x]; y++ }
            colProj[x] = s / (by1 - by0)
        }
        // Smooth before normalizing so the threshold applies to the full-range signal.
        smoothInPlace(colProj, (COL_SMOOTH * w).toInt())
        normalize(colProj)
        // Zero the right-hand panel last so it can't be picked up as a column.
        val cut = (RIGHT_PANEL_CUT * w).toInt()
        for (x in cut until w) colProj[x] = 0f
        val cols = runsAbove(colProj, COL_THRESH, (COL_MIN_LEN * w).toInt(), (COL_MAX_GAP * w).toInt())
        if (cols.isEmpty() || cols.size > MAX_COLS) return null

        // --- Rows: mean over the leftmost column only. ---
        val c0 = cols.first()
        val cw = c0.last - c0.first + 1
        val rowProj = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            var x = c0.first
            while (x <= c0.last) { s += gray[y * w + x]; x++ }
            rowProj[y] = s / cw
        }
        smoothInPlace(rowProj, (ROW_SMOOTH * h).toInt())
        normalize(rowProj)
        val rows = runsAbove(rowProj, ROW_THRESH, (ROW_MIN_LEN * h).toInt(), (ROW_MAX_GAP * h).toInt())
        if (rows.isEmpty()) return null

        return Grid(cols, rows, w, h)
    }

    private fun toGray(img: BufferedImage, w: Int, h: Int): FloatArray {
        val g = FloatArray(w * h)
        if (img.raster.numBands == 1) {
            // Single-band (grayscale): read raw stored samples. Going through getRGB here would
            // apply the gray ColorSpace's gamma and skew the values we tuned thresholds against.
            val s = img.raster.getSamples(0, 0, w, h, 0, null as IntArray?)
            for (i in g.indices) g[i] = s[i].toFloat()
        } else {
            // Color (the live capture): sRGB components, same ITU-R 601 luma as PIL's 'L'.
            val px = img.getRGB(0, 0, w, h, null, 0, w)
            for (i in px.indices) {
                val p = px[i]
                val r = (p ushr 16) and 0xFF
                val gg = (p ushr 8) and 0xFF
                val b = p and 0xFF
                g[i] = 0.299f * r + 0.587f * gg + 0.114f * b
            }
        }
        return g
    }

    private fun normalize(a: FloatArray) {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in a) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        for (i in a.indices) a[i] = (a[i] - mn) / range
    }

    /** Centered moving average (window [i-k/2, i+k/2], clamped at the edges). */
    private fun smoothInPlace(a: FloatArray, k: Int) {
        if (k < 2) return
        val n = a.size
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + a[i]
        val half = k / 2
        for (i in 0 until n) {
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half + 1).coerceAtMost(n)
            a[i] = (prefix[hi] - prefix[lo]) / (hi - lo)
        }
    }

    /** Contiguous runs strictly above [thresh]; runs split by <= [maxGap] are merged, then
     *  runs shorter than [minLen] are dropped. Ranges are inclusive. */
    private fun runsAbove(p: FloatArray, thresh: Float, minLen: Int, maxGap: Int): List<IntRange> {
        val raw = ArrayList<IntRange>()
        var start = -1
        for (i in p.indices) {
            val above = p[i] > thresh
            if (above && start < 0) start = i
            else if (!above && start >= 0) { raw.add(start..i - 1); start = -1 }
        }
        if (start >= 0) raw.add(start..p.size - 1)

        val merged = ArrayList<IntRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first - (last.last + 1) <= maxGap) {
                merged[merged.size - 1] = last.first..r.last
            } else {
                merged.add(r)
            }
        }
        return merged.filter { it.last - it.first + 1 >= minLen }
    }
}
