package com.opentts.engine.onnx

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs synthesis one chunk ahead of playback.
 *
 * `TextToSpeechService` calls us on its own thread and `audioAvailable()` blocks
 * while the buffer drains, so a serial loop synthesizes chunk *n+1* only after
 * chunk *n* finished playing. On hardware where the model's real-time factor is
 * anywhere near 1.0 (an emulator, an older phone, Kokoro at speed 1.0), that
 * leaves the AudioTrack starving between chunks — heard as dropouts and clicks.
 *
 * A single producer thread keeps at most [depth] chunks queued, so chunk *n+1*
 * is ready before the consumer finishes playing *n*, while memory stays bounded.
 */
internal class SynthesisPipeline(
    private val chunks: List<String>,
    private val synthesize: (String) -> FloatArray,
    private val onAudio: (FloatArray) -> Boolean,
    private val isCancelled: () -> Boolean = { false },
    private val depth: Int = 2,
    private val onChunk: (index: Int, samples: Int, millis: Long) -> Unit = { _, _, _ -> },
) {
    private val queue = ArrayBlockingQueue<Any>(depth.coerceAtLeast(1))
    private val stopped = AtomicBoolean(false)
    private val end = Any()

    @Volatile
    private var failure: Throwable? = null

    /** Synthesizes and emits every chunk; returns early when cancelled. */
    fun run() {
        if (chunks.isEmpty()) return

        val producer = Thread({ produce() }, "opentts-synth").apply {
            isDaemon = true
            start()
        }

        try {
            while (true) {
                val item = queue.take()
                if (item === end) break
                val audio = item as FloatArray
                if (!onAudio(audio)) {
                    stopped.set(true)
                    break
                }
            }
        } catch (e: InterruptedException) {
            stopped.set(true)
            Thread.currentThread().interrupt()
        } finally {
            if (stopped.get()) queue.clear()
            producer.join(5_000)
        }

        failure?.let { throw it }
    }

    private fun produce() {
        try {
            for ((index, chunk) in chunks.withIndex()) {
                if (stopped.get() || isCancelled()) break
                val started = System.currentTimeMillis()
                val audio = synthesize(chunk)
                onChunk(index, audio.size, System.currentTimeMillis() - started)
                if (audio.isEmpty()) continue

                // Blocks while the consumer is behind — that is the backpressure.
                while (!stopped.get()) {
                    if (queue.offer(audio, 100, TimeUnit.MILLISECONDS)) break
                    if (isCancelled()) return
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.e("SynthesisPipeline", "chunk synthesis failed: ${t.message}", t)
        } finally {
            while (!stopped.get()) {
                if (queue.offer(end, 100, TimeUnit.MILLISECONDS)) break
            }
        }
    }
}
