package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecognitionSettlerTest {

    private fun attempt(card: Int, distance: Double, calibration: PackGridCalibration? = null) = RecognitionAttempt(
        match = CardRecognizer.MatchResult(mapOf(0 to card), distance, distance),
        calibration = calibration,
    )

    @Test
    fun waitsForThreeFullFramesAndKeepsTheCleanestOne() {
        val settler = RecognitionSettler(required = 3)
        val detectedGeometry = PackGridCalibration(0.14f, 0.10f, 0.09f, 0.20f, 0.23f, 0.22f)

        assertNull(settler.observe(attempt(card = 9, distance = 300.0, calibration = detectedGeometry)))
        assertNull(settler.observe(attempt(card = 2, distance = 100.0)))
        val settled = settler.observe(attempt(card = 3, distance = 120.0))

        assertEquals(mapOf(0 to 2), settled?.match?.assignment)
        assertEquals(detectedGeometry, settled?.calibration)
        assertEquals(3, settler.fullRecognitions)
    }
}
