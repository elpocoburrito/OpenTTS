package com.opentts.engine

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * A live audio response from OpenRouter's `/audio/speech` endpoint. The body is
 * a raw audio byte stream (MP3 or PCM), not JSON. Closing this disconnects the
 * underlying HTTP call, which is what allows [OpenRouterTtsService.onStop] to
 * cancel an in-flight synthesis from another thread.
 */
class SpeechStream(
    private val connection: HttpURLConnection,
    val input: InputStream,
    val contentType: String?,
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        runCatching { connection.disconnect() }
    }
}

/** Non-2xx response from OpenRouter, carrying the status code and error body. */
class OpenRouterException(
    val code: Int,
    val body: String,
) : IOException("OpenRouter HTTP $code: ${body.take(500)}")

class OpenRouterClient(private val settings: TtsSettings) {

    @Throws(IOException::class)
    fun openSpeech(text: String): SpeechStream {
        val wantsSpeed = abs(settings.speed - 1.0f) > 0.001f
        val sendSpeed = wantsSpeed && settings.model !in speedUnsupportedModels

        return try {
            request(text, includeSpeed = sendSpeed)
        } catch (e: OpenRouterException) {
            // Several providers reject a non-default `speed` with HTTP 400.
            // Retry once without it and remember the model so we skip it next time.
            if (sendSpeed && e.code == 400 && e.body.contains("speed", ignoreCase = true)) {
                Log.w(TAG, "${settings.model} rejected speed; retrying without it")
                speedUnsupportedModels.add(settings.model)
                request(text, includeSpeed = false)
            } else {
                throw e
            }
        }
    }

    private fun request(text: String, includeSpeed: Boolean): SpeechStream {
        if (settings.apiKey.isBlank()) {
            throw IOException("OpenRouter API key is not set. Open OpenTTS and add your key.")
        }

        val url = URL(settings.baseUrl.trimEnd('/') + "/audio/speech")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = settings.timeoutSec.coerceIn(5, 300) * 1000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/*, application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
            setRequestProperty("HTTP-Referer", "https://github.com/opentts/OpenTTS")
            setRequestProperty("X-OpenRouter-Title", "OpenTTS")
        }

        val body = buildBody(settings, text, includeSpeed).toByteArray(Charsets.UTF_8)
        try {
            connection.outputStream.use { it.write(body) }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            val errorBody = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            connection.disconnect()
            throw OpenRouterException(code, errorBody)
        }

        return SpeechStream(connection, connection.inputStream, connection.contentType)
    }

    companion object {
        private const val TAG = "OpenRouterClient"

        /** Models known to reject a non-default `speed` (cached for the process lifetime). */
        private val speedUnsupportedModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun buildBody(settings: TtsSettings, text: String, includeSpeed: Boolean): String {
            val json = JSONObject()
            json.put("model", settings.model)
            json.put("input", text)
            if (settings.voice.isNotBlank()) json.put("voice", settings.voice)
            if (settings.instructions.isNotBlank()) json.put("instructions", settings.instructions)
            json.put("response_format", settings.format)
            // Many providers reject a non-default speed, so only include it when
            // it actually differs from 1.0 (and the model has not rejected it).
            if (includeSpeed && abs(settings.speed - 1.0f) > 0.001f) {
                json.put("speed", settings.speed.toDouble())
            }
            return json.toString()
        }
    }
}
