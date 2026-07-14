package com.firstpick.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckStrengthTest {

    @Test
    fun rankOrdinalMapsTiersAndDefaults() {
        assertEquals(0.0, DraftRank.ordinal("bronze"))
        assertEquals(5.0, DraftRank.ordinal("mythic"))
        assertEquals(2.0, DraftRank.ordinal("gold-3"))
        assertEquals(DraftRank.DEFAULT, DraftRank.ordinal(null))
        assertEquals(DraftRank.DEFAULT, DraftRank.ordinal("unranked"))
    }

    @Test
    fun structuralProjectionDropsGihFeatures() {
        val keptNames = DeckFeatures.KEEP_STRUCTURAL.map { DeckFeatures.NAMES[it] }
        assertTrue("deckZ" !in keptNames && "topZ" !in keptNames && "bombs" !in keptNames)
        assertTrue("rank" in keptNames && "removal" in keptNames && "intercept" in keptNames)
        val full = DoubleArray(DeckFeatures.DIM) { it.toDouble() }
        val proj = DeckFeatures.project(full, DeckFeatures.KEEP_STRUCTURAL)
        assertEquals(DeckFeatures.KEEP_STRUCTURAL.size, proj.size)
        assertEquals(full[DeckFeatures.KEEP_STRUCTURAL[1]], proj[1])
    }

    @Test
    fun trainerRecoversAMonotoneSignal() {

        val x = ArrayList<DoubleArray>()
        val y = ArrayList<Double>()
        for (i in 0 until 400) {
            val t = (i % 20) / 20.0
            val f = DoubleArray(DeckFeatures.DIM)
            f[0] = 1.0; f[1] = t; f[DeckFeatures.RANK_INDEX] = 2.0
            x += f
            y += (0.35 + 0.3 * t).coerceIn(0.0, 1.0)
        }
        val model = DeckStrengthTrainer.train(x, y.toDoubleArray(), DoubleArray(x.size) { 1.0 })
        val low = DoubleArray(DeckFeatures.DIM).also { it[0] = 1.0; it[1] = 0.0; it[DeckFeatures.RANK_INDEX] = 2.0 }
        val high = DoubleArray(DeckFeatures.DIM).also { it[0] = 1.0; it[1] = 1.0; it[DeckFeatures.RANK_INDEX] = 2.0 }
        assertTrue(model.predict(high) > model.predict(low) + 0.1, "prediction should rise with the driving feature")
        assertTrue(model.predict(low) in 0.0..1.0 && model.predict(high) in 0.0..1.0, "predictions stay in [0,1]")
    }
}
