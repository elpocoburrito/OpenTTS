package com.opentts.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the byte-alignment bug: a stream that arrives in
 * odd-length reads must still reach the framework byte for byte, in whole
 * frames. Before [PcmSink] the odd trailing byte was dropped, which swapped the
 * byte order of every following sample (audible as loud white noise).
 */
class PcmSinkTest {

    private class RecordingCallback(private val max: Int) : AudioTarget {
        val chunks = ArrayList<ByteArray>()
        var startCalls = 0
        var doneCalls = 0
        var errorCalls = 0
        var badAlignment = 0
        var oversized = 0

        override fun maxBufferSize(): Int = max

        override fun start(sampleRate: Int, audioFormat: Int, channels: Int): Int {
            startCalls++
            return 0 // TextToSpeech.SUCCESS
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length % 2 != 0) badAlignment++
            if (length > max) oversized++
            chunks.add(buffer.copyOfRange(offset, offset + length))
            return 0
        }

        override fun error() {
            errorCalls++
        }

        override fun done() {
            doneCalls++
        }

        fun collected(): ByteArray {
            val total = chunks.sumOf { it.size }
            val out = ByteArray(total)
            var pos = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, out, pos, chunk.size)
                pos += chunk.size
            }
            return out
        }
    }

    private fun source(length: Int): ByteArray =
        ByteArray(length) { (it * 7 + 3).toByte() }

    @Test
    fun `odd read sizes keep every sample aligned`() {
        val data = source(4001)
        val callback = RecordingCallback(max = 1000)
        val sink = PcmSink(callback, sampleRate = 24000, channels = 1) { false }

        // Deliberately awkward sizes, as TLS/socket reads produce.
        var offset = 0
        val sizes = intArrayOf(4095, 1, 3, 7, 999)
        var i = 0
        while (offset < data.size) {
            val take = minOf(sizes[i % sizes.size], data.size - offset)
            assertTrue(sink.write(data.copyOfRange(offset, offset + take), take))
            offset += take
            i++
        }
        sink.finish()

        // 4001 bytes is an odd total: the last dangling half-frame cannot be sent,
        // but everything before it must arrive byte for byte.
        assertArrayEquals(data.copyOf(data.size - 1), callback.collected())
        assertEquals(0, callback.badAlignment)
        assertEquals(0, callback.oversized)
        assertEquals(1, callback.startCalls)
        assertEquals(1, callback.doneCalls)
        assertEquals(0, callback.errorCalls)
    }

    @Test
    fun `stereo frames stay four byte aligned`() {
        val data = source(3001) // odd total, so a byte is left pending at the end
        val callback = RecordingCallback(max = 4096)
        val sink = PcmSink(callback, sampleRate = 44100, channels = 2) { false }

        var offset = 0
        while (offset < data.size - 1) { // stop on a 4-byte frame boundary
            val take = minOf(4097, data.size - 1 - offset)
            sink.write(data.copyOfRange(offset, offset + take), take)
            offset += take
        }
        sink.finish()

        val collected = callback.collected()
        assertEquals(0, collected.size % 4)
        assertArrayEquals(data.copyOf(collected.size), collected)
        assertEquals(0, callback.oversized)
    }

    @Test
    fun `nothing is emitted before start and finish still closes`() {
        val callback = RecordingCallback(max = 512)
        val sink = PcmSink(callback, sampleRate = 22050, channels = 1) { false }
        sink.finish()
        assertEquals(1, callback.startCalls)
        assertEquals(1, callback.doneCalls)
        assertEquals(0, callback.chunks.size)
    }
}
