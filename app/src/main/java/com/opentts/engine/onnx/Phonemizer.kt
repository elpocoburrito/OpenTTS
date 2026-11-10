package com.opentts.engine.onnx

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Thin wrapper around the bundled espeak-ng library: text -> IPA phonemes.
 *
 * Every local model family here (Kokoro, Piper/VITS, Matcha) consumes phonemes
 * rather than raw graphemes, so this is the shared front half of the local
 * pipeline. espeak's data files (~14 MB, the 24 most useful languages) ship in
 * `assets/espeak-ng-data` and are unpacked into the app's files dir on first
 * use, because espeak reads them from a real filesystem path.
 */
object Phonemizer {

    private const val TAG = "Phonemizer"
    private const val DATA_DIR_NAME = "espeak-ng-data"

    @Volatile
    private var loaded = false

    @Volatile
    private var initialized = false

    /** True once espeak-ng has been initialised against its data directory. */
    val isReady: Boolean get() = initialized

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (initialized) return true
        return try {
            if (!loaded) {
                System.loadLibrary("opentts_tts")
                loaded = true
            }
            val dir = ensureDataDir(context)
            initialized = nativeInit(dir.absolutePath)
            if (!initialized) Log.e(TAG, "espeak-ng failed to initialise at $dir")
            initialized
        } catch (e: Throwable) {
            Log.e(TAG, "espeak-ng unavailable: ${e.message}", e)
            false
        }
    }

    /**
     * IPA phonemes for [text], phonemised with the espeak language [voice]
     * (e.g. "en-us", "de", "cmn"). Empty string when the engine is unavailable.
     */
    fun phonemes(context: Context, text: String, voice: String): String {
        if (text.isBlank()) return ""
        if (!ensure(context)) return ""
        return synchronized(this) {
            if (nativeSetVoice(voice) != 0) {
                Log.w(TAG, "espeak has no voice '$voice', falling back to en-us")
                nativeSetVoice("en-us")
            }
            nativeTextToPhonemes(text)
        }
    }

    fun version(): String = runCatching { nativeVersion() }.getOrDefault("unavailable")

    /** Copies `assets/espeak-ng-data` into filesDir once per app update. */
    private fun ensureDataDir(context: Context): File {
        val target = File(context.filesDir, DATA_DIR_NAME)
        val stamp = File(context.filesDir, ".espeak-stamp")
        val wanted = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

        if (target.isDirectory && stamp.isFile && stamp.readText() == wanted) return target

        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val assets = context.assets
        copyAssetTree(assets, DATA_DIR_NAME, target)
        stamp.writeText(wanted)
        Log.i(TAG, "Unpacked espeak-ng data to $target (${target.walkTopDown().count()} files)")
        return target
    }

    private fun copyAssetTree(assets: android.content.res.AssetManager, path: String, outDir: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // Leaf: an actual file.
            outDir.parentFile?.mkdirs()
            assets.open(path).use { input -> outDir.outputStream().use { input.copyTo(it) } }
            return
        }
        outDir.mkdirs()
        for (child in children) copyAssetTree(assets, "$path/$child", File(outDir, child))
    }

    private external fun nativeInit(dataPath: String): Boolean
    private external fun nativeSetVoice(voice: String): Int
    private external fun nativeTextToPhonemes(text: String): String
    private external fun nativeVersion(): String
}
