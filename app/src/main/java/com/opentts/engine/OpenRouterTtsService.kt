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
import com.opentts.engine.server.ClientCertStore
import com.opentts.engine.server.ServerClient
import com.opentts.engine.server.ServerException
import java.io.File
import java.util.Locale

/**
 * Android text-to-speech engine with three interchangeable backends:
 *
 *  - **cloud** — OpenRouter's `/audio/speech` endpoint (the original behaviour).
 *  - **local** — on-device ONNX models (Kokoro 82M, Piper/VITS, Matcha) running
 *    through ONNX Runtime, with espeak-ng for phonemisation. No network at all.
 *  - **server** — a self-hosted OpenTTS-SRV instance.
 *
 * The platform calls [onSynthesizeText] on its own dedicated synthesis thread and
 * requires it to be synchronous, so every path runs inline. Cancellation works
 * because [onStop] is invoked on another thread: the streaming paths close the
 * live HTTP connection, the local path checks a flag between audio buffers.
 *
 * Reading apps are unforgiving: they show "speech error" and move on, with no
 * detail and no retry. So a failure here is never fatal on its own — a backend
 * that cannot produce audio hands over to the next one, and the reason is
 * recorded in [TtsDiagnostics] for the settings screen.
 */
class OpenRouterTtsService : TextToSpeechService() {

    private val store by lazy { SettingsStore(this) }
    private val localModels by lazy { ModelStore(this) }
    private val localEngine by lazy {
        LocalTtsEngine(
            context = this,
            store = localModels,
            configuredThreads = { store.load().localThreads },
            onThreadsTuned = { tuned ->
                store.save(
                    store.load().copy(
                        localThreads = tuned.threads,
                        localXnnpack = tuned.xnnpack,
                    ),
                )
            },
            xnnpackEnabled = { store.load().localXnnpack },
        )
    }

    @Volatile
    private var cancelRequested = false

    /** Either a cloud [SpeechStream] or a [ServerStream]; both are closeable. */
    @Volatile
    private var activeStream: AutoCloseable? = null

    /** What a synthesis attempt achieved, so the caller can decide to fall over. */
    private data class Outcome(val started: Boolean, val ok: Boolean) {
        companion object {
            val Failed = Outcome(started = false, ok = false)
            fun delivered() = Outcome(started = true, ok = true)
            /** Audio reached the client but something went wrong afterwards. */
            fun aborted() = Outcome(started = true, ok = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OpenTTS engine created (cloud + local + server backends)")
        warmLocalModel()
    }

    /**
     * Loads the selected local model in the background at service start. Latency
     * for the first utterance drops from seconds to nothing, and the thread
     * tuner runs while nothing is waiting on it.
     */
    private fun warmLocalModel() {
        val settings = store.load()
        // Warmed even when another backend is selected: if that backend is
        // unreachable, the fallback in onSynthesizeText uses this model and
        // would otherwise pay the load on the reader's first utterance.
        val pair = LocalModelCatalog.voice(settings.localModelId, settings.localVoiceId) ?: return
        val (model, voice) = pair
        if (!localModels.isVoiceReady(model, voice)) return

        Thread({
            runCatching { localEngine.warm(model, voice) }
                .onFailure { Log.w(TAG, "warm-up failed: ${it.message}") }
        }, "opentts-warmup").start()
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
        val voiceName = request.voiceName

        try {
            val settings = applyRequestOverrides(base, request)
            Log.d(
                TAG,
                "synthesize: ${text.length} chars, backend=${settings.backend}, " +
                    "voice=${voiceName ?: "(none)"}, maxBuffer=${callback.maxBufferSize}",
            )

            if (text.isBlank()) {
                callback.start(settings.sampleRate, AudioFormat.ENCODING_PCM_16BIT, settings.channels)
                callback.done()
                return
            }

            // 1. A voice name the reading app pinned wins over the stored backend,
            //    but only when it can actually be served.
            val pinnedLocal = voiceName?.let { LocalVoiceKey.parse(it) }
            if (pinnedLocal != null) {
                val (model, voice) = pinnedLocal
                val outcome = synthesizeLocal(model, voice, text, settings.speed, callback)
                if (outcome.ok) return
                if (outcome.started) {
                    callback.error()
                    return
                }
                TtsDiagnostics.record(
                    this,
                    "pinned local voice $voiceName produced no audio (model not installed?)",
                )
            }

            val pinnedServer = voiceName?.let { ServerVoiceKey.parse(it) }
            if (pinnedServer != null && settings.serverUrl.isNotBlank()) {
                val (model, voice) = pinnedServer
                val outcome = synthesizeServer(settings, model, voice, text, callback)
                if (outcome.ok) return
                if (outcome.started) {
                    callback.error()
                    return
                }
                TtsDiagnostics.record(this, "pinned server voice $voiceName produced no audio")
            }

            // 2. Stored backend first, then the others, so one misconfiguration
            //    cannot leave a reading app with no voice at all.
            for (backend in BackendFallback.order(settings.backend)) {
                val outcome = when (backend) {
                    TtsSettings.BACKEND_LOCAL -> synthesizeLocal(
                        settings.localModelId,
                        settings.localVoiceId,
                        text,
                        settings.speed,
                        callback,
                    )

                    TtsSettings.BACKEND_SERVER -> synthesizeServer(
                        settings,
                        settings.serverModel,
                        settings.serverVoice,
                        text,
                        callback,
                    )

                    else -> synthesizeCloud(settings, text, callback)
                }
                if (outcome.ok) return
                if (outcome.started) {
                    // Some audio already reached the client: appending a second
                    // backend's stream would duplicate words, so stop here.
                    TtsDiagnostics.record(this, "$backend stopped mid-utterance")
                    callback.error()
                    return
                }
                TtsDiagnostics.record(this, "$backend produced no audio; trying next backend")
            }

            TtsDiagnostics.record(
                this,
                "no backend could synthesize ${text.length} chars; " +
                    "voice=${voiceName ?: "(none)"}; configure one in OpenTTS settings",
            )
            runCatching { callback.error() }
        } catch (t: Throwable) {
            // A Throwable that escapes here kills the process and the client sees
            // only "error", so it is recorded and converted into a callback error.
            TtsDiagnostics.record(
                this,
                "synthesis crashed: ${t.javaClass.simpleName}: ${t.message}",
            )
            runCatching { callback.error() }
        }
    }

    private fun synthesizeCloud(
        settings: TtsSettings,
        text: String,
        callback: SynthesisCallback,
    ): Outcome {
        if (settings.apiKey.isBlank()) {
            TtsDiagnostics.record(this, "cloud backend skipped: no OpenRouter API key")
            return Outcome.Failed
        }

        val started = System.currentTimeMillis()
        return try {
            val stream = OpenRouterClient(settings).openSpeech(text)
            activeStream = stream
            var delivered = false
            stream.use {
                if (cancelRequested) return Outcome.Failed
                Log.d(TAG, "Streaming ${settings.format} from ${settings.model}/${settings.voice}")
                delivered = when (settings.format) {
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
            if (delivered) Outcome.delivered() else Outcome.Failed
        } catch (e: SynthesisCancelled) {
            Log.d(TAG, "Cloud synthesis cancelled")
            Outcome.Failed
        } catch (e: Exception) {
            TtsDiagnostics.record(this, "cloud failed: ${e.message}")
            Outcome.Failed
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
    ): Outcome {
        val pair = LocalModelCatalog.voice(modelId, voiceId)
        if (pair == null) {
            TtsDiagnostics.record(this, "unknown local model/voice: $modelId/$voiceId")
            return Outcome.Failed
        }
        val (model, voice) = pair

        if (!localModels.isVoiceReady(model, voice)) {
            TtsDiagnostics.record(this, "local model not installed: ${model.id}")
            return Outcome.Failed
        }
        if (!Phonemizer.ensure(this)) {
            TtsDiagnostics.record(this, "espeak-ng unavailable, cannot phonemise locally")
            return Outcome.Failed
        }

        val sink = PcmSink(CallbackTarget(callback), model.sampleRate, /* channels = */ 1) { cancelRequested }
        val began = System.currentTimeMillis()
        return try {
            localEngine.speak(text, model, voice, speed, isCancelled = { cancelRequested }) { samples ->
                if (cancelRequested) return@speak false
                sink.writeFloats(samples)
            }
            if (cancelRequested) {
                Log.d(TAG, "Local synthesis cancelled")
                if (sink.isStarted) Outcome.aborted() else Outcome.Failed
            } else {
                sink.finish()
                Log.d(TAG, "Local synthesis finished in ${System.currentTimeMillis() - began} ms")
                Outcome.delivered()
            }
        } catch (e: Exception) {
            TtsDiagnostics.record(this, "local failed: ${e.message}")
            if (!sink.isStarted) runCatching {
                callback.start(model.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            }
            if (sink.isStarted) Outcome.aborted() else Outcome.Failed
        }
    }

    /**
     * Streams 16-bit PCM from a self-hosted OpenTTS-SRV instance. The server is
     * asked for `pcm`, so the bytes go straight to the framework — no decoder —
     * and the true rate comes from the response headers.
     */
    private fun synthesizeServer(
        settings: TtsSettings,
        model: String,
        voice: String,
        text: String,
        callback: SynthesisCallback,
    ): Outcome {
        if (settings.serverUrl.isBlank()) {
            TtsDiagnostics.record(this, "server backend skipped: no URL configured")
            return Outcome.Failed
        }

        val started = System.currentTimeMillis()
        return try {
            val client = ServerClient(
                baseUrl = settings.serverUrl,
                token = settings.serverToken,
                certs = ClientCertStore.load(this),
            )
            val stream = client.openSpeech(
                text = text,
                model = model.ifBlank { TtsSettings.DEFAULT_LOCAL_MODEL },
                voice = voice.ifBlank { null },
                speed = settings.speed,
            )
            activeStream = stream
            var delivered = false
            stream.use {
                if (cancelRequested) return Outcome.Failed
                delivered = AudioDecoder.streamPcm(
                    input = stream.input,
                    sampleRate = stream.sampleRate,
                    channels = 1,
                    callback = callback,
                    isCancelled = { cancelRequested },
                )
            }
            Log.d(TAG, "Server synthesis finished in ${System.currentTimeMillis() - started} ms")
            if (delivered) Outcome.delivered() else Outcome.Failed
        } catch (e: ServerException) {
            TtsDiagnostics.record(this, "server failed: ${e.message}")
            Outcome.Failed
        } catch (e: Exception) {
            TtsDiagnostics.record(this, "server failed: ${e.message}")
            Outcome.Failed
        } finally {
            activeStream = null
        }
    }

    /** Applies a per-utterance voice and (optionally) the caller's speech rate. */
    private fun applyRequestOverrides(base: TtsSettings, request: SynthesisRequest): TtsSettings {
        var result = base

        val voiceName = request.voiceName
        if (!voiceName.isNullOrBlank() &&
            LocalVoiceKey.parse(voiceName) == null &&
            ServerVoiceKey.parse(voiceName) == null
        ) {
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

    /**
     * Every voice the engine can serve, installed or not.
     *
     * The framework caches this list and rejects `setVoice()` for a name that is
     * absent, so it is never narrowed to what is currently downloaded: a reading
     * app that pinned a local voice must still be able to select it — the
     * fallback in [onSynthesizeText] deals with the model being missing.
     */
    override fun onGetVoices(): MutableList<Voice> {
        val settings = store.load()
        val names = LinkedHashSet<String>()

        for (model in LocalModelCatalog.all) {
            for (voice in model.voices) {
                names.add(LocalVoiceKey.format(model.id, voice.id))
            }
        }

        val serverModel = settings.serverModel.ifBlank { TtsSettings.DEFAULT_LOCAL_MODEL }
        val serverVoice = settings.serverVoice.ifBlank { "default" }
        names.add(ServerVoiceKey.format(serverModel, serverVoice))

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
        if (settings.backend == TtsSettings.BACKEND_SERVER) {
            val model = settings.serverModel.ifBlank { TtsSettings.DEFAULT_LOCAL_MODEL }
            val voice = settings.serverVoice.ifBlank { "default" }
            return ServerVoiceKey.format(model, voice)
        }
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
        if (ServerVoiceKey.parse(name) != null) return TextToSpeech.SUCCESS
        return if (parseVoiceName(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    private fun createVoice(name: String): Voice {
        val server = ServerVoiceKey.parse(name)
        if (server != null) {
            return Voice(
                name,
                Locale.US,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                /* requiresNetwork = */ true,
                setOf("networkSynthesis", "serverSynthesis"),
            )
        }
        val local = LocalVoiceKey.parse(name)
        if (local != null) {
            val (model, voice) = LocalModelCatalog.voice(local.first, local.second)
                ?: return fallbackVoice(name)
            val locale = Locale.forLanguageTag(voice.language)
            val features = mutableSetOf("localSynthesis", model.family.label)
            if (!localModels.isVoiceReady(model, voice)) features.add("notInstalled")
            return Voice(
                name,
                if (locale.language.isBlank()) Locale.US else locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                /* requiresNetwork = */ false,
                features,
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
 * Order in which backends are tried for one utterance.
 *
 * The configured backend goes first; the rest follow so a missing API key, an
 * unreachable server or an uninstalled model degrades to a working voice instead
 * of surfacing "speech error" in the reading app.
 */
internal object BackendFallback {
    private val all = listOf(
        TtsSettings.BACKEND_LOCAL,
        TtsSettings.BACKEND_SERVER,
        TtsSettings.BACKEND_CLOUD,
    )

    fun order(preferred: String): List<String> =
        listOf(preferred) + all.filter { it != preferred }
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

/**
 * Framework voice names for a self-hosted server: `server|<model>/<voice>`.
 * Kept separate from `local|` so a reading app can pin either backend.
 */
object ServerVoiceKey {
    private const val PREFIX = "server"

    fun format(modelId: String, voiceId: String): String = "$PREFIX|$modelId/$voiceId"

    fun parse(name: String): Pair<String, String>? {
        if (!name.startsWith("$PREFIX|")) return null
        val rest = name.removePrefix("$PREFIX|")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val model = rest.substring(0, slash)
        val voice = rest.substring(slash + 1)
        if (model.isBlank() || voice.isBlank()) return null
        return model to voice
    }
}
