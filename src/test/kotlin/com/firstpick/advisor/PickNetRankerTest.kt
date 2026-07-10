package com.firstpick.advisor

import com.firstpick.cards.RankedCard
import com.firstpick.model.PickNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickNetRankerTest {

    /**
     * Three cards, hidden 2. W1 zeroed → hidden = gelu(0) = 0 regardless of
     * pool, so output = b3: fixed net preference Carrion > Bolt > Angel.
     */
    private fun net(): PickNet {
        val header = """{"v":1,"set":"TST","format":"PremierDraft","hidden":2,"cards":["Angel","Bolt","Carrion"]}"""
        val floats = FloatArray(2 * 3) + FloatArray(2) +          // W1, b1 (zero)
            FloatArray(2 * 2) + FloatArray(2) +                    // W2, b2 (zero)
            FloatArray(3 * 2) + floatArrayOf(1f, 2f, 3f)           // W3 (zero), b3
        val body = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { body.putFloat(it) }
        val file = kotlin.io.path.createTempFile("ranker", ".fpnet")
        java.nio.file.Files.write(file, header.toByteArray() + '\n'.code.toByte() + body.array())
        return PickNet.load(file)
    }

    private fun scored(name: String, value: Double) = ScoredCard(
        card = RankedCard(grpId = name.hashCode(), name = name, rating = null),
        value = value,
        z = 0.0,
        isBomb = false,
        reasons = listOf("On-color"),
        breakdown = ValueBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, finalScore = value),
        rawValue = value,
    )

    @Test
    fun `net order wins, values transplant rank-preservingly`() {
        // Heuristics prefer Angel (80) > Bolt (60) > Carrion (40); net prefers the reverse.
        val ranked = PickNetRanker.rerank(net(), listOf(scored("Angel", 80.0), scored("Bolt", 60.0), scored("Carrion", 40.0)), emptyList())
        assertNotNull(ranked)
        assertEquals(listOf("Carrion", "Bolt", "Angel"), ranked.map { it.card.name })
        // Value stays monotonic with rank: best displayed value on the net's top card.
        assertEquals(listOf(80.0, 60.0, 40.0), ranked.map { it.value })
        // Transplanted cards lose the breakdown tooltip; Bolt keeps its own value → keeps it.
        assertNull(ranked[0].breakdown)
        assertNotNull(ranked[1].breakdown)
        assertNull(ranked[2].breakdown)
        // The promoted top card is flagged.
        assertEquals(PickNetRanker.MODEL_PICK_REASON, ranked[0].reasons.first())
        assertTrue(ranked[1].reasons.none { it == PickNetRanker.MODEL_PICK_REASON })
    }

    @Test
    fun `agreeing top pick is not flagged`() {
        val ranked = PickNetRanker.rerank(net(), listOf(scored("Carrion", 80.0), scored("Bolt", 60.0)), emptyList())
        assertNotNull(ranked)
        assertEquals("Carrion", ranked[0].card.name)
        assertTrue(ranked[0].reasons.none { it == PickNetRanker.MODEL_PICK_REASON })
        assertNotNull(ranked[0].breakdown)
    }

    @Test
    fun `low coverage falls back to heuristics`() {
        val pack = listOf(scored("Angel", 80.0), scored("Mystery1", 70.0), scored("Mystery2", 60.0))
        assertNull(PickNetRanker.rerank(net(), pack, emptyList()))
    }

    @Test
    fun `unknown card in a known pack ranks last`() {
        // 4/5 known = exactly the 0.8 coverage floor (duplicates count as copies).
        val pack = listOf(
            scored("Angel", 80.0), scored("Bolt", 70.0), scored("Carrion", 60.0),
            scored("Angel", 55.0), scored("Mystery", 50.0),
        )
        val ranked = PickNetRanker.rerank(net(), pack, emptyList())
        assertNotNull(ranked)
        assertEquals("Mystery", ranked.last().card.name)
    }
}
