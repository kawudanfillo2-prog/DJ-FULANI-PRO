package com.djfulanipro

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class StemSeparationActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseButton: Button
    private lateinit var modelButton: Button
    private lateinit var separateButton: Button
    private var selectedUri: Uri? = null
    private var working = false
    private val cancelled = AtomicBoolean(false)

    private val modelFile: File
        get() = File(File(filesDir, "stem-models"), "htdemucs_6s.safetensors")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 9, 16)
        window.navigationBarColor = Color.rgb(5, 9, 16)
        buildScreen()
        refreshModelState()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }

    private fun action(value: String, onClick: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.rgb(5, 9, 16))
        }
        root.addView(text("DJ FULANI PRO • STEM SEPARATION", 18f))
        root.addView(text(
            "Raba waka zuwa drums, bass, other, vocals, guitar da piano.",
            11f, Color.rgb(45, 200, 255)
        ))
        root.addView(text(
            "Model Demucs v4.1.0 • kusan 55 MB. Za a sauke shi sau daya; ba ya cikin APK.",
            10f, Color.LTGRAY
        ))
        chooseButton = action("1 • ZABI WAKA") { pickAudio() }
        root.addView(chooseButton)
        modelButton = action("2 • SAUKE STEM MODEL (55 MB)") { downloadModel() }
        root.addView(modelButton)
        separateButton = action("3 • RABA WAKA") { separateSelectedTrack() }
        root.addView(separateButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
        ))
        status = text("Zaɓi waka, sannan ka sauke model idan ba ya cikin wayarka.", 11f, Color.LTGRAY)
        root.addView(status)
        root.addView(text("ABUBUWAN DA ZAKA SAMU", 13f, Color.rgb(45, 200, 255)))
        listOf(
            "DRUMS.wav — ganguna / kick / snare",
            "BASS.wav — bass line",
            "OTHER.wav — sauran kayan kida",
            "VOCALS.wav — murya",
            "GUITAR.wav — guitar",
            "PIANO.wav — piano",
            "INSTRUMENTAL.wav — duk kayan kida an haɗa su ba tare da vocals ba"
        ).forEach { root.addView(text("• $it", 10f, Color.WHITE)) }
        root.addView(text(
            "Za a adana su a Music / DJ FULANI PRO / Separated. Za ka iya ɗauko WAV ɗin daga Deck Load Audio.",
            10f, Color.LTGRAY
        ))
        root.addView(action("CANCEL") { cancelOperation() })
        root.addView(action("KOMA DJ MIXER") { finish() })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun refreshModelState() {
        val ready = modelFile.exists() && modelFile.length() > 50_000_000L
        modelButton.text = if (ready) "MODEL READY ✓ • SAKE SAUKEWA" else "2 • SAUKE STEM MODEL (55 MB)"
        separateButton.isEnabled = ready && selectedUri != null && !working
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_STEM_AUDIO)
    }

    @Deprecated("Uses Android document picker for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_STEM_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        try {
            contentResolver.takePersistableUriPermission(
                uri, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        chooseButton.text = "WAKA TA ZAƁA ✓"
        status.text = "An zaɓi waka. Sauke model, sannan ka danna Raba Waka."
        refreshModelState()
    }

    private fun beginWork() {
        working = true
        cancelled.set(false)
        chooseButton.isEnabled = false
        modelButton.isEnabled = false
        separateButton.isEnabled = false
    }

    private fun endWork() {
        working = false
        chooseButton.isEnabled = true
        modelButton.isEnabled = true
        refreshModelState()
    }

    private fun updateProgress(value: Float, message: String) {
        runOnUiThread {
            progress.progress = (value.coerceIn(0f, 1f) * 100f).toInt()
            status.text = message
        }
    }

    private fun downloadModel() {
        if (working) return
        beginWork()
        status.text = "Ana sauke Demucs model…"
        Thread {
            try {
                modelFile.parentFile?.mkdirs()
                val part = File(modelFile.parentFile, modelFile.name + ".part")
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode !in 200..299)
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("Cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            val fraction = if (total > 0) done.toFloat() / total else 0f
                            updateProgress(fraction, "Ana sauke model… ${done / (1024 * 1024)} MB")
                        }
                    }
                }
                connection.disconnect()
                val actualHash = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
                if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException("SHA-256 bai yi daidai ba; an soke model ɗin.")
                }
                if (modelFile.exists()) modelFile.delete()
                if (!part.renameTo(modelFile)) {
                    part.copyTo(modelFile, overwrite = true)
                    part.delete()
                }
                runOnUiThread {
                    progress.progress = 100
                    status.text = "MODEL READY ✓ • Yanzu za ka iya raba waka."
                    endWork()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Download bai yi nasara ba: ${e.message}"
                    endWork()
                }
            }
        }.start()
    }

    private fun separateSelectedTrack() {
        val uri = selectedUri ?: run { toast("Da farko zaɓi waka."); return }
        if (!modelFile.exists()) { toast("Da farko sauke model."); return }
        if (working) return
        beginWork()
        Thread {
            try {
                val workDir = File(cacheDir, "stem-work").apply { deleteRecursively(); mkdirs() }
                val rawFile = File(workDir, "input.f32")
                val preparedFile = File(workDir, "prepared.f32")
                val outputDir = File(
                    File(getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir, "DJ-FULANI-PRO"),
                    "stems"
                ).apply { deleteRecursively(); mkdirs() }

                updateProgress(0f, "Ana decode waƙa…")
                val decoded = PcmFileDecoder.decode(this, uri, rawFile) { fraction ->
                    updateProgress(fraction * 0.22f, "Ana decode waƙa…")
                }
                if (cancelled.get()) throw InterruptedException("Cancelled")

                NativeStemEngine.prepareInput(
                    rawFile.absolutePath,
                    decoded.sampleRate,
                    decoded.channels,
                    preparedFile.absolutePath
                ) { fraction, stage ->
                    updateProgress(0.22f + fraction * 0.15f, stage)
                    !cancelled.get()
                }
                if (cancelled.get()) throw InterruptedException("Cancelled")

                updateProgress(0.38f, "Ana loda Demucs model…")
                NativeStemEngine.loadModel(modelFile.absolutePath)
                val engineInfo = NativeStemEngine.computeInfo(gpu = true, computeThreads = 0)
                updateProgress(0.42f, "Engine: $engineInfo • Ana raba stems…")

                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                val threads = if (mem.totalMem >= 8L * 1024L * 1024L * 1024L) 2 else 1

                NativeStemEngine.separate(
                    preparedFile.absolutePath,
                    outputDir.absolutePath,
                    selected = setOf("drums", "bass", "other", "vocals", "guitar", "piano"),
                    threads = threads,
                    computeThreads = 0,
                    gpu = true,
                    listener = NativeStemEngine.ProgressListener { fraction, stage ->
                        updateProgress(0.42f + fraction * 0.52f, stage)
                        !cancelled.get()
                    }
                )
                if (cancelled.get()) throw InterruptedException("Cancelled")

                updateProgress(0.95f, "Ana haɗa instrumental mix…")
                val instrumental = File(outputDir, "Instrumental.wav")
                mixWavFiles(
                    listOf("drums.wav", "bass.wav", "other.wav", "guitar.wav", "piano.wav")
                        .map { File(outputDir, it) },
                    instrumental
                )

                val names = listOf(
                    "drums.wav", "bass.wav", "other.wav", "vocals.wav",
                    "guitar.wav", "piano.wav", "Instrumental.wav"
                )
                names.forEachIndexed { index, name ->
                    val file = File(outputDir, name)
                    if (file.exists()) {
                        publishToMusic(file)
                        updateProgress(0.95f + (index + 1) * 0.007f, "Ana adana ${file.name}…")
                    }
                }

                rawFile.delete()
                preparedFile.delete()
                runOnUiThread {
                    progress.progress = 100
                    status.text = "SEPARATION COMPLETE ✓ • An ajiye stems 7 a Music / DJ FULANI PRO / Separated."
                    endWork()
                    toast("Instrumental da vocals sun shirya.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Separation bai kammala ba: ${e.message}"
                    endWork()
                }
            }
        }.start()
    }

    private fun mixWavFiles(inputs: List<File>, output: File) {
        require(inputs.isNotEmpty() && inputs.all { it.exists() }) { "Wasu stem files sun ɓace." }
        val dataBytes = inputs.minOf { (it.length() - 44L).coerceAtLeast(0L) }
        require(dataBytes > 0L) { "Stem audio is empty." }
        val header = ByteArray(44)
        FileInputStream(inputs.first()).use { it.read(header) }
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.putInt(4, (36L + dataBytes).toInt())
        headerBuffer.putInt(40, dataBytes.toInt())

        val streams = inputs.map {
            BufferedInputStream(FileInputStream(it)).apply { skipFully(44L) }
        }
        try {
            BufferedOutputStream(FileOutputStream(output)).use { out ->
                out.write(header)
                val chunkBytes = 32 * 1024
                val buffers = Array(inputs.size) { ByteArray(chunkBytes) }
                var remaining = dataBytes.toInt()
                while (remaining > 0) {
                    val n = minOf(chunkBytes, remaining)
                    val sums = IntArray(n / 2)
                    for (i in streams.indices) {
                        readFully(streams[i], buffers[i], n)
                        val inputBuffer = ByteBuffer.wrap(buffers[i], 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        for (sample in sums.indices) sums[sample] += inputBuffer.short.toInt()
                    }
                    val mixed = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
                    for (sum in sums) mixed.putShort(sum.coerceIn(-32768, 32767).toShort())
                    out.write(mixed.array())
                    remaining -= n
                }
            }
        } finally {
            streams.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    private fun BufferedInputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                if (read() < 0) throw IllegalStateException("WAV header incomplete.")
                remaining--
            } else remaining -= skipped
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n < 0) throw IllegalStateException("Stem WAV ended unexpectedly.")
            offset += n
        }
    }

    private fun publishToMusic(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/DJ FULANI PRO/Separated")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not save ${file.name} to Music.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun cancelOperation() {
        cancelled.set(true)
        try { NativeStemEngine.cancel() } catch (_: Exception) { }
        status.text = "An aika soke aikin…"
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_STEM_AUDIO = 7701
        private const val MODEL_URL =
            "https://huggingface.co/adefossez/HTDemucs-6s/resolve/3c5ee475be622df764938de97e4281a7b07ffa58/5c90dfd2.safetensors"
        private const val MODEL_SHA256 =
            "d2a1745f0744725f1b8ca5bf469b67c651ea5ed1b52998cab033b2158609d411"
    }
}
