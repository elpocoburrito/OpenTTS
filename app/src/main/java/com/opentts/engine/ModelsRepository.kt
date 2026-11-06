package com.opentts.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A text-to-speech model exposed by OpenRouter. */
data class TtsModel(
    val id: String,
    val name: String,
    val voices: List<String>,
)

/**
 * Loads the list of TTS-capable models from OpenRouter's models endpoint and
 * serialises it to a small cache stored in preferences. The catalogue is used
 * both by the settings UI and by the engine's voice list.
 */
object ModelsRepository {

    private const val MODELS_PATH = "/models?output_modalities=speech"

    fun fetchCatalog(baseUrl: String, apiKey: String): List<TtsModel> {
        val url = URL(baseUrl.trimEnd('/') + MODELS_PATH)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code: ${body.take(300)}")
            }
            return parseApiResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Parses the `{"data":[...]}` response of the models endpoint. */
    fun parseApiResponse(json: String): List<TtsModel> {
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        return parseArray(root.optJSONArray("data") ?: JSONArray())
    }

    /** Parses the compact cached form (a bare JSON array). */
    fun fromJson(json: String): List<TtsModel> {
        if (json.isBlank()) return emptyList()
        return runCatching { parseArray(JSONArray(json)) }.getOrDefault(emptyList())
    }

    fun toJson(models: List<TtsModel>): String {
        val array = JSONArray()
        for (model in models) {
            array.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("supported_voices", JSONArray(model.voices))
                }
            )
        }
        return array.toString()
    }

    private fun parseArray(array: JSONArray): List<TtsModel> {
        val result = ArrayList<TtsModel>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val name = obj.optString("name").ifBlank { id }
            val voices = ArrayList<String>()
            obj.optJSONArray("supported_voices")?.let { voiceArray ->
                for (j in 0 until voiceArray.length()) {
                    val voice = voiceArray.optString(j)
                    if (voice.isNotBlank()) voices.add(voice)
                }
            }
            result.add(TtsModel(id = id, name = name, voices = voices))
        }
        result.sortBy { it.name.lowercase() }
        return result
    }
}
