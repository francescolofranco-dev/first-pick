package com.firstpick.overlay

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArenaMemoryReaderTest {
    @Test fun aFreshSupportedProcessRestoresTheGrantButtonAfterAnUnsupportedReading() {
        val directory = Files.createTempDirectory("firstpick-reader-recovery").toFile()
        try {
            val helper = directory.resolve("reader").apply {
                writeText("""
                    |#!/bin/sh
                    |first=1
                    |while IFS= read -r request; do
                    |  if [ "${'$'}first" = 1 ]; then
                    |    printf '%s\n' '{"status":"unsupported_build"}'
                    |    first=0
                    |  else
                    |    printf '%s\n' '{"status":"access_required"}'
                    |  fi
                    |done
                """.trimMargin())
                check(setExecutable(true))
            }
            ArenaMemoryReader(helper).use { reader ->
                assertEquals(MemoryUnavailableKind.UNSUPPORTED, assertIs<ArenaMemoryRead.Unavailable>(reader.read()).kind)
                assertEquals(LiveDeckAccessStatus.UNSUPPORTED, reader.access.value.status)
                assertEquals(MemoryUnavailableKind.ACCESS_REQUIRED, assertIs<ArenaMemoryRead.Unavailable>(reader.read()).kind)
                assertEquals(LiveDeckAccessStatus.ACCESS_REQUIRED, reader.access.value.status)
                assertTrue(reader.access.value.canGrant)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
