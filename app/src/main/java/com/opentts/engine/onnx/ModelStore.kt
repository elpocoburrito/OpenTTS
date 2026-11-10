package com.opentts.engine.onnx

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs and removes local models. Nothing is bundled in the APK: a model is a
 * set of files downloaded from Hugging Face into `filesDir/models/<id>/`, plus an
 * optional per-voice payload (Kokoro's 510x256 style matrices).
 */
class ModelStore(private val context: Context) {

    data class Progress(val received: Long, val total: Long, val label: String) {
        val fraction: Float
            get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    fun modelDir(modelId: String): File = File(context.filesDir, "models/$modelId")

    fun modelFile(model: LocalModelSpec, name: String): File = File(modelDir(model.id), name)

    fun voiceFile(model: LocalModelSpec, voice: LocalVoiceSpec): File? =
        voice.remote?.let { modelFile(model, it.name) }

    /** True when every base file of the model is on disk. */
    fun isInstalled(model: LocalModelSpec): Boolean =
        model.files.all { present(modelFile(model, it.name), it.bytes) }

    fun isVoiceReady(model: LocalModelSpec, voice: LocalVoiceSpec): Boolean {
        if (!isInstalled(model)) return false
        val remote = voice.remote ?: return true
        return present(modelFile(model, remote.name), remote.bytes)
    }

    /**
     * A file counts as installed when it is non-empty and, if the catalogue
     * knows the size, at least that large. `RemoteFile.bytes == 0` means "size
     * unknown" (e.g. small config JSONs), not "must be empty".
     */
    private fun present(file: File, expectedBytes: Long): Boolean {
        val length = file.length()
        return length > 0 && (expectedBytes <= 0 || length >= expectedBytes)
    }

    fun installedBytes(model: LocalModelSpec): Long =
        modelDir(model.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun install(model: LocalModelSpec) = withContext(Dispatchers.IO) {
        modelDir(model.id).mkdirs()
        val total = model.files.sumOf { it.bytes }
        var done = 0L
        for (file in model.files) {
            download(model.id, file, modelFile(model, file.name), done, total)
            done += file.bytes
        }
        update(model.id, null)
        Log.i(TAG, "Installed ${model.id} (${installedBytes(model)} bytes on disk)")
    }

    suspend fun ensureVoice(model: LocalModelSpec, voice: LocalVoiceSpec) = withContext(Dispatchers.IO) {
        val remote = voice.remote ?: return@withContext
        val target = modelFile(model, remote.name)
        if (present(target, remote.bytes)) return@withContext
        target.parentFile?.mkdirs()
        download(model.id, remote, target, 0, remote.bytes)
        update(model.id, null)
    }

    fun delete(model: LocalModelSpec): Boolean {
        update(model.id, null)
        return modelDir(model.id).deleteRecursively()
    }

    private fun update(modelId: String, progress: Progress?) {
        _progress.value = if (progress == null) _progress.value - modelId
        else _progress.value + (modelId to progress)
    }

    private fun download(
        modelId: String,
        file: RemoteFile,
        target: File,
        alreadyDone: Long,
        grandTotal: Long,
    ) {
        if (present(target, file.bytes)) return

        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()

        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenTTS-NG")
            setRequestProperty("Accept", "*/*")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${file.url}")
            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: file.bytes
            val total = if (grandTotal > 0) grandTotal else alreadyDone + declared
            var received = 0L

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        update(modelId, Progress(alreadyDone + received, total, file.name))
                    }
                }
            }
            if (!temp.renameTo(target)) throw IOException("Could not move ${file.name} into place")
            Log.i(TAG, "Downloaded ${file.name} ($received bytes)")
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private const val TAG = "ModelStore"
    }
}
