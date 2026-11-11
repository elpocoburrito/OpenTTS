package com.opentts.engine.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Builds ONNX Runtime sessions with the settings that actually matter on a phone:
 * XNNPACK where the runtime supports it, a measured intra-op thread count, and
 * an inter-op count of 1 (the graphs are linear chains, extra inter-op threads
 * only add scheduling).
 *
 * XNNPACK is the difference between "usable" and "unusable" for conv-heavy TTS
 * graphs on ARM: it uses fp16/neon kernels the default CPU EP does not.
 */
internal object SessionFactory {

    private const val TAG = "SessionFactory"

    @Volatile
    var xnnpackAvailable: Boolean = false
        private set

    fun create(path: File, threads: Int, useXnnpack: Boolean = true): OrtSession {
        require(path.exists()) { "missing model ${path.absolutePath}" }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads.coerceIn(1, 8))
            setInterOpNumThreads(1)
            if (useXnnpack) addXnnpack(this, threads)
        }
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(path.absolutePath, options)
        Log.i(
            TAG,
            "session ${path.name}: threads=${threads.coerceIn(1, 8)} " +
                "xnnpack=$xnnpackAvailable abi=${Build.SUPPORTED_ABIS.firstOrNull()}",
        )
        return session
    }

    private fun addXnnpack(options: OrtSession.SessionOptions, threads: Int) {
        // x86 emulators have no XNNPACK build in some AARs; never let an
        // unsupported provider stop the engine from running.
        if (!isArm()) return
        runCatching {
            options.addXnnpack(
                mapOf(
                    "intra_op_num_threads" to threads.coerceIn(1, 8).toString(),
                    "max_num_threads" to threads.coerceIn(1, 8).toString(),
                ),
            )
            xnnpackAvailable = true
        }.onFailure {
            Log.w(TAG, "XNNPACK not available: ${it.message}")
            xnnpackAvailable = false
        }
    }

    private fun isArm(): Boolean =
        Build.SUPPORTED_ABIS.any { it.startsWith("arm") || it.startsWith("aarch") }
}
