package com.opentts.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Runs local TTS models through ONNX Runtime.
 *
 * Every family here is phoneme driven, so the pipeline is always
 * text -> espeak IPA -> model -> float PCM. What differs per family is the
 * tokenizer, the tensors and (for Matcha) the two-stage acoustic + vocoder graph;
 * that difference is isolated in the [Model] adapters below.
 */
class LocalTtsEngine(
    private val context: Context,
    private val store: ModelStore,
    /** 0 = ask the tuner (measured on this device, once). */
    private val configuredThreads: () -> Int = { 0 },
    /** Called with the winning thread count so it can be persisted. */
    private val onThreadsTuned: (Int) -> Unit = {},
) {

    /** A loaded, ready-to-run model. */
    interface Model : AutoCloseable {
        val sampleRate: Int
        /** [phonemes] are IPA. Returns mono float PCM in [-1, 1]. */
        fun synthesize(phonemes: String, speed: Float, speakerId: Int): FloatArray
    }

    private var cachedKey: String? = null
    private var cached: Model? = null

    @Synchronized
    fun load(model: LocalModelSpec, voice: LocalVoiceSpec): Model {
        val key = "${model.id}/${voice.id}"
        cached?.let { if (cachedKey == key) return it }
        close()

        val loaded = AdapterFactory.build(model, voice, store, resolveThreads(model, voice))
        cachedKey = key
        cached = loaded
        return loaded
    }

    /** Uses the stored thread count, benchmarking once when it is still 0. */
    private fun resolveThreads(model: LocalModelSpec, voice: LocalVoiceSpec): Int {
        val configured = configuredThreads()
        if (configured in 1..8) return configured
        val best = ThreadTuner.tune(model, voice, store, onThreadsTuned)
        Log.i(TAG, "thread tuner chose $best thread(s)")
        return best
    }

    /**
     * Loads the model and runs one short inference, so the first real utterance
     * does not pay for graph construction, kernel selection and page faults.
     */
    fun warm(model: LocalModelSpec, voice: LocalVoiceSpec) {
        val started = System.currentTimeMillis()
        val engine = load(model, voice)
        val phonemes = Phonemizer.phonemes(context, "warm up", voice.espeakVoice)
        if (phonemes.isNotBlank()) {
            runCatching { engine.synthesize(phonemes, 1f, voice.speakerId) }
        }
        Log.i(TAG, "warmed ${model.id}/${voice.id} in ${System.currentTimeMillis() - started} ms")
    }

    @Synchronized
    fun close() {
        runCatching { cached?.close() }
        cached = null
        cachedKey = null
    }

    /**
     * Synthesises [text] sentence by sentence and hands each block of float PCM
     * to [onAudio]. Chunking keeps time-to-first-audio short and caps the token
     * count per model call (Kokoro's style matrix only covers 510 positions).
     *
     * @return the sample rate of the produced audio.
     */
    fun speak(
        text: String,
        model: LocalModelSpec,
        voice: LocalVoiceSpec,
        speed: Float,
        isCancelled: () -> Boolean = { false },
        onAudio: (FloatArray) -> Boolean,
    ): Int {
        val engine = load(model, voice)
        val chunks = SentenceSplitter.split(text)
        Log.i(TAG, "Local synthesis: ${chunks.size} chunk(s), model=${model.id}, voice=${voice.id}")

        val started = System.currentTimeMillis()
        var firstAudioLogged = false

        SynthesisPipeline(
            chunks = chunks,
            synthesize = { chunk ->
                val phonemes = Phonemizer.phonemes(context, chunk, voice.espeakVoice)
                if (phonemes.isBlank()) {
                    Log.w(TAG, "espeak produced no phonemes for: ${chunk.take(60)}")
                    FloatArray(0)
                } else {
                    limitPeak(engine.synthesize(phonemes, speed, voice.speakerId))
                }
            },
            onAudio = { samples ->
                if (!firstAudioLogged) {
                    firstAudioLogged = true
                    Log.i(TAG, "first audio after ${System.currentTimeMillis() - started} ms")
                }
                onAudio(samples)
            },
            isCancelled = isCancelled,
            onChunk = { index, samples, millis ->
                Log.d(TAG, "chunk $index: ${if (chunks.size > index) chunks[index].length else 0} chars -> $samples samples in $millis ms")
            },
        ).run()

        return engine.sampleRate
    }

    /**
     * Kokoro occasionally overshoots ±1.0 on plosives (the reference Python
     * pipeline does the same, ~5 samples per phrase). Scaling a chunk down when
     * that happens avoids hard-clipping distortion in the 16-bit conversion.
     */
    private fun limitPeak(samples: FloatArray): FloatArray {
        var peak = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        if (peak <= 1.0f || peak == 0f) return samples
        val scale = 0.99f / peak
        return FloatArray(samples.size) { samples[it] * scale }
    }

    companion object {
        private const val TAG = "LocalTtsEngine"

    }
}

// ---------------------------------------------------------------------------
// Kokoro 82M — input_ids + style + speed -> waveform @ 24 kHz
// ---------------------------------------------------------------------------

internal class KokoroModel(
    private val dir: File,
    private val voiceFile: File,
    override val sampleRate: Int,
    threads: Int = threads(),
) : LocalTtsEngine.Model {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = SessionFactory.create(File(dir, "model.onnx"), threads)

    /** char -> id, from the model's HF `tokenizer.json`. 115 IPA symbols. */
    private val vocab: Map<String, Int> = run {
        val json = JSONObject(File(dir, "tokenizer.json").readText())
        val model = json.getJSONObject("model").getJSONObject("vocab")
        buildMap { model.keys().forEach { key -> put(key, model.getInt(key)) } }
    }

    private val padId: Int = vocab["$"] ?: 0

    /** 510 rows x 256 floats of style vectors, one row per phoneme count. */
    private val style: FloatArray = voiceFile.readBytes().let { bytes ->
        val floats = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        floats
    }

    private val styleRows: Int = style.size / 256

    override fun synthesize(phonemes: String, speed: Float, speakerId: Int): FloatArray {
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids.add(0L)
        for (c in phonemes) {
            vocab[c.toString()]?.let { ids.add(it.toLong()) }
        }
        ids.add(0L)
        if (ids.size <= 2) return FloatArray(0)

        val length = min(ids.size, styleRows)
        val row = styleRows.coerceAtMost(length + 1) - 1
        val styleRow = FloatArray(256)
        System.arraycopy(style, row * 256, styleRow, 0, 256)

        val tensors = ArrayList<OnnxTensor>(3)
        try {
            tensors.add(longTensor(env, LongArray(ids.size) { ids[it] }, longArrayOf(1, ids.size.toLong())))
            tensors.add(floatTensor(env, styleRow, longArrayOf(1, 256)))
            tensors.add(floatTensor(env, floatArrayOf(speed), longArrayOf(1)))

            val inputs = mapOf(
                "input_ids" to tensors[0],
                "style" to tensors[1],
                "speed" to tensors[2],
            )
            session.run(inputs).use { result ->
                val out = result.get(0) as OnnxTensor
                val samples = out.floatBuffer.let { buffer ->
                    val arr = FloatArray(buffer.remaining())
                    buffer.get(arr)
                    arr
                }
                return samples
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}

// ---------------------------------------------------------------------------
// Piper / VITS — input + input_lengths + scales (+ sid) -> audio
// ---------------------------------------------------------------------------

internal class PiperModel(
    private val dir: File,
    override val sampleRate: Int,
    threads: Int = threads(),
) : LocalTtsEngine.Model {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val config: JSONObject = run {
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: error("No .onnx in $dir")
        JSONObject(File(onnx.absolutePath + ".json").readText())
    }

    private val onnxFile: File = dir.listFiles { f -> f.name.endsWith(".onnx") }!!.first()

    private val session: OrtSession = SessionFactory.create(onnxFile, threads)

    private val phonemeIds: Map<String, Int> = run {
        val map = config.getJSONObject("phoneme_id_map")
        buildMap {
            map.keys().forEach { key ->
                val arr = map.getJSONArray(key)
                if (arr.length() > 0) put(key, arr.getInt(0))
            }
        }
    }

    private val padId: Int = phonemeIds["_"] ?: 0
    private val bosId: Int = phonemeIds["^"] ?: 1
    private val eosId: Int = phonemeIds["$"] ?: 2

    private val noiseScale: Float = config.optJSONObject("inference")?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f
    private val noiseW: Float = config.optJSONObject("inference")?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f
    private val hasSpeakerId: Boolean = config.optInt("num_speakers", 1) > 1

    /** Piper's own phoneme ids: BOS, then (id, PAD) per phoneme, then EOS. */
    private fun ids(phonemes: String): LongArray {
        val out = ArrayList<Long>(phonemes.length * 2 + 2)
        out.add(bosId.toLong())
        for (c in phonemes) {
            val id = phonemeIds[c.toString()] ?: continue
            out.add(id.toLong())
            out.add(padId.toLong())
        }
        out.add(eosId.toLong())
        return out.toLongArray()
    }

    override fun synthesize(phonemes: String, speed: Float, speakerId: Int): FloatArray {
        val tokens = ids(phonemes)
        if (tokens.size < 3) return FloatArray(0)

        val tensors = ArrayList<OnnxTensor>()
        try {
            val input = longTensor(env, tokens, longArrayOf(1, tokens.size.toLong()))
            val lengths = longTensor(env, longArrayOf(tokens.size.toLong()), longArrayOf(1))
            val scales = floatTensor(
                env,
                floatArrayOf(noiseScale, (1f / speed.coerceIn(0.5f, 2f)), noiseW),
                longArrayOf(3),
            )
            tensors.add(input); tensors.add(lengths); tensors.add(scales)

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input" to input,
                "input_lengths" to lengths,
                "scales" to scales,
            )
            if (hasSpeakerId) {
                val sid = longTensor(
                    env,
                    longArrayOf(speakerId.coerceAtLeast(0).toLong()),
                    longArrayOf(1),
                )
                tensors.add(sid)
                inputs["sid"] = sid
            }

            session.run(inputs).use { result ->
                val out = result.get(0) as OnnxTensor
                val buffer = out.floatBuffer
                val arr = FloatArray(buffer.remaining())
                buffer.get(arr)
                return arr
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}

// ---------------------------------------------------------------------------
// Matcha — acoustic model -> 80-band mel -> HiFiGAN vocoder -> audio
// ---------------------------------------------------------------------------

internal class MatchaModel(
    private val dir: File,
    override val sampleRate: Int,
    threads: Int = threads(),
) : LocalTtsEngine.Model {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val acoustic: OrtSession = SessionFactory.create(File(dir, "model.onnx"), threads)
    private val vocoder: OrtSession = SessionFactory.create(File(dir, "vocoder.onnx"), threads)

    /** tokens.txt from icefall: one symbol + id per line. */
    private val tokens: Map<String, Int> = run {
        buildMap {
            File(dir, "tokens.txt").forEachLine { line ->
                val parts = line.trim().split(' ')
                if (parts.size >= 2) {
                    val id = parts.last().toIntOrNull() ?: return@forEachLine
                    put(parts[0].removePrefix("▁"), id)
                    if (parts[0] != parts[0].removePrefix("▁")) put(parts[0], id)
                }
            }
        }
    }

    private val blankId: Int = tokens["^"] ?: 0

    override fun synthesize(phonemes: String, speed: Float, speakerId: Int): FloatArray {
        val ids = LongArray(phonemes.length) { i -> (tokens[phonemes[i].toString()] ?: blankId).toLong() }
        if (ids.isEmpty()) return FloatArray(0)

        val t1 = longTensor(env, ids, longArrayOf(1, ids.size.toLong()))
        val t2 = longTensor(env, longArrayOf(ids.size.toLong()), longArrayOf(1))
        val t3 = floatTensor(env, floatArrayOf(0.667f), longArrayOf(1))
        val t4 = floatTensor(env, floatArrayOf(1f / speed.coerceIn(0.5f, 2f)), longArrayOf(1))
        try {
            val mel = acoustic.run(
                mapOf("x" to t1, "x_length" to t2, "noise_scale" to t3, "length_scale" to t4),
            ).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val info = tensor.info as ai.onnxruntime.TensorInfo
                val buffer = tensor.floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }

            val melShape = longArrayOf(1, 80, mel.size.toLong() / 80)
            val melTensor = floatTensor(env, mel, melShape)
            try {
                // The vocoder's mel input is called "mel" (HiFiGAN) or "mels"
                // (Vocos); take whatever the graph declares.
                val melInputName = vocoder.inputNames.first()
                vocoder.run(mapOf(melInputName to melTensor)).use { result ->
                    // HiFiGAN returns [1, samples]; tolerate any rank.
                    val tensor = result.get(0) as OnnxTensor
                    val info = tensor.info as ai.onnxruntime.TensorInfo
                    Log.d("MatchaModel", "vocoder output shape ${info.shape.toList()}")
                    val buffer = tensor.floatBuffer
                    val arr = FloatArray(buffer.remaining())
                    buffer.get(arr)
                    return arr
                }
            } finally {
                runCatching { melTensor.close() }
            }
        } finally {
            listOf(t1, t2, t3, t4).forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { acoustic.close() }
        runCatching { vocoder.close() }
    }
}

// ---------------------------------------------------------------------------

private fun threads(): Int = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))

// ONNX Runtime's Android build exposes buffer-based factories only, so arrays
// are wrapped rather than passed directly.
private fun longTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(data), shape)

private fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(data), shape)

/** Splits prose into speakable chunks that fit a single model call. */
object SentenceSplitter {
    private const val MAX_CHARS = 320

    fun split(text: String): List<String> {
        val cleaned = text.replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()

        val sentences = ArrayList<String>()
        val current = StringBuilder()
        val terminators = charArrayOf('.', '!', '?', '…', '。', '！', '？', ';', ':')

        fun flush() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) sentences.add(piece)
            current.setLength(0)
        }

        for (ch in cleaned) {
            current.append(ch)
            if (ch in terminators || ch == '\n') flush()
            if (current.length >= MAX_CHARS) {
                // Hard wrap long run-ons at the last space.
                val cut = current.lastIndexOf(' ')
                if (cut > 40) {
                    val remainder = current.substring(cut + 1)
                    current.setLength(cut)
                    flush()
                    current.append(remainder)
                } else {
                    flush()
                }
            }
        }
        flush()

        // Merge tiny fragments so we do not pay model-startup cost per "Dr." etc.
        val merged = ArrayList<String>()
        for (s in sentences) {
            if (merged.isNotEmpty() && merged.last().length + s.length < 80) {
                merged[merged.size - 1] = merged.last() + " " + s
            } else {
                merged.add(s)
            }
        }
        return merged
    }
}
