package com.firstpick.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt


class PickNet private constructor(
    val set: String,
    val format: String,
    val cards: List<String>,
    private val hidden: Int,
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray,
    private val w3: FloatArray,
    private val b3: FloatArray,
) {


    private val index: Map<String, Int> = buildMap {
        for ((i, c) in cards.withIndex()) {
            val front = c.substringBefore(" // ")
            if (front != c) putIfAbsent(front, i)
        }
        for ((i, c) in cards.withIndex()) put(c, i)
    }

    fun knows(card: String): Boolean = card in index


    fun score(pool: Iterable<String>, pack: Collection<String>): List<Pair<String, Float>> {
        val n = cards.size
        val x = FloatArray(n)
        for (name in pool) index[name]?.let { x[it]++ }

        val h1 = FloatArray(hidden)
        forward(w1, b1, x, n, h1, gelu = true)
        val h2 = FloatArray(hidden)
        forward(w2, b2, h1, hidden, h2, gelu = true)
        val out = FloatArray(n)
        forward(w3, b3, h2, hidden, out, gelu = false)

        return pack.distinct()
            .map { name -> name to (index[name]?.let { out[it] } ?: Float.NEGATIVE_INFINITY) }
            .sortedByDescending { it.second }
    }

    private fun forward(w: FloatArray, b: FloatArray, x: FloatArray, xLen: Int, dst: FloatArray, gelu: Boolean) {
        for (row in dst.indices) {
            var s = b[row]
            val off = row * xLen
            for (j in 0 until xLen) s += w[off + j] * x[j]
            dst[row] = if (gelu) gelu(s) else s
        }
    }

    companion object {
        @Serializable
        private data class Header(
            val v: Int,
            val set: String,
            val format: String,
            val hidden: Int,
            val valTop1: Double = 0.0,
            val cards: List<String>,
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun load(path: Path): PickNet = parse(Files.readAllBytes(path), path.toString())

        fun parse(bytes: ByteArray, source: String): PickNet {
            val nl = bytes.indexOf('\n'.code.toByte())
            require(nl > 0) { "not a .fpnet file: $source" }
            val header = json.decodeFromString<Header>(String(bytes, 0, nl, Charsets.UTF_8))
            require(header.v == 1) { "unsupported .fpnet version ${header.v}" }
            val n = header.cards.size
            val h = header.hidden
            val buf = ByteBuffer.wrap(bytes, nl + 1, bytes.size - nl - 1).order(ByteOrder.LITTLE_ENDIAN)
            fun floats(count: Int) = FloatArray(count).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + count * 4) }
            val net = PickNet(
                header.set, header.format, header.cards, h,
                w1 = floats(h * n), b1 = floats(h),
                w2 = floats(h * h), b2 = floats(h),
                w3 = floats(n * h), b3 = floats(n),
            )
            require(!buf.hasRemaining()) { "trailing bytes in $source — dims mismatch" }
            return net
        }


        private fun gelu(x: Float): Float {
            val v = x.toDouble()
            return (v * 0.5 * (1.0 + erf(v / SQRT2))).toFloat()
        }

        private val SQRT2 = sqrt(2.0)


        private fun erf(x: Double): Double {
            val t = 1.0 / (1.0 + 0.3275911 * abs(x))
            val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
            return if (x >= 0) y else -y
        }
    }
}
