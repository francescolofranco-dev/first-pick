package com.firstpick.log

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

open class LogWatcher(
    private val path: Path,
    private val pollInterval: Duration = 250.milliseconds,
) {
    open fun lines(fromStart: Boolean = true): Flow<String> = flow {
        var position = if (fromStart) 0L else sizeOrZero()
        var carry = ByteArray(0)

        while (true) {
            currentCoroutineContext().ensureActive()
            val size = sizeOrZero()
            if (size < position) {
                position = 0
                carry = ByteArray(0)
            }
            if (size > position) {
                val (newPos, emitted, newCarry) = readNewLines(position, size, carry)
                position = newPos
                carry = newCarry
                for (line in emitted) emit(line)
            }
            delay(pollInterval)
        }
    }

    private data class Read(val position: Long, val lines: List<String>, val carry: ByteArray)

    private fun readNewLines(from: Long, to: Long, carry: ByteArray): Read {
        val acc = ByteArrayOutputStream()
        acc.write(carry)
        var position = from
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            ch.position(from)
            val buf = ByteBuffer.allocate(CHUNK)
            while (position < to) {
                buf.clear()
                val n = ch.read(buf)
                if (n <= 0) break
                acc.write(buf.array(), 0, n)
                position += n
            }
        }
        val bytes = acc.toByteArray()
        val lastNl = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNl < 0) return Read(position, emptyList(), bytes)

        val complete = String(bytes, 0, lastNl + 1, Charsets.UTF_8)
        val remainder = bytes.copyOfRange(lastNl + 1, bytes.size)
        val lines = complete.split('\n')
            .map { it.removeSuffix("\r") }
            .filter { it.isNotEmpty() }
        return Read(position, lines, remainder)
    }

    private fun sizeOrZero(): Long = if (Files.exists(path)) Files.size(path) else 0L

    companion object {
        private const val CHUNK = 1 shl 20
    }
}
