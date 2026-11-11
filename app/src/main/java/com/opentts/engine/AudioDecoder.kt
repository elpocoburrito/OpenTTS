package com.opentts.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.SynthesisCallback
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Thrown internally when synthesis is cancelled or the callback rejects data. */
internal class SynthesisCancelled : Exception("Synthesis cancelled")

/**
 * Feeds audio to a [SynthesisCallback].
 *
 * PCM responses are streamed straight through a [PcmSink], which keeps sample
 * alignment intact even when the source hands us odd-length reads (a dropped
 * trailing byte used to byte-swap every later sample — loud white noise). MP3
 * responses are decoded with [MediaExtractor] + [MediaCodec], which parses the
 * container correctly and reports the real sample rate and channel count.
 */
object AudioDecoder {

    fun streamPcm(
        input: InputStream,
        sampleRate: Int,
        channels: Int,
        callback: SynthesisCallback,
        isCancelled: () -> Boolean,
    ) {
        val target = CallbackTarget(callback)
        val sink = PcmSink(target, sampleRate, channels, isCancelled)
        val frameBytes = 2 * channels
        val bufferSize = (minOf(target.maxBufferSize(), 16 * 1024) / frameBytes).coerceAtLeast(1) * frameBytes
        val buffer = ByteArray(bufferSize)

        while (true) {
            if (isCancelled()) throw SynthesisCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (!sink.write(buffer, read)) {
                // start() was refused or the caller cancelled mid-stream.
                if (isCancelled()) throw SynthesisCancelled()
                return
            }
        }
        sink.finish()
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
            val sink = PcmSink(CallbackTarget(callback), sampleRate, channelCount, isCancelled)

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
                        if (info.size > 0) {
                            val outBuffer = codec.getOutputBuffer(outIndex)!!
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            outBuffer.get(bytes)
                            if (!sink.write(bytes, bytes.size) && isCancelled()) {
                                throw SynthesisCancelled()
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (sink.isStarted) sink.finish() else sink.error()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

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
