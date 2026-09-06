package com.firstpick.overlay

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

object CardRecognizer {
    private const val COLOR_WEIGHT = 8.0
    private const val LAYOUT_WEIGHT = 2.0

    private data class Crop(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

    private val REFERENCE_ART = Crop(0.08, 0.92, 0.20, 0.52)
    private val CAPTURE_ART = Crop(0.05, 0.96, 0.20, 0.52)
    private val REFERENCE_LAYOUT = Crop(0.04, 0.96, 0.02, 0.98)
    private val CAPTURE_LAYOUT = Crop(0.01, 0.99, 0.01, 0.99)

    class Signature(
        val bits: BooleanArray,
        val color: FloatArray,
        val layoutColor: FloatArray,
    )

    data class MatchResult(
        val assignment: Map<Int, Int>,
        val totalDistance: Double,
        val worstDistance: Double,
    )

    fun ofCard(card: BufferedImage): Signature = signatureOfRegion(
        card, 0, 0, card.width, card.height,
        REFERENCE_ART, REFERENCE_LAYOUT,
    )

    fun ofCard(card: BufferedImage, isRoom: Boolean): Signature =
        ofCard(if (isRoom) arenaRoomReference(card) else card)

    fun ofRegion(frame: BufferedImage, rx: Int, ry: Int, rw: Int, rh: Int): Signature = signatureOfRegion(
        frame, rx, ry, rw, rh,
        CAPTURE_ART, CAPTURE_LAYOUT,
    )

    private fun signatureOfRegion(
        frame: BufferedImage,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        artCrop: Crop,
        layoutCrop: Crop,
    ): Signature {
        val art = crop(frame, rx, ry, rw, rh, artCrop)
        val layout = crop(frame, rx, ry, rw, rh, layoutCrop)

        val gH = scaleGray(art, 17, 16)
        val gV = scaleGray(art, 16, 17)
        val bits = BooleanArray(16 * 16 * 2)
        var k = 0
        for (y in 0 until 16) for (x in 0 until 16) bits[k++] = gH[y * 17 + x + 1] > gH[y * 17 + x]
        for (y in 0 until 16) for (x in 0 until 16) bits[k++] = gV[(y + 1) * 16 + x] > gV[y * 16 + x]
        return Signature(bits, scaleRgb(art, 6, 6), scaleRgb(layout, 12, 18))
    }

    fun distance(a: Signature, b: Signature): Double {
        var ham = 0
        for (i in a.bits.indices) if (a.bits[i] != b.bits[i]) ham++
        var c = 0.0
        for (i in a.color.indices) {
            val d = a.color[i] - b.color[i]
            c += d * d
        }
        var layout = 0.0
        for (i in a.layoutColor.indices) {
            val d = a.layoutColor[i] - b.layoutColor[i]
            layout += d * d
        }
        return ham + COLOR_WEIGHT * c + LAYOUT_WEIGHT * layout
    }

    private fun crop(frame: BufferedImage, rx: Int, ry: Int, rw: Int, rh: Int, crop: Crop): BufferedImage {
        val x = (rx + crop.x0 * rw).toInt().coerceIn(0, frame.width - 1)
        val y = (ry + crop.y0 * rh).toInt().coerceIn(0, frame.height - 1)
        val w = ((crop.x1 - crop.x0) * rw).toInt().coerceIn(1, frame.width - x)
        val h = ((crop.y1 - crop.y0) * rh).toInt().coerceIn(1, frame.height - y)
        return frame.getSubimage(x, y, w, h)
    }

    private fun arenaRoomReference(card: BufferedImage): BufferedImage {
        val landscape = BufferedImage(card.height, card.width, BufferedImage.TYPE_INT_RGB)
        val landscapeGraphics = landscape.createGraphics()
        try {
            val g = landscapeGraphics
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val rotation = AffineTransform().apply {
                translate(card.height.toDouble(), 0.0)
                rotate(Math.PI / 2)
            }
            g.drawImage(card, rotation, null)
        } finally {
            landscapeGraphics.dispose()
        }

        val out = BufferedImage(card.width, card.height, BufferedImage.TYPE_INT_RGB)
        val halfHeight = out.height / 2
        val sourceTop = (ROOM_Y0 * landscape.height).roundToInt()
        val sourceBottom = (ROOM_Y1 * landscape.height).roundToInt()
        val outGraphics = out.createGraphics()
        try {
            val g = outGraphics
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(
                landscape,
                0, 0, out.width, halfHeight,
                (ROOM_LEFT_X0 * landscape.width).roundToInt(), sourceTop,
                (ROOM_LEFT_X1 * landscape.width).roundToInt(), sourceBottom,
                null,
            )
            g.drawImage(
                landscape,
                0, halfHeight, out.width, out.height,
                (ROOM_RIGHT_X0 * landscape.width).roundToInt(), sourceTop,
                (ROOM_RIGHT_X1 * landscape.width).roundToInt(), sourceBottom,
                null,
            )
        } finally {
            outGraphics.dispose()
        }
        return out
    }

    fun match(frame: BufferedImage, rects: List<CardDetector.CardRect>, refs: List<Signature?>): Map<Int, Int> {
        return matchDetailed(frame, rects, refs).assignment
    }

    fun matchDetailed(frame: BufferedImage, rects: List<CardDetector.CardRect>, refs: List<Signature?>): MatchResult {
        val knownRefs = refs.mapIndexedNotNull { index, signature -> signature?.let { index to it } }
        if (rects.isEmpty() || knownRefs.isEmpty() || knownRefs.size > rects.size) {
            return MatchResult(emptyMap(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        }

        val rectSigs = rects.map { ofRegion(frame, it.x, it.y, it.w, it.h) }
        val costs = Array(knownRefs.size) { refPos ->
            DoubleArray(rects.size) { rectPos -> distance(knownRefs[refPos].second, rectSigs[rectPos]) }
        }
        val refToRect = minimumAssignment(costs)
        val assignment = HashMap<Int, Int>(knownRefs.size)
        var total = 0.0
        var worst = 0.0
        for (refPos in refToRect.indices) {
            val rectPos = refToRect[refPos]
            val d = costs[refPos][rectPos]
            assignment[rects[rectPos].index] = knownRefs[refPos].first
            total += d
            worst = maxOf(worst, d)
        }
        val unknownRefs = refs.indices.filter { refs[it] == null }
        val unusedRects = rects.filter { it.index !in assignment }
        if (unknownRefs.size == 1 && unusedRects.size == 1) {
            assignment[unusedRects.single().index] = unknownRefs.single()
        }
        return MatchResult(assignment, total, worst)
    }

    internal fun minimumAssignment(costs: Array<DoubleArray>): IntArray {
        val rows = costs.size
        val cols = costs.first().size
        val u = DoubleArray(rows + 1)
        val v = DoubleArray(cols + 1)
        val p = IntArray(cols + 1)
        val way = IntArray(cols + 1)

        for (i in 1..rows) {
            p[0] = i
            var j0 = 0
            val minV = DoubleArray(cols + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(cols + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..cols) {
                    if (used[j]) continue
                    val current = costs[i0 - 1][j - 1] - u[i0] - v[j]
                    if (current < minV[j]) {
                        minV[j] = current
                        way[j] = j0
                    }
                    if (minV[j] < delta) {
                        delta = minV[j]
                        j1 = j
                    }
                }
                for (j in 0..cols) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minV[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val out = IntArray(rows)
        for (j in 1..cols) if (p[j] != 0) out[p[j] - 1] = j - 1
        return out
    }

    private fun scaled(src: BufferedImage, tw: Int, th: Int): BufferedImage {
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        return out
    }

    private fun scaleGray(src: BufferedImage, tw: Int, th: Int): IntArray {
        val s = scaled(src, tw, th)
        val out = IntArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val p = s.getRGB(x, y)
            out[y * tw + x] = (299 * ((p ushr 16) and 0xFF) + 587 * ((p ushr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return out
    }

    private fun scaleRgb(src: BufferedImage, tw: Int, th: Int): FloatArray {
        val s = scaled(src, tw, th)
        val out = FloatArray(tw * th * 3)
        var i = 0
        for (y in 0 until th) for (x in 0 until tw) {
            val p = s.getRGB(x, y)
            out[i++] = ((p ushr 16) and 0xFF) / 255f
            out[i++] = ((p ushr 8) and 0xFF) / 255f
            out[i++] = (p and 0xFF) / 255f
        }
        return out
    }

    private const val ROOM_LEFT_X0 = 0.073
    private const val ROOM_LEFT_X1 = 0.511
    private const val ROOM_RIGHT_X0 = 0.527
    private const val ROOM_RIGHT_X1 = 0.965
    private const val ROOM_Y0 = 0.036
    private const val ROOM_Y1 = 0.545
}
