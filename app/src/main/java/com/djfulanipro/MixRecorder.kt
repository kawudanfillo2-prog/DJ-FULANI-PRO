package com.djfulanipro

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

internal class MixRecorder(
    private val context: Context,
    private val uri: Uri,
    private val onStopped: (Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private var bytesWritten = 0L

    fun start(): Boolean {
        if (!active.compareAndSet(false, true)) return false
        worker = Thread({ recordLoop() }, "DJ-FULANI-master-recorder").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        active.set(false)
        try { worker?.join(4000) } catch (_: InterruptedException) { }
    }

    private fun recordLoop() {
        var pfd: ParcelFileDescriptor? = null
        var output: FileOutputStream? = null
        var channel: FileChannel? = null
        val sampleRate = try { NativeAudioBridge.nativeGetOutputSampleRate() } catch (_: Exception) { 48000 }
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Could not open the selected WAV file.")
            output = FileOutputStream(pfd.fileDescriptor)
            channel = output.channel
            channel.truncate(0L)
            channel.position(0L)
            writeFully(channel, ByteBuffer.wrap(ByteArray(44)))
            bytesWritten = 0L
            NativeAudioBridge.nativeStartRecording()

            val pcm = FloatArray(2048 * 2)
            while (active.get()) {
                val frames = NativeAudioBridge.nativeReadRecording(pcm, 2048)
                if (frames > 0) {
                    writePcm16(channel, pcm, frames)
                } else {
                    try { Thread.sleep(4) } catch (_: InterruptedException) { }
                }
            }

            NativeAudioBridge.nativeStopRecording()
            while (true) {
                val frames = NativeAudioBridge.nativeReadRecording(pcm, 2048)
                if (frames <= 0) break
                writePcm16(channel, pcm, frames)
            }

            channel.position(0L)
            writeFully(channel, makeWavHeader(bytesWritten, sampleRate))
            channel.truncate(44L + bytesWritten)
            channel.force(true)
            active.set(false)
            onStopped(bytesWritten)
        } catch (e: Exception) {
            active.set(false)
            try { NativeAudioBridge.nativeStopRecording() } catch (_: Exception) { }
            onError(e.message ?: "Recording failed.")
        } finally {
            try { channel?.close() } catch (_: Exception) { }
            try { output?.close() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
            worker = null
        }
    }

    private fun writePcm16(channel: FileChannel, pcm: FloatArray, frames: Int) {
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * 2) {
            val sample = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data.putShort(sample.toShort())
        }
        data.flip()
        writeFully(channel, data)
        bytesWritten += frames.toLong() * 4L
    }

    private fun makeWavHeader(dataBytes: Long, sampleRate: Int): ByteBuffer {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataBytes).coerceAtMost(0xffffffffL).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(2)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 4)
        header.putShort(4)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.coerceAtMost(0xffffffffL).toInt())
        header.flip()
        return header
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }
}
