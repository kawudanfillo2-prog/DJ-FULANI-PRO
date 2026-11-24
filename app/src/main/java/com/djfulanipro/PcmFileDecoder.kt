package com.djfulanipro

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PcmFileDecoder {
    data class Result(val sampleRate: Int, val channels: Int)

    fun decode(context: Context, uri: Uri, outputFile: File, progress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; inputFormat = f; break }
            }
            if (track < 0 || inputFormat == null) {
                throw IllegalArgumentException("The selected file has no audio track.")
            }
            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing audio MIME type")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            outputFile.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(outputFile), 1024 * 1024).use { output ->
                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("Decoder input buffer unavailable")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val bytesPerSample =
                                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val samples = pcm.remaining() / bytesPerSample
                                val converted = ByteBuffer.allocate(samples * 4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                repeat(samples) {
                                    val value = if (encoding == AudioFormat.ENCODING_PCM_FLOAT)
                                        pcm.float else pcm.short / 32768f
                                    converted.putFloat(value.coerceIn(-1f, 1f))
                                }
                                output.write(converted.array(), 0, converted.position())
                                if (durationUs > 0L && info.presentationTimeUs >= 0L) {
                                    progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            return Result(rate, channels)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }
}
