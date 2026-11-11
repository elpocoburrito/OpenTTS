package com.opentts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.zip.CRC32

/**
 * Minimal surface of `SynthesisCallback` that [PcmSink] needs.
 *
 * The SDK class cannot be subclassed in a plain JVM unit test (its constructor
 * is not accessible), so the sink talks to this interface and a thin adapter
 * forwards to the real callback. That keeps the byte-alignment logic testable.
 */
internal interface AudioTarget {
    fun start(sampleRate: Int, audioFormat: Int, channels: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
    fun done()
    fun error()
    fun maxBufferSize(): Int
}

internal class CallbackTarget(private val callback: SynthesisCallback) : AudioTarget {
    override fun start(sampleRate: Int, audioFormat: Int, channels: Int): Int =
        callback.start(sampleRate, audioFormat, channels)

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int =
        callback.audioAvailable(buffer, offset, length)

    override fun done() {
        runCatching { callback.done() }
    }

    override fun error() {
        runCatching { callback.error() }
    }

    override fun maxBufferSize(): Int =
        callback.getMaxBufferSize().takeIf { it in 2..(1 shl 20) } ?: 4096

    companion object {
        const val PCM_16 = AudioFormat.ENCODING_PCM_16BIT
        const val SUCCESS = TextToSpeech.SUCCESS
    }
}

/**
 * Frame-aligned writer for the TTS callback.
 *
 * The framework consumes interleaved 16-bit frames, so every call must carry a
 * whole number of frames. Streaming code that forwards whatever a socket handed
 * it breaks that: one odd-length read (TLS and HTTP produce them) leaves the
 * writer a byte out of phase, and every following sample is byte-swapped —
 * audible as full-scale white noise, not as a glitch.
 *
 * This class owns the carry-over byte, so that cannot happen, and keeps each
 * write within the callback's maximum buffer size.
 */
internal class PcmSink(
    private val target: AudioTarget,
    private val sampleRate: Int,
    private val channels: Int,
    private val isCancelled: () -> Boolean,
) {
    private val frameBytes = 2 * channels
    private val pending = ByteArray(frameBytes)
    private var pendingLength = 0
    private var started = false

    private val maxBuffer: Int =
        (target.maxBufferSize() / frameBytes).coerceAtLeast(1) * frameBytes

    // Debug builds log a CRC of exactly what reached the framework, so a
    // corrupted stream (byte swap, dropped sample) is visible in logcat.
    private val crc = CRC32()
    private var emittedBytes = 0L

    val isStarted: Boolean get() = started

    private fun startIfNeeded(): Boolean {
        if (started) return true
        val result = target.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels)
        if (result != TextToSpeech.SUCCESS) return false
        started = true
        return true
    }

    /** Writes [length] bytes from [data]; false when cancelled or refused. */
    fun write(data: ByteArray, length: Int): Boolean {
        if (length <= 0) return true
        if (isCancelled()) return false
        if (!startIfNeeded()) return false

        var index = 0

        // 1. Complete the frame left over from the previous call.
        if (pendingLength > 0) {
            val take = minOf(frameBytes - pendingLength, length)
            System.arraycopy(data, 0, pending, pendingLength, take)
            pendingLength += take
            index = take
            if (pendingLength < frameBytes) return true
            if (target.write(pending, 0, frameBytes) != TextToSpeech.SUCCESS) return false
            if (BuildConfig.DEBUG) {
                crc.update(pending, 0, frameBytes)
                emittedBytes += frameBytes
            }
            pendingLength = 0
        }

        // 2. Whole frames, chunked to the callback's buffer limit.
        val framesEnd = index + ((length - index) / frameBytes) * frameBytes
        while (index < framesEnd) {
            if (isCancelled()) return false
            val chunk = minOf(maxBuffer, framesEnd - index)
            if (target.write(data, index, chunk) != TextToSpeech.SUCCESS) return false
            if (BuildConfig.DEBUG) {
                crc.update(data, index, chunk)
                emittedBytes += chunk
            }
            index += chunk
        }

        // 3. Keep the tail (fewer bytes than one frame) for the next call.
        val rest = length - index
        if (rest > 0) {
            System.arraycopy(data, index, pending, 0, rest)
            pendingLength = rest
        }
        return true
    }

    /** Ends the utterance. */
    fun finish() {
        if (!started && !startIfNeeded()) {
            target.error()
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "PcmSink",
                "utterance done: $emittedBytes bytes, ${emittedBytes / frameBytes} frames, " +
                    "crc=${java.lang.Long.toHexString(crc.value)}, rate=$sampleRate, channels=$channels",
            )
        }
        target.done()
    }

    fun error() = target.error()
}
