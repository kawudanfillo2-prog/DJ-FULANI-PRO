package com.djfulanipro

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decodes a short PCM preview with Android's native media decoder and estimates
 * tempo from transient/onset energy. This is an estimate, not a commercial
 * beat-grid/downbeat detector; the DJ can still adjust the beat marker.
 */
internal object BpmAnalyzer {
    data class Analysis(
        val bpm: Float,
        val beatZeroMs: Long,
        val waveform: FloatArray
    )

    fun analyze(context: Context, uri: Uri): Analysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) return null
            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val frameBuckets = ArrayList<Float>(90 * 100)
            var bucketEnergy = 0.0
            var bucketSamples = 0
            val samplesPerBucket = (sampleRate / 100).coerceAtLeast(1)
            var samplesInBucket = 0
            var inputDone = false
            var outputDone = false
            var decodedSamples = 0L
            val maxSamples = sampleRate.toLong() * 90L * channels
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0 || decodedSamples >= maxSamples) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val format = codec.outputFormat
                            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            if (encoding == 4) {
                                while (pcm.remaining() >= 4 && decodedSamples < maxSamples) {
                                    val value = pcm.float.coerceIn(-1f, 1f)
                                    bucketEnergy += value.toDouble() * value
                                    bucketSamples++
                                    samplesInBucket++
                                    decodedSamples++
                                    if (samplesInBucket >= samplesPerBucket * channels) {
                                        frameBuckets.add(sqrt(bucketEnergy / bucketSamples).toFloat())
                                        bucketEnergy = 0.0
                                        bucketSamples = 0
                                        samplesInBucket = 0
                                    }
                                }
                            } else {
                                while (pcm.remaining() >= 2 && decodedSamples < maxSamples) {
                                    val value = pcm.short / 32768f
                                    bucketEnergy += value.toDouble() * value
                                    bucketSamples++
                                    samplesInBucket++
                                    decodedSamples++
                                    if (samplesInBucket >= samplesPerBucket * channels) {
                                        frameBuckets.add(sqrt(bucketEnergy / bucketSamples).toFloat())
                                        bucketEnergy = 0.0
                                        bucketSamples = 0
                                        samplesInBucket = 0
                                    }
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (decodedSamples >= maxSamples) outputDone = true
                    }
                }
            }
            if (bucketSamples > 0) frameBuckets.add(sqrt(bucketEnergy / bucketSamples).toFloat())
            return estimate(frameBuckets.toFloatArray())
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun estimate(envelope: FloatArray): Analysis? {
        if (envelope.size < 500) return null
        val mean = envelope.average().toFloat()
        val onset = FloatArray(envelope.size)
        for (i in 2 until envelope.size) {
            val local = (envelope[i - 1] + envelope[i - 2]) * 0.5f
            onset[i] = max(0f, envelope[i] - local) +
                max(0f, envelope[i] - mean) * 0.18f
        }
        val maxLag = 100 // 60 BPM at 100 envelope frames/second
        val minLag = 33  // ~182 BPM
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var score = 0.0
            var normA = 0.0
            var normB = 0.0
            var i = lag
            while (i < onset.size) {
                val a = onset[i].toDouble()
                val b = onset[i - lag].toDouble()
                score += a * b
                normA += a * a
                normB += b * b
                i++
            }
            val normalized = score / (sqrt(normA * normB) + 1e-12)
            if (normalized > bestScore) {
                bestScore = normalized
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore < 0.08) return null
        val bpm = (6000f / bestLag).coerceIn(60f, 182f)

        // Find the first strong transient near the start as an initial beat-grid anchor.
        val threshold = max(mean * 0.12f, onset.maxOrNull()?.times(0.22f) ?: 0f)
        var first = -1
        for (i in 3 until minOf(onset.size, bestLag * 8)) {
            if (onset[i] >= threshold && onset[i] >= onset[i - 1] && onset[i] >= onset[i + 1]) {
                first = i
                break
            }
        }
        val beatZeroMs = if (first >= 0) first * 10L else 0L
        val peak = envelope.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
        val waveform = FloatArray(160) { index ->
            val start = index * envelope.size / 160
            val end = max(start + 1, (index + 1) * envelope.size / 160)
            var value = 0f
            for (j in start until minOf(end, envelope.size)) value = max(value, envelope[j])
            (value / peak).coerceIn(0f, 1f)
        }
        return Analysis(bpm, beatZeroMs, waveform)
    }
}
