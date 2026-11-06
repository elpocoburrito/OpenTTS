package com.opentts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Android text-to-speech engine backed by OpenRouter's `/audio/speech` endpoint.
 *
 * The platform calls [onSynthesizeText] on its own dedicated synthesis thread;
 * the contract requires synthesis to be synchronous, so the network request and
 * decoding run inline on that thread. Cancellation still works because [onStop]
 * is invoked on a different thread and disconnects the live HTTP connection.
 */
class OpenRouterTtsService : TextToSpeechService() {

    private val store by lazy { SettingsStore(this) }

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeStream: SpeechStream? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OpenTTS engine created")
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
        val effective = applyRequestOverrides(base, request)

        if (effective.apiKey.isBlank()) {
            Log.w(TAG, "Cannot synthesize: no OpenRouter API key configured")
            runCatching { callback.error() }
            return
        }

        if (text.isBlank()) {
            callback.start(effective.sampleRate, AudioFormat.ENCODING_PCM_16BIT, effective.channels)
            callback.done()
            return
        }

        val started = System.currentTimeMillis()
        try {
            val stream = OpenRouterClient(effective).openSpeech(text)
            activeStream = stream
            stream.use {
                if (cancelRequested) return
                Log.d(TAG, "Streaming ${effective.format} from ${effective.model}/${effective.voice}")
                when (effective.format) {
                    TtsSettings.FORMAT_PCM -> AudioDecoder.streamPcm(
                        input = stream.input,
                        sampleRate = effective.sampleRate,
                        channels = effective.channels,
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
            Log.d(TAG, "Synthesis finished in ${System.currentTimeMillis() - started} ms")
        } catch (e: SynthesisCancelled) {
            Log.d(TAG, "Synthesis cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed: ${e.message}", e)
            runCatching { callback.error() }
        } finally {
            activeStream = null
        }
    }

    /** Applies a per-utterance voice and (optionally) the caller's speech rate. */
    private fun applyRequestOverrides(base: TtsSettings, request: SynthesisRequest): TtsSettings {
        var result = base

        val voiceName = request.voiceName
        if (!voiceName.isNullOrBlank()) {
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
        val models = ModelsRepository.fromJson(settings.catalogJson)
        val configured = models.firstOrNull { it.id == settings.model }

        val names = LinkedHashSet<String>()
        val voices = configured?.voices.orEmpty().ifEmpty { listOf(settings.voice) }
        for (voice in voices) {
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
        return voiceName(settings.model, settings.voice)
    }

    override fun onIsValidVoiceName(name: String?): Int =
        if (name != null && parseVoiceName(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    private fun createVoice(name: String): Voice =
        Voice(
            name,
            Locale.US,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            /* requiresNetwork = */ true,
            setOf("networkSynthesis"),
        )

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
