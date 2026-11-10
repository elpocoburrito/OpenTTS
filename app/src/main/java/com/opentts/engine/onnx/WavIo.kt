package com.opentts.engine.onnx

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono WAV writer, used to hand real synthesis output to tests. */
object WavIo {

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)              // PCM header size
        buffer.putShort(1)             // PCM
        buffer.putShort(1)             // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)  // byte rate
        buffer.putShort(2)             // block align
        buffer.putShort(16)            // bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)

        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buffer.putShort(v.toShort())
        }

        file.parentFile?.mkdirs()
        file.writeBytes(buffer.array())
    }
}
