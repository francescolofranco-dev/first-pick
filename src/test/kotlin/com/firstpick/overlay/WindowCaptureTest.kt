package com.firstpick.overlay

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WindowCaptureTest {
    @Test
    fun timedOutHelperIsTerminatedAndReaped() {
        val dir = Files.createTempDirectory("fp-window-capture")
        val pidFile = dir.resolve("pid")
        val helper = dir.resolve("hanging-helper").toFile()
        helper.writeText("#!/bin/sh\nprintf '%s' \"$$\" > '${pidFile}'\nexec sleep 30\n")
        helper.setExecutable(true)

        val capture = WindowCapture(
            helper = helper,
            captureTimeoutMs = 500,
            killGraceMs = 100,
        )

        assertNull(capture.capture())
        val pid = Files.readString(pidFile).toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "timed-out helper $pid must not survive")
    }
}
