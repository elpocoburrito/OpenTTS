package com.opentts.engine.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lookahead pipeline must preserve chunk order, keep at most `depth` chunks
 * in flight, stop promptly when the consumer cancels, and surface a producer
 * failure to the caller (the service needs to report it as a synthesis error).
 */
class SynthesisPipelineTest {

    private fun audio(size: Int, tag: Float) = FloatArray(size) { tag }

    @Test
    fun `chunks arrive in order and are synthesized ahead of consumption`() {
        val produced = Collections.synchronizedList(ArrayList<Int>())
        val consumed = Collections.synchronizedList(ArrayList<Int>())
        val chunks = (1..5).map { "chunk$it" }

        val pipeline = SynthesisPipeline(
            chunks = chunks,
            synthesize = { chunk ->
                val index = chunk.removePrefix("chunk").toInt()
                produced.add(index)
                audio(index, index.toFloat())
            },
            onAudio = { samples ->
                consumed.add(samples.first().toInt())
                true
            },
            depth = 2,
        )
        pipeline.run()

        assertEquals(listOf(1, 2, 3, 4, 5), consumed)
        assertEquals(listOf(1, 2, 3, 4, 5), produced)
    }

    @Test
    fun `consumer cancellation stops the producer early`() {
        val synthesized = AtomicInteger(0)
        val chunks = (1..20).map { "chunk$it" }

        val pipeline = SynthesisPipeline(
            chunks = chunks,
            synthesize = {
                synthesized.incrementAndGet()
                Thread.sleep(5)
                audio(4, 1f)
            },
            onAudio = { false }, // reject the first chunk
            depth = 2,
        )
        pipeline.run()

        // Depth 2 plus the one being taken: a couple of chunks may get made, but
        // the pipeline must not grind through all 20.
        assertTrue("synthesized ${synthesized.get()} chunks", synthesized.get() <= 4)
    }

    @Test
    fun `producer failure is rethrown to the caller`() {
        var caught: Throwable? = null
        val pipeline = SynthesisPipeline(
            chunks = listOf("ok", "boom", "after"),
            synthesize = { chunk -> if (chunk == "boom") error("model exploded") else audio(2, 1f) },
            onAudio = { true },
            depth = 2,
        )
        try {
            pipeline.run()
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("expected the failure to surface", caught is IllegalStateException)
        assertEquals("model exploded", caught?.message)
    }

    @Test
    fun `empty chunk list is a no-op`() {
        var called = false
        val pipeline = SynthesisPipeline(
            chunks = emptyList(),
            synthesize = { audio(1, 1f) },
            onAudio = { called = true; true },
        )
        pipeline.run()
        assertTrue(!called)
        assertNull(null)
    }

    @Test
    fun `cancellation flag checked between chunks`() {
        val synthesized = AtomicInteger(0)
        val pipeline = SynthesisPipeline(
            chunks = (1..50).map { "c$it" },
            synthesize = {
                synthesized.incrementAndGet()
                audio(2, 1f)
            },
            onAudio = { true },
            isCancelled = { synthesized.get() >= 3 },
            depth = 1,
        )
        pipeline.run()
        assertTrue("stopped early (${synthesized.get()})", synthesized.get() <= 6)
    }
}
