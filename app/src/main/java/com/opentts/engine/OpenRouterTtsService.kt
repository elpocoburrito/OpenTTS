package com.opentts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.opentts.engine.onnx.LocalModelCatalog
import com.opentts.engine.onnx.LocalTtsEngine
import com.opentts.engine.onnx.ModelStore
import com.opentts.engine.onnx.Phonemizer
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Android text-to-speech engine with two interchangeable backends:
 *
 *  - **cloud** — OpenRouter's `/audio/speech` endpoint (the original behaviour).
 *  - **local** — on-device ONNX models (Kokoro 82M, Piper/VITS, Matcha) running
 *    through ONNX Runtime, with espeak-ng for phonemisation. No network at all.
 *
 * The platform calls [onSynthesizeText] on its own dedicated synthesis thread and
 * requires it to be synchronous, so both paths run inline. Cancellation works
 * because [onStop] is invoked on another thread: the cloud path closes the live
 * HTTP stream, the local path flips a flag checked between audio buffers.
 */
class OpenRouterTtsService : TextToSpeechService() {

    private val store by lazy { SettingsStore(this) }
    private val localModels by lazy { ModelStore(this) }
    private val localEngine by lazy { LocalTtsEngine(this, localModels) }

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeStream: SpeechStream? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OpenTTS engine created (cloud + local backends)")
    }

    // ---- Language handling -------------------------------------------------

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

    // ---- Synthesis ---------------------------------------------------------

    override fun onStop() {
        cancelRequested = true
        runCatching { activeStream?.close() }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        cancelRequested = false
        val base = store.load()
        val text = (request.charSequenceText ?: request.text ?: "").toString()

        // A voice picked in the framework wins over the stored backend: reading
        // apps hand us the voice name they were configured with, and the
        // "local|model/voice" prefix is unambiguous.
        val requested = request.voiceName?.let { LocalVoiceKey.parse(it) }
        val settings = applyRequestOverrides(base, request)

        if (requested != null) {
            synthesizeLocal(requested.first, requested.second, text, settings.speed, callback)
            return
        }
        if (settings.backend == TtsSettings.BACKEND_LOCAL) {
            synthesizeLocal(settings.localModelId, settings.localVoiceId, text, settings.speed, callback)
            return
        }
        synthesizeCloud(settings, text, callback)
    }

    private fun synthesizeCloud(settings: TtsSettings, text: String, callback: SynthesisCallback) {
        if (settings.apiKey.isBlank()) {
            Log.w(TAG, "Cannot synthesize: no OpenRouter API key configured")
            runCatching { callback.error() }
            return
        }

        if (text.isBlank()) {
            callback.start(settings.sampleRate, AudioFormat.ENCODING_PCM_16BIT, settings.channels)
            callback.done()
            return
        }

        val started = System.currentTimeMillis()
        try {
            val stream = OpenRouterClient(settings).openSpeech(text)
            activeStream = stream
            stream.use {
                if (cancelRequested) return
                Log.d(TAG, "Streaming ${settings.format} from ${settings.model}/${settings.voice}")
                when (settings.format) {
                    TtsSettings.FORMAT_PCM -> AudioDecoder.streamPcm(
                        input = stream.input,
                        sampleRate = settings.sampleRate,
                        channels = settings.channels,
                        callback = callback,
                        isCancelled = { cancelRequested },
                    )

                    else -> {
                        val tempFile = File.createTempFile("opentts-", ".mp3", cacheDir)
                        try {
                            stream.input.copyToCancellable(tempFile.outputStream()) { cancelRequested }
                            AudioDecoder.decodeFileToPcm(tempFile, callback) { cancelRequested }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }
            Log.d(TAG, "Cloud synthesis finished in ${System.currentTimeMillis() - started} ms")
        } catch (e: SynthesisCancelled) {
            Log.d(TAG, "Synthesis cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed: ${e.message}", e)
            runCatching { callback.error() }
        } finally {
            activeStream = null
        }
    }

    private fun synthesizeLocal(
        modelId: String,
        voiceId: String,
        text: String,
        speed: Float,
        callback: SynthesisCallback,
    ) {
        val pair = LocalModelCatalog.voice(modelId, voiceId)
        if (pair == null) {
            Log.e(TAG, "Unknown local model/voice: $modelId/$voiceId")
            runCatching { callback.error() }
            return
        }
        val (model, voice) = pair

        if (!localModels.isVoiceReady(model, voice)) {
            Log.e(TAG, "Local model is not installed: ${model.id} (open OpenTTS to download it)")
            runCatching { callback.error() }
            return
        }
        if (!Phonemizer.ensure(this)) {
            Log.e(TAG, "espeak-ng unavailable, cannot phonemise locally")
            runCatching { callback.error() }
            return
        }
        if (text.isBlank()) {
            callback.start(model.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        var started = false
        val began = System.currentTimeMillis()
        try {
            callback.start(model.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            started = true

            localEngine.speak(text, model, voice, speed) { samples ->
                if (cancelRequested) return@speak false
                emitPcm(callback, samples) { cancelRequested }
            }
            Log.d(TAG, "Local synthesis finished in ${System.currentTimeMillis() - began} ms")
            if (!cancelRequested) runCatching { callback.done() }
        } catch (e: Exception) {
            Log.e(TAG, "Local synthesis failed: ${e.message}", e)
            if (!started) {
                runCatching { callback.start(model.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) }
            }
            runCatching { callback.error() }
        }
    }

    /** Converts float PCM to 16-bit mono little-endian and feeds the framework. */
    private fun emitPcm(
        callback: SynthesisCallback,
        samples: FloatArray,
        isCancelled: () -> Boolean,
    ): Boolean {
        val maxShorts = (callback.maxBufferSize / 2).coerceAtLeast(1)
        val bytes = ByteArray(min(maxShorts, samples.size).coerceAtLeast(1) * 2)
        var index = 0
        while (index < samples.size) {
            if (isCancelled()) return false
            val count = min(maxShorts, samples.size - index)
            for (i in 0 until count) {
                val v = (samples[index + i].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            if (callback.audioAvailable(bytes, 0, count * 2) != TextToSpeech.SUCCESS) return false
            index += count
        }
        return true
    }

    /** Applies a per-utterance voice and (optionally) the caller's speech rate. */
    private fun applyRequestOverrides(base: TtsSettings, request: SynthesisRequest): TtsSettings {
        var result = base

        val voiceName = request.voiceName
        if (!voiceName.isNullOrBlank() && LocalVoiceKey.parse(voiceName) == null) {
            parseVoiceName(voiceName)?.let { (model, voice) ->
                result = result.copy(model = model, voice = voice)
            }
        }

        val rate = request.speechRate
        if (result.useClientRate && rate in 1..1000 && rate != 100) {
            result = result.copy(speed = (result.speed * rate / 100f).coerceIn(0.25f, 4.0f))
        }

        return result
    }

    // ---- Voices ------------------------------------------------------------

    override fun onGetVoices(): MutableList<Voice> {
        val settings = store.load()
        val names = LinkedHashSet<String>()

        // Every voice of every installed local model.
        for (model in LocalModelCatalog.all) {
            if (!localModels.isInstalled(model)) continue
            for (voice in model.voices) {
                if (localModels.isVoiceReady(model, voice)) {
                    names.add(LocalVoiceKey.format(model.id, voice.id))
                }
            }
        }

        // Cloud voices from the cached catalogue.
        val models = ModelsRepository.fromJson(settings.catalogJson)
        val configured = models.firstOrNull { it.id == settings.model }
        val cloudVoices = configured?.voices.orEmpty().ifEmpty { listOf(settings.voice) }
        for (voice in cloudVoices) {
            if (voice.isNotBlank()) names.add(voiceName(settings.model, voice))
        }
        if (names.isEmpty()) names.add(voiceName(settings.model, settings.voice))

        return names.map { createVoice(it) }.toMutableList()
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String {
        val settings = store.load()
        if (settings.backend == TtsSettings.BACKEND_LOCAL) {
            val pair = LocalModelCatalog.voice(settings.localModelId, settings.localVoiceId)
            val modelId = pair?.first?.id ?: TtsSettings.DEFAULT_LOCAL_MODEL
            val voiceId = pair?.second?.id ?: TtsSettings.DEFAULT_LOCAL_VOICE
            return LocalVoiceKey.format(modelId, voiceId)
        }
        return voiceName(settings.model, settings.voice)
    }

    override fun onIsValidVoiceName(name: String?): Int {
        if (name == null) return TextToSpeech.ERROR
        if (LocalVoiceKey.parse(name) != null) return TextToSpeech.SUCCESS
        return if (parseVoiceName(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    private fun createVoice(name: String): Voice {
        val local = LocalVoiceKey.parse(name)
        if (local != null) {
            val (model, voice) = LocalModelCatalog.voice(local.first, local.second)
                ?: return fallbackVoice(name)
            val locale = Locale.forLanguageTag(voice.language)
            return Voice(
                name,
                if (locale.language.isBlank()) Locale.US else locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                /* requiresNetwork = */ false,
                setOf("localSynthesis", model.family.label),
            )
        }
        return Voice(
            name,
            Locale.US,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            /* requiresNetwork = */ true,
            setOf("networkSynthesis"),
        )
    }

    private fun fallbackVoice(name: String): Voice =
        Voice(name, Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())

    companion object {
        private const val TAG = "OpenRouterTtsService"
        private const val SEPARATOR = "|"

        fun voiceName(model: String, voice: String): String = "$model$SEPARATOR$voice"

        /** Parses "author/model|voice" into its two parts, or null if malformed. */
        fun parseVoiceName(name: String): Pair<String, String>? {
            val index = name.indexOf(SEPARATOR)
            if (index <= 0) return null
            val model = name.substring(0, index)
            val voice = name.substring(index + 1)
            if (model.isBlank() || voice.isBlank()) return null
            return model to voice
        }
    }
}

/**
 * Framework voice names for local models: `local|<modelId>/<voiceId>`.
 * The "local" prefix keeps them distinct from cloud `author/model|voice` names.
 */
object LocalVoiceKey {
    private const val PREFIX = "local"

    fun format(modelId: String, voiceId: String): String = "$PREFIX|$modelId/$voiceId"

    /** Returns (modelId, voiceId) when [name] is a known local voice. */
    fun parse(name: String): Pair<String, String>? {
        if (!name.startsWith("$PREFIX|")) return null
        val rest = name.removePrefix("$PREFIX|")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val modelId = rest.substring(0, slash)
        val voiceId = rest.substring(slash + 1)
        if (voiceId.isBlank()) return null
        return LocalModelCatalog.voice(modelId, voiceId)?.let { modelId to voiceId }
    }
}
