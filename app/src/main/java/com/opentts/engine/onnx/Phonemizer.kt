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
    private const val CACHE_ENTRIES = 256

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
            loadLibrary()
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
     * Small LRU of recent phonemisations. Reading apps repeat short strings
     * (menu items, "Chapter 4", page headers) and espeak costs a JNI round trip
     * plus dictionary lookups each time.
     */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_ENTRIES
    }

    /**
     * IPA phonemes for [text], phonemised with the espeak language [voice]
     * (e.g. "en-us", "de", "cmn"). Empty string when the engine is unavailable.
     */
    fun phonemes(context: Context, text: String, voice: String): String {
        if (text.isBlank()) return ""

        val key = voice + "\u0000" + text
        synchronized(cache) { cache[key] }?.let { return it }

        if (!ensure(context)) return ""
        val result = synchronized(this) {
            if (nativeSetVoice(voice) != 0) {
                Log.w(TAG, "espeak has no voice '$voice', falling back to en-us")
                nativeSetVoice("en-us")
            }
            nativeTextToPhonemes(text)
        }
        if (result.isNotEmpty()) synchronized(cache) { cache[key] = result }
        return result
    }

    /** Exposed for the settings screen: number of cached phonemisations. */
    fun cacheSize(): Int = synchronized(cache) { cache.size }

    /**
     * espeak's version string. Only the shared library is loaded (not its 14 MB
     * data directory), so this is safe to call from the settings UI.
     */
    fun version(): String = runCatching {
        loadLibrary()
        nativeVersion()
    }.getOrDefault("unavailable")

    @Synchronized
    private fun loadLibrary() {
        if (!loaded) {
            System.loadLibrary("opentts_tts")
            loaded = true
        }
    }

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
