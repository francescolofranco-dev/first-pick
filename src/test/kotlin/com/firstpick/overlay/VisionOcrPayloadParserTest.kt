package com.firstpick.overlay

import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisionOcrPayloadParserTest {
    @Test
    fun parsesVersionedTopLeftNormalizedObservations() {
        val frame = VisionOcrPayloadParser.parse(
            """
            {
              "schema": 1,
              "ok": true,
              "width": 2560,
              "height": 1496,
              "observations": [
                {"text":"  3x   Stock Up  ","confidence":0.94,"x":0.78,"y":0.41,"w":0.15,"h":0.025}
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(frame)
        assertEquals(2560, frame.pixelWidth)
        assertEquals(1496, frame.pixelHeight)
        assertEquals("3x Stock Up", frame.observations.single().text)
        assertEquals(NormalizedRect(0.78, 0.41, 0.15, 0.025), frame.observations.single().bounds)
    }

    @Test
    fun rejectsFailureWrongSchemaAndMalformedBounds() {
        assertNull(VisionOcrPayloadParser.parse("""{"schema":1,"ok":false,"width":1,"height":1}"""))
        assertNull(VisionOcrPayloadParser.parse("""{"schema":2,"ok":true,"width":1,"height":1}"""))
        assertNull(
            VisionOcrPayloadParser.parse(
                """{"schema":1,"ok":true,"width":100,"height":100,"observations":[{"text":"x","confidence":0.8,"x":0.9,"y":0.1,"w":0.2,"h":0.1}]}""",
            ),
        )
    }

    @Test
    fun rejectsInvalidConfidenceInsteadOfPartiallyTrustingAFrame() {
        assertNull(
            VisionOcrPayloadParser.parse(
                """{"schema":1,"ok":true,"width":100,"height":100,"observations":[{"text":"Stock Up","confidence":1.2,"x":0.1,"y":0.1,"w":0.2,"h":0.1}]}""",
            ),
        )
    }

    @Test
    fun wrapperAcceptsAValidatedHelperPayload() {
        val helper = Files.createTempFile("firstpick-fake-ocr", ".sh").toFile()
        helper.writeText(
            """#!/bin/sh
            printf '%s' '{"schema":1,"ok":true,"width":32,"height":24,"observations":[{"text":"Done","confidence":0.9,"x":0.8,"y":0.8,"w":0.1,"h":0.1}]}'
            """.trimIndent(),
        )
        helper.setExecutable(true)

        val frame = VisionOcr(helper = helper, timeoutMs = 2_000).recognize(
            BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB),
        )

        assertEquals("Done", frame?.observations?.single()?.text)
    }
}
