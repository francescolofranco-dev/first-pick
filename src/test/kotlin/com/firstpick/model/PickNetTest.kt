package com.firstpick.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PickNetTest {

    /** Two cards, hidden size two, hand-written weights in export order. */
    private fun tinyNet(): PickNet {
        val header = """{"v":1,"set":"TST","format":"PremierDraft","hidden":2,"cards":["Alpha","Beta"]}"""
        // W1(2x2) b1(2) W2(2x2) b2(2) W3(2x2) b3(2) — identity chains, bias favors Alpha at the end.
        val floats = floatArrayOf(
            1f, 0f, 0f, 1f, 0f, 0f,       // W1 rows, b1
            1f, 0f, 0f, 1f, 0f, 0f,       // W2 rows, b2
            1f, 0f, 0f, 1f, 0.5f, -0.5f,  // W3 rows, b3
        )
        val body = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { body.putFloat(it) }
        val file = Files.createTempFile("picknet", ".fpnet")
        Files.write(file, header.toByteArray() + '\n'.code.toByte() + body.array())
        return PickNet.load(file)
    }

    @Test
    fun `loads header and ranks pack cards`() {
        val net = tinyNet()
        assertEquals("TST", net.set)
        assertEquals(listOf("Alpha", "Beta"), net.cards)
        assertTrue(net.knows("Alpha"))
        assertTrue(!net.knows("Gamma"))

        // Empty pool: only b3 differentiates → Alpha (+0.5) over Beta (−0.5).
        val ranked = net.score(emptyList(), listOf("Alpha", "Beta"))
        assertEquals("Alpha", ranked[0].first)
        assertTrue(ranked[0].second > ranked[1].second)
    }

    @Test
    fun `pool changes scores`() {
        val net = tinyNet()
        val empty = net.score(emptyList(), listOf("Beta"))[0].second
        val withPool = net.score(listOf("Beta", "Beta"), listOf("Beta"))[0].second
        // Positive identity chain: copies of Beta in the pool raise Beta's score.
        assertTrue(withPool > empty)
    }

    @Test
    fun `unknown pack card ranks last`() {
        val net = tinyNet()
        val ranked = net.score(emptyList(), listOf("Gamma", "Beta"))
        assertEquals("Beta", ranked[0].first)
        assertEquals(Float.NEGATIVE_INFINITY, ranked[1].second)
    }
}
