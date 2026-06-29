package com.firstpick.overlay

import java.awt.RenderingHints
import java.awt.image.BufferedImage

object CardRecognizer {
    private const val AX0 = 0.08
    private const val AX1 = 0.92
    private const val AY0 = 0.11
    private const val AY1 = 0.44
    private const val COLOR_WEIGHT = 8.0

    class Signature(val bits: BooleanArray, val color: FloatArray)

    fun ofCard(card: BufferedImage): Signature = ofRegion(card, 0, 0, card.width, card.height)

    fun ofRegion(frame: BufferedImage, rx: Int, ry: Int, rw: Int, rh: Int): Signature {
        val ax = (rx + AX0 * rw).toInt().coerceIn(0, frame.width - 1)
        val ay = (ry + AY0 * rh).toInt().coerceIn(0, frame.height - 1)
        val aw = ((AX1 - AX0) * rw).toInt().coerceIn(1, frame.width - ax)
        val ah = ((AY1 - AY0) * rh).toInt().coerceIn(1, frame.height - ay)
        val art = frame.getSubimage(ax, ay, aw, ah)

        val gH = scaleGray(art, 17, 16)
        val gV = scaleGray(art, 16, 17)
        val bits = BooleanArray(16 * 16 * 2)
        var k = 0
        for (y in 0 until 16) for (x in 0 until 16) bits[k++] = gH[y * 17 + x + 1] > gH[y * 17 + x]
        for (y in 0 until 16) for (x in 0 until 16) bits[k++] = gV[(y + 1) * 16 + x] > gV[y * 16 + x]
        return Signature(bits, scaleRgb(art, 6, 6))
    }

    fun distance(a: Signature, b: Signature): Double {
        var ham = 0
        for (i in a.bits.indices) if (a.bits[i] != b.bits[i]) ham++
        var c = 0.0
        for (i in a.color.indices) {
            val d = a.color[i] - b.color[i]
            c += d * d
        }
        return ham + COLOR_WEIGHT * c
    }

    fun match(frame: BufferedImage, rects: List<CardDetector.CardRect>, refs: List<Signature?>): Map<Int, Int> {
        if (rects.isEmpty() || refs.all { it == null }) return emptyMap()
        val rectSigs = rects.associate { it.index to ofRegion(frame, it.x, it.y, it.w, it.h) }
        data class Pair3(val rect: Int, val ref: Int, val d: Double)
        val pairs = ArrayList<Pair3>(rectSigs.size * refs.size)
        for ((ri, rs) in rectSigs) for (ci in refs.indices) refs[ci]?.let { pairs.add(Pair3(ri, ci, distance(rs, it))) }
        pairs.sortBy { it.d }
        val usedRect = HashSet<Int>()
        val usedRef = HashSet<Int>()
        val out = HashMap<Int, Int>()
        for (p in pairs) {
            if (p.rect in usedRect || p.ref in usedRef) continue
            out[p.rect] = p.ref
            usedRect.add(p.rect)
            usedRef.add(p.ref)
        }
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
}
