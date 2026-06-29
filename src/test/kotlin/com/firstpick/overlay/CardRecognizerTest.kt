package com.firstpick.overlay

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class CardRecognizerTest {

    /** A synthetic card: dark frame with a distinct colored gradient in its art window. */
    private fun card(seed: Int, w: Int = 300, h: Int = 420): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(30, 30, 30)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val ax = (0.08 * w).toInt(); val ay = (0.11 * h).toInt()
        val aw = (0.84 * w).toInt(); val ah = (0.33 * h).toInt()
        for (y in 0 until ah) for (x in 0 until aw) {
            val r = (seed * 53 + x) % 256
            val gg = (seed * 97 + y) % 256
            val b = (seed * 151 + x + y) % 256
            img.setRGB(ax + x, ay + y, Color(r, gg, b).rgb)
        }
        return img
    }

    @Test
    fun matchesEachRectToTheCardDrawnThere() {
        val n = 6
        val refs = (0 until n).map { CardRecognizer.ofCard(card(it + 1)) }

        // Draw the cards into a frame in a shuffled order, so a correct match must rely on the
        // art, not position. drawn[rectIndex] = which card was painted into that rect.
        val drawn = intArrayOf(3, 0, 5, 1, 4, 2)
        val cw = 300; val ch = 420; val cols = 3
        val frame = BufferedImage(cols * cw + 40, 2 * ch + 40, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        g.color = Color.BLACK; g.fillRect(0, 0, frame.width, frame.height)
        val rects = ArrayList<CardDetector.CardRect>()
        for (i in 0 until n) {
            val rx = 10 + (i % cols) * (cw + 10)
            val ry = 10 + (i / cols) * (ch + 10)
            g.drawImage(card(drawn[i] + 1), rx, ry, null)
            rects.add(CardDetector.CardRect(i, rx, ry, cw, ch))
        }
        g.dispose()

        val assignment = CardRecognizer.match(frame, rects, refs)
        for (i in 0 until n) {
            assertEquals(drawn[i], assignment[i], "rect $i should match the card drawn there")
        }
    }
}
