package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.roundToInt

object CardDetector {

    private const val TAG = "CardDetector"

    data class CardRect(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    data class Grid(
        val cols: List<IntRange>,
        val rows: List<IntRange>,
        val imageW: Int,
        val imageH: Int,
    ) {
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

    private const val BAND_TOP = 0.20
    private const val BAND_BOTTOM = 0.92
    private const val ROW_BAND_SPAN = 0.25
    private const val RIGHT_PANEL_CUT = 0.78
    private const val COL_SMOOTH = 0.008
    private const val COL_THRESH = 0.35f
    private const val COL_MIN_LEN = 0.04
    private const val COL_MAX_GAP = 0.01
    private const val ROW_SMOOTH = 0.01
    private const val ROW_THRESH = 0.20f
    private const val ROW_MIN_LEN = 0.10
    private const val ROW_MAX_GAP = 0.005
    private const val CARD_ASPECT = 0.714
    private const val DEFAULT_PITCH_RATIO = 1.1
    private const val MAX_ROW0_TOP = 0.35

    private sealed class DetectResult {
        data class Found(val grid: Grid) : DetectResult()
        object HoverMagnified : DetectResult()
        object Indeterminate : DetectResult()
    }

    fun detect(img: BufferedImage, expectedCount: Int = 0): Grid? =
        (detectInternal(img, expectedCount) as? DetectResult.Found)?.grid


    fun isHoverMagnified(img: BufferedImage, expectedCount: Int = 0): Boolean =
        detectInternal(img, expectedCount) is DetectResult.HoverMagnified

    private fun detectInternal(img: BufferedImage, expectedCount: Int): DetectResult {
        val w = img.width
        val h = img.height
        if (w < 200 || h < 200) return DetectResult.Indeterminate
        val gray = toGray(img, w, h)


        val expRows = if (expectedCount > 0) ceil(expectedCount.toDouble() / MAX_COLS).toInt() else 3
        val by0 = (BAND_TOP * h).toInt()
        val by1 = (minOf(BAND_BOTTOM, BAND_TOP + expRows * ROW_BAND_SPAN) * h).toInt()
        val colProj = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            var y = by0
            while (y < by1) { s += gray[y * w + x]; y++ }
            colProj[x] = s / (by1 - by0)
        }
        smoothInPlace(colProj, (COL_SMOOTH * w).toInt())
        normalize(colProj)
        val cut = (RIGHT_PANEL_CUT * w).toInt()
        for (x in cut until w) colProj[x] = 0f


        val rawCols = splitMergedRuns(runsAbove(colProj, COL_THRESH, (COL_MIN_LEN * w).toInt(), (COL_MAX_GAP * w).toInt()))
        Log.debug(TAG, "cols=${rawCols.map { "${it.first}..${it.last}" }} expected=${minOf(MAX_COLS, expectedCount)}")
        if (rawCols.isEmpty() || rawCols.size > MAX_COLS) return DetectResult.Indeterminate
        if (expectedCount > 0 && rawCols.size != minOf(MAX_COLS, expectedCount)) return DetectResult.Indeterminate


        val colW = rawCols.map { it.last - it.first + 1 }.sorted()[rawCols.size / 2]
        val cols = rawCols.map { c ->
            val center = (c.first + c.last) / 2
            val a = (center - colW / 2).coerceAtLeast(0)
            a..(a + colW - 1).coerceAtMost(w - 1)
        }


        val gridW = cols.sumOf { it.last - it.first + 1 }
        val rowProj = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            for (c in cols) {
                var x = c.first
                while (x <= c.last) { s += gray[y * w + x]; x++ }
            }
            rowProj[y] = s / gridW
        }
        smoothInPlace(rowProj, (ROW_SMOOTH * h).toInt())
        normalize(rowProj)
        val bands = runsAbove(rowProj, ROW_THRESH, (ROW_MIN_LEN * h).toInt(), (ROW_MAX_GAP * h).toInt())
        Log.debug(TAG, "bands=${bands.map { "${it.first}..${it.last}" }}")
        if (bands.isEmpty()) return DetectResult.Indeterminate

        val aspectH = (colW / CARD_ASPECT).toInt()


        val first = bands.first()
        val row0H = first.last - first.first + 1
        val cardH: Int
        val row0Top: Int
        if (row0H >= (0.9 * aspectH).toInt() && row0H <= (1.15 * aspectH).toInt()) {
            cardH = row0H
            row0Top = first.first
        } else {
            cardH = aspectH
            row0Top = (first.last - aspectH + 1).coerceAtLeast(0)
        }


        if (row0Top > (MAX_ROW0_TOP * h)) {
            Log.debug(TAG, "reject: row0Top $row0Top past ${(MAX_ROW0_TOP * h).toInt()} (hovered/magnified frame)")
            return DetectResult.HoverMagnified
        }
        val pitchCandidates = bands.map { it.last }.zipWithNext { a, b -> b - a }
            .filter { it in (0.9 * cardH).toInt()..(1.5 * cardH).toInt() }
        val pitch = if (pitchCandidates.isNotEmpty()) {
            pitchCandidates.sorted()[pitchCandidates.size / 2]
        } else {
            (cardH * DEFAULT_PITCH_RATIO).toInt()
        }

        val rowCount = when {
            expectedCount > 0 -> ceil(expectedCount.toDouble() / cols.size).toInt()
            else -> ((bands.last().first - row0Top).toDouble() / pitch).roundToInt() + 1
        }.coerceAtLeast(1)
        val rows = (0 until rowCount).mapNotNull { r ->
            val top = row0Top + r * pitch
            val bottom = (top + cardH - 1).coerceAtMost(h - 1)
            if (top < 0 || top > h - 1 || bottom <= top) null else top..bottom
        }
        if (rows.isEmpty()) return DetectResult.Indeterminate
        return DetectResult.Found(Grid(cols, rows, w, h))
    }

    private fun toGray(img: BufferedImage, w: Int, h: Int): FloatArray {
        val g = FloatArray(w * h)
        if (img.raster.numBands == 1) {
            val s = img.raster.getSamples(0, 0, w, h, 0, null as IntArray?)
            for (i in g.indices) g[i] = s[i].toFloat()
        } else {
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

    internal fun splitMergedRuns(runs: List<IntRange>): List<IntRange> {
        if (runs.size < 2) return runs
        val unit = runs.map { it.last - it.first + 1 }.sorted()[runs.size / 2]
        if (unit <= 0) return runs
        val out = ArrayList<IntRange>(runs.size)
        for (r in runs) {
            val len = r.last - r.first + 1
            val k = (len.toDouble() / unit).roundToInt().coerceAtLeast(1)
            if (k == 1) {
                out.add(r)
                continue
            }
            val step = len.toDouble() / k
            for (i in 0 until k) {
                val a = r.first + (i * step).toInt()
                val b = if (i == k - 1) r.last else r.first + ((i + 1) * step).toInt() - 1
                if (b >= a) out.add(a..b)
            }
        }
        return out
    }

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
