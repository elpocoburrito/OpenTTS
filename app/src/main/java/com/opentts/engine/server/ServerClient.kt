package com.opentts.engine.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** One model as advertised by the OpenTTS-SRV `/v1/models` endpoint. */
data class ServerModel(
    val id: String,
    val label: String,
    val family: String,
    val sampleRate: Int,
    val installed: Boolean,
    val voices: List<ServerVoice>,
)

data class ServerVoice(
    val id: String,
    val label: String,
    val language: String,
    val ready: Boolean,
)

/** A live synthesis response: raw PCM streaming plus the headers we need. */
class ServerStream(
    val input: InputStream,
    val sampleRate: Int,
    val model: String,
    val voice: String,
    private val connection: HttpURLConnection,
) : AutoCloseable {
    override fun close() {
        runCatching { input.close() }
        runCatching { connection.disconnect() }
    }
}

class ServerException(message: String) : Exception(message)

/**
 * Client for a self-hosted OpenTTS-SRV instance.
 *
 * The server speaks the OpenAI `/v1/audio/speech` shape; we ask for `pcm`
 * (16-bit mono, little endian) so the bytes can be forwarded straight into
 * SynthesisCallback without a decoder, and read the real rate from
 * `X-Sample-Rate`.
 */
class ServerClient(
    baseUrl: String,
    private val token: String,
    private val certs: ClientCertStore? = null,
    private val timeoutSeconds: Int = 120,
) {

    private val base: String = baseUrl.trimEnd('/')

    private fun open(path: String, method: String): HttpURLConnection {
        val connection = URL("$base$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = timeoutSeconds * 1000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json, audio/*")
        connection.setRequestProperty("User-Agent", "OpenTTS-NG")
        if (connection is HttpsURLConnection) {
            certs?.socketFactory?.let { connection.sslSocketFactory = it }
            // Keep SNI/hostname verification on by default.
            connection.setHostnameVerifier { hostname, session ->
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }
        return connection
    }

    /** GET /v1/models — throws [ServerException] with a readable reason. */
    fun listModels(): List<ServerModel> {
        val connection = open("/v1/models", "GET")
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw ServerException("HTTP $code from server: ${body.take(200)}")
            }
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            return buildList {
                for (i in 0 until data.length()) {
                    val model = data.getJSONObject(i)
                    val voicesJson = model.optJSONArray("voices") ?: JSONArray()
                    val voices = buildList {
                        for (j in 0 until voicesJson.length()) {
                            val v = voicesJson.getJSONObject(j)
                            add(
                                ServerVoice(
                                    id = v.optString("id"),
                                    label = v.optString("label", v.optString("id")),
                                    language = v.optString("language", "en-US"),
                                    ready = v.optBoolean("ready", true),
                                ),
                            )
                        }
                    }
                    add(
                        ServerModel(
                            id = model.optString("id"),
                            label = model.optString("label", model.optString("id")),
                            family = model.optString("family", ""),
                            sampleRate = model.optInt("sample_rate", 24000),
                            installed = model.optBoolean("installed", false),
                            voices = voices,
                        ),
                    )
                }
            }
        } catch (e: ServerException) {
            throw e
        } catch (e: Exception) {
            throw ServerException(e.message ?: "could not reach the server")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST /v1/audio/speech with `response_format=pcm`.
     * Blocking: call it from the synthesis thread.
     */
    fun openSpeech(text: String, model: String, voice: String?, speed: Float): ServerStream {
        val connection = open("/v1/audio/speech", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            if (!voice.isNullOrBlank()) put("voice", voice)
            put("response_format", "pcm")
            put("speed", speed.toDouble())
        }.toString().toByteArray()

        return try {
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ServerException("HTTP $code: ${body.take(200)}")
            }
            val sampleRate = connection.getHeaderField("X-Sample-Rate")?.toIntOrNull() ?: 24000
            Log.i(
                TAG,
                "server synthesis: model=$model voice=$voice rate=$sampleRate " +
                    "device=${connection.getHeaderField("X-Device")}",
            )
            ServerStream(
                input = connection.inputStream,
                sampleRate = sampleRate,
                model = model,
                voice = voice.orEmpty(),
                connection = connection,
            )
        } catch (e: Exception) {
            connection.disconnect()
            if (e is ServerException) throw e
            throw ServerException(e.message ?: "synthesis request failed")
        }
    }

    /** GET /health, used by the settings screen to verify URL + token. */
    fun health(): String {
        val connection = open("/health", "GET")
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ServerException("HTTP $code: ${body.take(160)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "ServerClient"
    }
}
