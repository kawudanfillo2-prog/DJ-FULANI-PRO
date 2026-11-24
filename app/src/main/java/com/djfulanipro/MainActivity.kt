package com.djfulanipro

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.Cursor
import android.provider.OpenableColumns
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private data class DeckUi(
        val title: TextView,
        val status: TextView,
        val playButton: Button,
        val seek: SeekBar,
        var player: MediaPlayer? = null,
        var prepared: Boolean = false,
        var dragging: Boolean = false,
        var gain: Float = 0.85f
    )

    private val decks = ArrayList<DeckUi>(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var masterGain = 0.9f
    private var crossfader = 0.5f
    private var pendingDeck = -1

    private val progressTask = object : Runnable {
        override fun run() {
            decks.forEach { deck ->
                val player = deck.player
                if (deck.prepared && player != null && !deck.dragging) {
                    try {
                        val duration = player.duration.coerceAtLeast(1)
                        deck.seek.max = duration
                        deck.seek.progress = player.currentPosition.coerceIn(0, duration)
                    } catch (_: IllegalStateException) { }
                }
            }
            mainHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 18)
        window.navigationBarColor = Color.rgb(7, 11, 18)
        buildConsole()
        mainHandler.post(progressTask)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 11f
            setOnClickListener { action() }
        }

    private fun buildConsole() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(7, 11, 18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("DJ FULANI PRO", 22f), LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(label("4-DECK MIXER  •  PHASE 2", 10f, Color.rgb(35, 205, 255)))
        root.addView(header)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (column in 0..1) {
                val index = row * 2 + column
                rowLayout.addView(createDeck(index), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    width = 0
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                    weight = 1f
                    setMargins(dp(4), dp(3), dp(4), dp(3))
                })
            }
            grid.addView(rowLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        root.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(createMasterStrip())
        setContentView(root)
    }

    private fun createDeck(index: Int): View {
        val accent = when (index) {
            0 -> Color.rgb(0, 190, 255)
            1 -> Color.rgb(255, 155, 45)
            2 -> Color.rgb(155, 115, 255)
            else -> Color.rgb(40, 220, 155)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(24, 34, 49))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("DECK ${'A' + index}", 17f, accent),
            LinearLayout.LayoutParams(0, dp(30), 1f))
        top.addView(button("＋ LOAD") { openAudioPicker(index) })
        panel.addView(top)

        val title = label("No track loaded", 11f, Color.LTGRAY)
        panel.addView(title)
        val status = label("READY  •  BPM --", 9f, accent)
        panel.addView(status)

        val seek = SeekBar(this).apply { max = 1000; progress = 0 }
        panel.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = button("PLAY") { toggleDeck(index) }
        controls.addView(play, LinearLayout.LayoutParams(0, dp(38), 1f))
        controls.addView(button("STOP") { stopDeck(index) },
            LinearLayout.LayoutParams(0, dp(38), 1f))
        panel.addView(controls)

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeRow.addView(label("VOL", 9f, Color.LTGRAY))
        val volume = SeekBar(this).apply { max = 100; progress = 85 }
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, dp(24), 1f))
        panel.addView(volumeRow)

        val deck = DeckUi(title, status, play, seek)
        decks.add(deck)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { deck.dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                deck.dragging = false
                if (deck.prepared) try {
                    deck.player?.seekTo(deck.seek.progress)
                } catch (_: IllegalStateException) { }
            }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { }
        })
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                deck.gain = progress / 100f
                applyDeckVolumes()
            }
        })
        return panel
    }

    private fun createMasterStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setBackgroundColor(Color.rgb(16, 24, 36))
        }
        val master = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        master.addView(label("MASTER", 9f))
        val masterBar = SeekBar(this).apply { max = 100; progress = 90 }
        masterBar.setOnSeekBarChangeListener(simpleSeek {
            masterGain = it / 100f
            applyDeckVolumes()
        })
        master.addView(masterBar, LinearLayout.LayoutParams(dp(115), dp(28)))
        strip.addView(master)

        val cross = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        cross.addView(label("CROSSFADER   A  ◀────●────▶  B", 9f, Color.rgb(45, 205, 255)))
        val crossBar = SeekBar(this).apply { max = 100; progress = 50 }
        crossBar.setOnSeekBarChangeListener(simpleSeek {
            crossfader = it / 100f
            applyDeckVolumes()
        })
        cross.addView(crossBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        strip.addView(cross, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return strip
    }

    private fun simpleSeek(onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onValue(progress)
            }
        }

    private fun openAudioPicker(deckIndex: Int) {
        pendingDeck = deckIndex
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val index = pendingDeck
        if (index !in 0..3) return
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) { }
        loadTrack(index, uri)
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "Audio track"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) name = cursor.getString(column) ?: name
            }
        } catch (_: Exception) { } finally { cursor?.close() }
        return name
    }

    private fun loadTrack(index: Int, uri: Uri) {
        val deck = decks[index]
        releasePlayer(deck)
        deck.title.text = getDisplayName(uri)
        deck.status.text = "LOADING AUDIO…"
        deck.playButton.text = "LOADING"
        val player = MediaPlayer()
        deck.player = player
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener {
                deck.prepared = true
                deck.status.text = "LOADED  •  ${formatTime(it.duration)}  •  BPM --"
                deck.playButton.text = "PLAY"
                deck.seek.max = it.duration.coerceAtLeast(1)
                applyDeckVolumes()
                Toast.makeText(this, "Loaded on Deck ${'A' + index}",
                    Toast.LENGTH_SHORT).show()
            }
            player.setOnCompletionListener {
                deck.playButton.text = "PLAY"
                deck.status.text = "FINISHED  •  BPM --"
                deck.seek.progress = 0
            }
            player.setOnErrorListener { _, what, extra ->
                deck.prepared = false
                deck.status.text = "PLAYBACK ERROR ($what/$extra)"
                deck.playButton.text = "PLAY"
                Toast.makeText(this, "This audio format could not be played.",
                    Toast.LENGTH_LONG).show()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer(deck)
            deck.status.text = "COULD NOT LOAD TRACK"
            deck.playButton.text = "PLAY"
            Toast.makeText(this, "Could not open this audio file.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleDeck(index: Int) {
        val deck = decks[index]
        val player = deck.player
        if (!deck.prepared || player == null) {
            Toast.makeText(this, "Load a music file into this deck first.",
                Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (player.isPlaying) {
                player.pause()
                deck.playButton.text = "PLAY"
                deck.status.text = "PAUSED  •  BPM --"
            } else {
                player.start()
                deck.playButton.text = "PAUSE"
                deck.status.text = "PLAYING  •  BPM --"
            }
        } catch (_: IllegalStateException) {
            Toast.makeText(this, "Reload this track and try again.",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopDeck(index: Int) {
        val deck = decks[index]
        val player = deck.player ?: return
        if (!deck.prepared) return
        try {
            if (player.isPlaying) player.pause()
            player.seekTo(0)
            deck.seek.progress = 0
            deck.playButton.text = "PLAY"
            deck.status.text = "STOPPED  •  BPM --"
        } catch (_: IllegalStateException) { }
    }

    private fun releasePlayer(deck: DeckUi) {
        deck.prepared = false
        try { deck.player?.setOnPreparedListener(null) } catch (_: Exception) { }
        try { deck.player?.release() } catch (_: Exception) { }
        deck.player = null
        deck.seek.progress = 0
        deck.playButton.text = "PLAY"
    }

    private fun applyDeckVolumes() {
        decks.forEachIndexed { index, deck ->
            val crossGain = when (index) {
                0, 2 -> (1f - crossfader).coerceIn(0f, 1f)
                else -> crossfader.coerceIn(0f, 1f)
            }
            try {
                deck.player?.setVolume(deck.gain * masterGain * crossGain,
                    deck.gain * masterGain * crossGain)
            } catch (_: IllegalStateException) { }
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTask)
        decks.forEach { releasePlayer(it) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 4102
    }
}
