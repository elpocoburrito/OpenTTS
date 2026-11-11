package com.opentts.engine.onnx

import android.util.Log

/** Builds the adapter for a model family with a given thread count. */
internal object AdapterFactory {
    fun build(
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        store: ModelStore,
        threads: Int,
        xnnpack: Boolean = true,
    ): LocalTtsEngine.Model {
        val dir = store.modelDir(model.id)
        return when (model.family) {
            ModelFamily.KOKORO ->
                KokoroModel(dir, store.voiceFile(model, voice) ?: error("no voice file"), model.sampleRate, threads, xnnpack)

            ModelFamily.PIPER -> PiperModel(dir, model.sampleRate, threads, xnnpack)
            ModelFamily.MATCHA -> MatchaModel(dir, model.sampleRate, threads, xnnpack)
        }
    }
}

/**
 * Picks the intra-op thread count by measuring, once per device.
 *
 * On big.LITTLE SoCs the naive answer (one thread per core) is often slower than
 * 2-3 threads: the little cores finish their share late and everyone waits. The
 * benchmark runs the real model on a fixed phoneme string, so the winner
 * reflects the actual graph rather than a synthetic matrix multiply.
 */
object ThreadTuner {

    private const val TAG = "ThreadTuner"
    private const val BENCH_PHONEMES =
        "həlˈoʊ wˈɜːld ðɪs ɪz ə bɛntʃmɑːɹk fɔːɹ ðə θɹɛd tjuːnɹ̩"

    private const val WARMUP_RUNS = 1
    private const val TIMED_RUNS = 2

    /** Winning combination for this device + model. */
    data class Result(val threads: Int, val xnnpack: Boolean)

    /**
     * Measures intra-op thread counts first, then A/Bs XNNPACK at the winning
     * count. XNNPACK is not automatically better: on the models shipped here it
     * loses 7-16 % on tested ARM devices (int8 MatMuls are not XNNPACK kernels, so the
     * graph gets partitioned with boundary copies), but a future model may
     * differ, and this costs one extra session build to find out.
     */
    fun tune(
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        store: ModelStore,
        onTuned: (Result) -> Unit = {},
        candidates: List<Int> = defaultCandidates(),
    ): Result {
        val measured = ArrayList<Triple<Int, Boolean, Long>>()

        for (threads in candidates) {
            measure(model, voice, store, threads, xnnpack = false)?.let { (t, ms) ->
                measured.add(Triple(t, false, ms))
            }
        }
        val bestThreads = measured.minByOrNull { it.third }?.first ?: candidates.first()

        measure(model, voice, store, bestThreads, xnnpack = true)?.let { (t, ms) ->
            measured.add(Triple(t, true, ms))
        }

        val winner = measured.minByOrNull { it.third }
        val result = Result(winner?.first ?: bestThreads, winner?.second ?: false)

        if (measured.isNotEmpty()) {
            Log.i(
                TAG,
                "best: threads=${result.threads} xnnpack=${result.xnnpack} " +
                    measured.joinToString { "t${it.first}/xnn=${it.second}:${it.third}ms" },
            )
            runCatching { onTuned(result) }
        }
        return result
    }

    private fun measure(
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        store: ModelStore,
        threads: Int,
        xnnpack: Boolean,
    ): Pair<Int, Long>? {
        val adapter = runCatching { AdapterFactory.build(model, voice, store, threads, xnnpack) }
            .onFailure { Log.w(TAG, "t$threads/xnn=$xnnpack could not build: ${it.message}") }
            .getOrNull() ?: return null

        return try {
            repeat(WARMUP_RUNS) { adapter.synthesize(BENCH_PHONEMES, 1f, voice.speakerId) }
            val started = System.currentTimeMillis()
            repeat(TIMED_RUNS) { adapter.synthesize(BENCH_PHONEMES, 1f, voice.speakerId) }
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "threads=$threads xnnpack=$xnnpack -> ${elapsed} ms for $TIMED_RUNS runs")
            threads to elapsed
        } catch (e: Exception) {
            Log.w(TAG, "t$threads/xnn=$xnnpack benchmark failed: ${e.message}")
            null
        } finally {
            runCatching { adapter.close() }
        }
    }

    private fun defaultCandidates(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        return buildList {
            add(2)
            if (cores >= 4) add(4)
            if (cores >= 8) add(3)
        }.distinct().sorted()
    }
}
