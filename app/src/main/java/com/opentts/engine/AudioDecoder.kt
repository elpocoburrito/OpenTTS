package com.opentts.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Thrown internally when synthesis is cancelled or the callback rejects data. */
internal class SynthesisCancelled : Exception("Synthesis cancelled")

/**
 * Feeds audio to a [SynthesisCallback].
 *
 * PCM responses are streamed straight through. MP3 responses are decoded with
 * [MediaExtractor] + [MediaCodec], which correctly parses the container (ID3
 * tags and frame boundaries) and reports the real sample rate and channel
 * count instead of us guessing them.
 */
object AudioDecoder {

    fun streamPcm(
        input: InputStream,
        sampleRate: Int,
        channels: Int,
        callback: SynthesisCallback,
        isCancelled: () -> Boolean,
    ) {
        val maxBuffer = callback.maxBufferSize()
        val buffer = ByteArray(minOf(maxBuffer, 16 * 1024))
        var started = false
        while (true) {
            if (isCancelled()) throw SynthesisCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (!started) {
                if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels) !=
                    TextToSpeech.SUCCESS
                ) {
                    return
                }
                started = true
            }
            emit(callback, buffer, read, isCancelled)
        }
        if (started) callback.done()
    }

    fun decodeFileToPcm(
        file: File,
        callback: SynthesisCallback,
        isCancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = format
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                callback.error()
                return
            }

            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = trackFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)

            var started = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (isCancelled()) throw SynthesisCancelled()

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        if (!started) {
                            if (callback.start(
                                    sampleRate,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    channelCount,
                                ) != TextToSpeech.SUCCESS
                            ) {
                                return
                            }
                            started = true
                        }
                        if (info.size > 0) {
                            val outBuffer = codec.getOutputBuffer(outIndex)!!
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            outBuffer.get(bytes)
                            emit(callback, bytes, bytes.size, isCancelled)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (started) callback.done() else callback.error()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun emit(
        callback: SynthesisCallback,
        data: ByteArray,
        length: Int,
        isCancelled: () -> Boolean,
    ) {
        val max = callback.maxBufferSize()
        var offset = 0
        while (offset < length) {
            if (isCancelled()) throw SynthesisCancelled()
            // Keep 16-bit sample alignment.
            val chunk = (minOf(max, length - offset) / 2) * 2
            if (chunk <= 0) break
            if (callback.audioAvailable(data, offset, chunk) != TextToSpeech.SUCCESS) {
                throw SynthesisCancelled()
            }
            offset += chunk
        }
    }

    private fun SynthesisCallback.maxBufferSize(): Int =
        getMaxBufferSize().takeIf { it in 2..(1 shl 20) } ?: 4096

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}

/** Copies [this] stream to [out], aborting if [isCancelled] becomes true. */
internal fun InputStream.copyToCancellable(out: OutputStream, isCancelled: () -> Boolean) {
    val buffer = ByteArray(16 * 1024)
    while (true) {
        if (isCancelled()) throw SynthesisCancelled()
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
    }
}
