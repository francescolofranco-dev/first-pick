package com.firstpick.log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class LogWatcherTest {

    @Test
    fun replaysAppendsAndHandlesRotation() = runBlocking {
        val file = Files.createTempFile("watcher", ".log")
        Files.writeString(file, "alpha\nbeta\n")

        val received = Collections.synchronizedList(mutableListOf<String>())
        val watcher = LogWatcher(file, pollInterval = 15.milliseconds)
        val job = launch(Dispatchers.IO) {
            watcher.lines(fromStart = true).collect { received.add(it) }
        }
        try {
            waitUntil { received.size >= 2 }
            assertEquals(listOf("alpha", "beta"), received.toList())

            Files.writeString(file, "gamma\n", StandardOpenOption.APPEND)
            waitUntil { received.contains("gamma") }

            Files.writeString(file, "delta\n")
            waitUntil { received.contains("delta") }
            assertTrue(received.contains("delta"))
        } finally {
            job.cancel()
            Files.deleteIfExists(file)
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) fail("condition not met within ${timeoutMs}ms")
            delay(15)
        }
    }
}
