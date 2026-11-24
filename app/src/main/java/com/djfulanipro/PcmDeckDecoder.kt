package com.djfulanipro

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class PcmDeckDecoder(
    private val context: Context,
    private val deckIndex: Int,
    private val uri: Uri,
    private val requestedStartMs: Long,
    private val onReady: (Long, Long) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ decodeLoop() }, "DJ-FULANI-deck-$deckIndex").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
        }
        worker = null
    }

    private fun decodeLoop() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    inputFormat = format
                    break
                }
            }
            if (track < 0 || inputFormat == null) {
                onError("No audio stream found.")
                return
            }

            extractor.selectTrack(track)
            if (requestedStartMs > 0L) {
                extractor.seekTo(requestedStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val extractorStartMs = max(0L, extractor.sampleTime / 1000L)
            val timelineStartMs = if (requestedStartMs > 0L) requestedStartMs else extractorStartMs

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing MIME")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val duration = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                0L
            }
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            NativeAudioBridge.nativeSetDeckSourceInfo(deckIndex, rate, duration, timelineStartMs)
            onReady(duration, timelineStartMs)

            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val requestedStartUs = requestedStartMs.coerceAtLeast(0L) * 1000L

            while (running.get() && !outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("No codec input buffer")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        NativeAudioBridge.nativeSetDeckSourceInfo(
                            deckIndex, rate, duration, timelineStartMs
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0 && running.get()) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPerSample =
                                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = pcm.remaining() / (bytesPerSample * channels)

                            if (frames > 0) {
                                // MediaExtractor seeks to a preceding compressed-audio sync
                                // frame. Trim decoded PCM up to the exact requested time;
                                // otherwise SYNC can start several milliseconds early.
                                val deltaUs = (requestedStartUs - info.presentationTimeUs)
                                    .coerceAtLeast(0L)
                                val skipFrames = if (requestedStartMs > 0L && deltaUs > 0L) {
                                    ((deltaUs * rate + 999_999L) / 1_000_000L)
                                        .toInt().coerceIn(0, frames)
                                } else {
                                    0
                                }

                                val remainingFrames = frames - skipFrames
                                if (remainingFrames > 0) {
                                    val stereo = FloatArray(remainingFrames * 2)
                                    for (outFrame in 0 until remainingFrames) {
                                        val sourceFrame = outFrame + skipFrames
                                        val base = sourceFrame * channels
                                        val left = read(pcm, base, encoding)
                                        val right = if (channels == 1) left
                                            else read(pcm, base + 1, encoding)
                                        stereo[outFrame * 2] = left
                                        stereo[outFrame * 2 + 1] = right
                                    }

                                    var sent = 0
                                    while (running.get() && sent < remainingFrames) {
                                        val count = NativeAudioBridge.nativePushPcm(
                                            deckIndex, stereo, sent * 2, remainingFrames - sent
                                        )
                                        if (count > 0) {
                                            sent += count
                                        } else {
                                            try {
                                                Thread.sleep(2)
                                            } catch (_: InterruptedException) {
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        outputDone =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (running.get() && outputDone) {
                NativeAudioBridge.nativeFinishDeck(deckIndex)
                onFinished()
            }
        } catch (error: Exception) {
            if (running.get()) onError(error.message ?: "Audio decoder failed.")
        } finally {
            running.set(false)
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun read(buffer: java.nio.ByteBuffer, index: Int, encoding: Int): Float {
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            buffer.getFloat(index * 4).coerceIn(-1f, 1f)
        } else {
            buffer.getShort(index * 2) / 32768f
        }
    }
}
