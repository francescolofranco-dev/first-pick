package com.firstpick.overlay

data class CardSlot(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

object PackLayout {
    const val COLS = 5

    private const val LEFT = 0.150
    private const val TOP = 0.210
    private const val CARD_W = 0.081
    private const val CARD_H = 0.201
    private const val COL_PITCH = 0.102
    private const val ROW_PITCH = 0.238

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
