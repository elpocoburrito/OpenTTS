package com.opentts.engine

import android.content.Context
import android.content.SharedPreferences

/** Immutable snapshot of the engine configuration. */
data class TtsSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val format: String = FORMAT_MP3,
    val sampleRate: Int = 24000,
    val channels: Int = 1,
    val speed: Float = 1.0f,
    val instructions: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val timeoutSec: Int = 60,
    val useClientRate: Boolean = true,
    /** Cached JSON catalog of TTS models (see ModelsRepository). */
    val catalogJson: String = "",
    /** "cloud" (OpenRouter) or "local" (on-device ONNX). */
    val backend: String = BACKEND_CLOUD,
    /** Installed local model id, see LocalModelCatalog. */
    val localModelId: String = DEFAULT_LOCAL_MODEL,
    /** Voice id inside that model. */
    val localVoiceId: String = DEFAULT_LOCAL_VOICE,
) {
    companion object {
        const val DEFAULT_MODEL = "hexgrad/kokoro-82m"
        const val DEFAULT_VOICE = "af_heart"
        const val FORMAT_MP3 = "mp3"
        const val FORMAT_PCM = "pcm"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        const val BACKEND_CLOUD = "cloud"
        const val BACKEND_LOCAL = "local"
        const val DEFAULT_LOCAL_MODEL = "kokoro-82m"
        const val DEFAULT_LOCAL_VOICE = "af_heart"
    }
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TtsSettings = TtsSettings(
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, TtsSettings.DEFAULT_MODEL) ?: TtsSettings.DEFAULT_MODEL,
        voice = prefs.getString(KEY_VOICE, TtsSettings.DEFAULT_VOICE) ?: TtsSettings.DEFAULT_VOICE,
        format = prefs.getString(KEY_FORMAT, TtsSettings.FORMAT_MP3) ?: TtsSettings.FORMAT_MP3,
        sampleRate = prefs.getInt(KEY_SAMPLE_RATE, 24000),
        channels = prefs.getInt(KEY_CHANNELS, 1),
        speed = prefs.getFloat(KEY_SPEED, 1.0f),
        instructions = prefs.getString(KEY_INSTRUCTIONS, "") ?: "",
        baseUrl = prefs.getString(KEY_BASE_URL, TtsSettings.DEFAULT_BASE_URL) ?: TtsSettings.DEFAULT_BASE_URL,
        timeoutSec = prefs.getInt(KEY_TIMEOUT, 60),
        useClientRate = prefs.getBoolean(KEY_USE_CLIENT_RATE, true),
        catalogJson = prefs.getString(KEY_CATALOG, "") ?: "",
        backend = prefs.getString(KEY_BACKEND, TtsSettings.BACKEND_CLOUD) ?: TtsSettings.BACKEND_CLOUD,
        localModelId = prefs.getString(KEY_LOCAL_MODEL, TtsSettings.DEFAULT_LOCAL_MODEL)
            ?: TtsSettings.DEFAULT_LOCAL_MODEL,
        localVoiceId = prefs.getString(KEY_LOCAL_VOICE, TtsSettings.DEFAULT_LOCAL_VOICE)
            ?: TtsSettings.DEFAULT_LOCAL_VOICE,
    )

    fun save(settings: TtsSettings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_VOICE, settings.voice)
            .putString(KEY_FORMAT, settings.format)
            .putInt(KEY_SAMPLE_RATE, settings.sampleRate)
            .putInt(KEY_CHANNELS, settings.channels)
            .putFloat(KEY_SPEED, settings.speed)
            .putString(KEY_INSTRUCTIONS, settings.instructions)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putInt(KEY_TIMEOUT, settings.timeoutSec)
            .putBoolean(KEY_USE_CLIENT_RATE, settings.useClientRate)
            .putString(KEY_CATALOG, settings.catalogJson)
            .putString(KEY_BACKEND, settings.backend)
            .putString(KEY_LOCAL_MODEL, settings.localModelId)
            .putString(KEY_LOCAL_VOICE, settings.localVoiceId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "opentts"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_FORMAT = "format"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SPEED = "speed"
        private const val KEY_INSTRUCTIONS = "instructions"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_USE_CLIENT_RATE = "use_client_rate"
        private const val KEY_CATALOG = "catalog_json"
        private const val KEY_BACKEND = "backend"
        private const val KEY_LOCAL_MODEL = "local_model"
        private const val KEY_LOCAL_VOICE = "local_voice"
    }
}
