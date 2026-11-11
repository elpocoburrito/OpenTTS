package com.opentts.engine.onnx

import android.util.Log

/** Builds the adapter for a model family with a given thread count. */
internal object AdapterFactory {
    fun build(
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        store: ModelStore,
        threads: Int,
    ): LocalTtsEngine.Model {
        val dir = store.modelDir(model.id)
        return when (model.family) {
            ModelFamily.KOKORO ->
                KokoroModel(dir, store.voiceFile(model, voice) ?: error("no voice file"), model.sampleRate, threads)

            ModelFamily.PIPER -> PiperModel(dir, model.sampleRate, threads)
            ModelFamily.MATCHA -> MatchaModel(dir, model.sampleRate, threads)
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
internal object ThreadTuner {

    private const val TAG = "ThreadTuner"
    private const val BENCH_PHONEMES =
        "həlˈoʊ wˈɜːld ðɪs ɪz ə bɛntʃmɑːɹk fɔːɹ ðə θɹɛd tjuːnɹ̩"

    private const val WARMUP_RUNS = 1
    private const val TIMED_RUNS = 2

    fun tune(
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        store: ModelStore,
        onTuned: (Int) -> Unit = {},
        candidates: List<Int> = defaultCandidates(),
    ): Int {
        val results = ArrayList<Pair<Int, Long>>()

        for (threads in candidates) {
            val adapter = runCatching { AdapterFactory.build(model, voice, store, threads) }
                .onFailure { Log.w(TAG, "threads=$threads could not build: ${it.message}") }
                .getOrNull() ?: continue

            try {
                repeat(WARMUP_RUNS) { adapter.synthesize(BENCH_PHONEMES, 1f, voice.speakerId) }
                val started = System.currentTimeMillis()
                repeat(TIMED_RUNS) { adapter.synthesize(BENCH_PHONEMES, 1f, voice.speakerId) }
                val elapsed = System.currentTimeMillis() - started
                results.add(threads to elapsed)
                Log.i(TAG, "threads=$threads -> ${elapsed} ms for $TIMED_RUNS runs")
            } catch (e: Exception) {
                Log.w(TAG, "threads=$threads benchmark failed: ${e.message}")
            } finally {
                runCatching { adapter.close() }
            }
        }

        val best = results.minByOrNull { it.second }?.first ?: candidates.first()
        if (results.isNotEmpty()) {
            Log.i(TAG, "best: $best thread(s) ${results.joinToString { "${it.first}:${it.second}ms" }}")
            runCatching { onTuned(best) }
        }
        return best
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
