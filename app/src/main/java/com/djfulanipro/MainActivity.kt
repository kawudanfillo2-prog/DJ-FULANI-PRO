@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.djfulanipro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class MainActivity : Activity() {

    private data class DeckUi(
        val player: ExoPlayer,
        val videoView: PlayerView,
        val title: TextView,
        val status: TextView,
        val bpmButton: Button,
        val masterButton: Button,
        val playButton: Button,
        val seek: SeekBar,
        val waveform: WaveformView,
        val turntable: TurntableView,
        var mediaUri: Uri? = null,
        var audioMode: Boolean = false,
        var decoder: PcmDeckDecoder? = null,
        var audioPlaying: Boolean = false,
        var durationMs: Long = 0L,
        var eqLow: Float = 1f,
        var eqMid: Float = 1f,
        var eqHigh: Float = 1f,
        var analysisToken: Int = 0,
        var loaded: Boolean = false,
        var dragging: Boolean = false,
        var gain: Float = 0.82f,
        var bpm: Float = 0f,
        var pitchPercent: Float = 0f,
        var beatZeroMs: Long = 0L,
        var cueMs: Long = 0L,
        var hotCues: LongArray = LongArray(4) { -1L },
        var loopStartMs: Long = 0L,
        var loopEndMs: Long = 0L,
        var loopEnabled: Boolean = false,
        var syncEnabled: Boolean = false,
        var isMaster: Boolean = false,
        var equalizer: Equalizer? = null,
        var reverb: PresetReverb? = null,
        var bassBoost: BassBoost? = null,
        var reverbEnabled: Boolean = false,
        var bassEnabled: Boolean = false
    )

    private data class SamplerPad(
        var player: MediaPlayer? = null,
        var ready: Boolean = false,
        var name: String = "EMPTY",
        var button: Button? = null
    )

    private val decks = ArrayList<DeckUi>(4)
    private val samplerPads = Array(8) { SamplerPad() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var masterGain = 0.9f
    private var crossfader = 0.5f
    private var pendingDeck = -1

    private val progressTask = object : Runnable {
        override fun run() {
            decks.forEachIndexed { index, deck ->
                if (!deck.loaded) return@forEachIndexed
                val position = currentPositionMs(index)
                val duration = if (deck.audioMode) deck.durationMs else deck.player.duration
                val playing = if (deck.audioMode) deck.audioPlaying else deck.player.isPlaying
                if (!deck.dragging && duration > 0L) {
                    deck.seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    deck.seek.progress = position.coerceIn(0L, deck.seek.max.toLong()).toInt()
                }
                if (duration > 0L) deck.waveform.setPlaybackPosition(position.toFloat() / duration)
                deck.turntable.setPlaying(playing)
                if (deck.loopEnabled && playing && position >= deck.loopEndMs) {
                    if (deck.audioMode) seekAudioDeck(index, deck.loopStartMs, true)
                    else deck.player.seekTo(deck.loopStartMs)
                }
                if (playing) deck.playButton.text = "PAUSE"
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 10, 17)
        window.navigationBarColor = Color.rgb(6, 10, 17)
        val audioReady = try { NativeAudioBridge.nativeStartEngine() } catch (_: Exception) { false }
        if (!audioReady) Toast.makeText(this, "Native audio engine could not start on this device.", Toast.LENGTH_LONG).show()
        buildConsole()
        mainHandler.post(progressTask)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
            textSize = 10f
            minHeight = dp(34)
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { action() }
        }

    private fun buildConsole() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(9))
            setBackgroundColor(Color.rgb(6, 10, 17))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_dj_fulani_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(label("DJ FULANI PRO", 22f, Color.WHITE),
            LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(label("4 DECKS • AUDIO + VIDEO", 9f, Color.rgb(45, 200, 255)))
        root.addView(header)

        val subHeader = label(
            "LOAD MUSIC OR VIDEO • SET BPM • MARK THE FIRST BEAT • SYNC",
            9f, Color.rgb(170, 190, 210)
        )
        root.addView(subHeader)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            for (column in 0..1) {
                val index = row * 2 + column
                rowLayout.addView(
                    createDeck(index),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                )
            }
            grid.addView(rowLayout)
        }
        root.addView(grid)
        root.addView(createMasterStrip())
        root.addView(createSamplerSection())
        root.addView(label(
            "SYNC uses the BPM values you enter and the beat markers you set. " +
                "Video decoding depends on your phone's supported formats.",
            9f, Color.LTGRAY
        ).apply { setPadding(dp(4), dp(8), dp(4), dp(5)) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            setBackgroundColor(Color.rgb(6, 10, 17))
        }
        setContentView(scroll)
    }

    private fun createDeck(index: Int): View {
        val accents = intArrayOf(
            Color.rgb(0, 190, 255),
            Color.rgb(255, 75, 110),
            Color.rgb(55, 220, 125),
            Color.rgb(180, 105, 255)
        )
        val accent = accents[index]
        val player = ExoPlayer.Builder(this).build()
        val sessionId = try {
            (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        } catch (_: Exception) { 0 }

        if (sessionId > 0) {
            try { player.setAudioSessionId(sessionId) } catch (_: Exception) { }
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = false

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundColor(Color.rgb(20, 30, 44))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("DECK ${'A' + index}", 16f, accent),
            LinearLayout.LayoutParams(0, dp(32), 1f))

        val bpmButton = button("BPM --") { editBpm(index) }
        top.addView(bpmButton)
        val masterButton = button(if (index == 0) "MASTER" else "SET MASTER") {
            setMasterDeck(index)
        }
        top.addView(masterButton)
        panel.addView(top)

        val title = label("No track loaded", 10f, Color.WHITE).apply {
            maxLines = 1
            setPadding(0, dp(2), 0, dp(2))
        }
        panel.addView(title)
        val status = label("READY • AUDIO / VIDEO", 9f, accent)
        panel.addView(status)
        val waveform = WaveformView(this).apply {
            setBackgroundColor(Color.rgb(7, 12, 20))
        }
        panel.addView(waveform, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ))
        val turntable = TurntableView(this, accent).apply {
            setBackgroundColor(Color.rgb(7, 12, 20))
        }
        panel.addView(turntable, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(126)
        ))

        val videoView = PlayerView(this).apply {
            useController = false
            controllerAutoShow = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            this.player = player
            setShutterBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        panel.addView(videoView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
        ))

        val seek = SeekBar(this).apply { max = 1000; progress = 0 }
        panel.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(23)
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = button("PLAY") { toggleDeck(index) }
        controls.addView(play, LinearLayout.LayoutParams(0, dp(35), 1f))
        controls.addView(button("CUE") { returnToCue(index) },
            LinearLayout.LayoutParams(0, dp(35), 1f))
        controls.addView(button("SET CUE") { setCue(index) },
            LinearLayout.LayoutParams(0, dp(35), 1f))
        controls.addView(button("SET BEAT") { setBeatZero(index) },
            LinearLayout.LayoutParams(0, dp(35), 1f))
        controls.addView(button("SYNC") { syncDeck(index) },
            LinearLayout.LayoutParams(0, dp(35), 1f))
        panel.addView(controls)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        secondRow.addView(button("AUTO BPM") { analyzeBpm(index) },
            LinearLayout.LayoutParams(0, dp(33), 1.15f))
        secondRow.addView(button("STOP") { stopDeck(index) },
            LinearLayout.LayoutParams(0, dp(33), 0.8f))
        secondRow.addView(button("4 BEAT LOOP") { toggleLoop(index) },
            LinearLayout.LayoutParams(0, dp(33), 1.3f))
        secondRow.addView(button("LOOP OFF") { deckLoopOff(index) },
            LinearLayout.LayoutParams(0, dp(33), 1f))
        panel.addView(secondRow)

        val hotCueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (cue in 0..3) {
            hotCueRow.addView(button("HOT ${cue + 1}") {
                useHotCue(index, cue)
            }, LinearLayout.LayoutParams(0, dp(31), 1f))
        }
        panel.addView(hotCueRow)

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeRow.addView(label("VOL", 8f, Color.LTGRAY))
        val volume = SeekBar(this).apply { max = 100; progress = 82 }
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, dp(22), 1f))
        volumeRow.addView(button("LOAD AUDIO / VIDEO") {
            openDeckPicker(index)
        })
        panel.addView(volumeRow)

        val pitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pitchRow.addView(label("PITCH", 8f, Color.LTGRAY))
        val pitch = SeekBar(this).apply { max = 200; progress = 100 }
        pitchRow.addView(pitch, LinearLayout.LayoutParams(0, dp(22), 1f))
        panel.addView(pitchRow)

        val eqRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val eqLow = createEqControl(index, "LOW", 80f)
        val eqMid = createEqControl(index, "MID", 1000f)
        val eqHigh = createEqControl(index, "HIGH", 8000f)
        eqRow.addView(eqLow, LinearLayout.LayoutParams(0, dp(40), 1f))
        eqRow.addView(eqMid, LinearLayout.LayoutParams(0, dp(40), 1f))
        eqRow.addView(eqHigh, LinearLayout.LayoutParams(0, dp(40), 1f))
        panel.addView(eqRow)

        val fxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fxRow.addView(button("REVERB") { toggleReverb(index) },
            LinearLayout.LayoutParams(0, dp(31), 1f))
        fxRow.addView(button("BASS BOOST") { toggleBassBoost(index) },
            LinearLayout.LayoutParams(0, dp(31), 1.3f))
        panel.addView(fxRow)

        val deck = DeckUi(
            player = player,
            videoView = videoView,
            title = title,
            status = status,
            bpmButton = bpmButton,
            masterButton = masterButton,
            playButton = play,
            seek = seek,
            waveform = waveform,
            turntable = turntable,
            isMaster = index == 0
        )
        if (sessionId > 0) createDeckEffects(deck, sessionId)
        decks.add(deck)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { deck.dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                deck.dragging = false
                if (deck.loaded) {
                    if (deck.audioMode) seekAudioDeck(index, deck.seek.progress.toLong(), deck.audioPlaying)
                    else player.seekTo(deck.seek.progress.toLong())
                }
            }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { }
        })
        volume.setOnSeekBarChangeListener(simpleSeek {
            deck.gain = it / 100f
            applyDeckVolumes()
        })
        pitch.setOnSeekBarChangeListener(simpleSeek {
            deck.pitchPercent = (it - 100) / 10f
            applyDeckSpeed(index)
        })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> deck.status.text = "BUFFERING…"
                    Player.STATE_READY -> {
                        if (!deck.audioMode) deck.loaded = player.currentMediaItem != null
                        if (!deck.audioMode && deck.loaded) {
                            deck.playButton.text = if (player.isPlaying) "PAUSE" else "PLAY"
                            deck.status.text = "READY • ${formatTime(player.duration)} • " +
                                if (deck.bpm > 0f) "BPM ${deck.bpm}" else "BPM --"
                        }
                        applyDeckVolumes()
                    }
                    Player.STATE_ENDED -> {
                        deck.playButton.text = "PLAY"
                        deck.status.text = "FINISHED"
                    }
                    Player.STATE_IDLE -> {
                        if (!deck.loaded) deck.status.text = "READY • AUDIO / VIDEO"
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                deck.playButton.text = if (isPlaying) "PAUSE" else "PLAY"
            }

            override fun onPlayerError(error: PlaybackException) {
                deck.loaded = false
                deck.playButton.text = "PLAY"
                deck.status.text = "FORMAT / DECODER ERROR"
                Toast.makeText(
                    this@MainActivity,
                    "This file format may not be supported by this phone.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        return panel
    }

    private fun createEqControl(deckIndex: Int, name: String, frequency: Float): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        container.addView(label(name, 8f, Color.rgb(50, 205, 255)))
        val control = SeekBar(this).apply { max = 200; progress = 100 }
        control.setOnSeekBarChangeListener(simpleSeek { progress ->
            setEqBand(deckIndex, frequency, progress)
        })
        container.addView(control, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(26)
        ))
        return container
    }

    private fun createDeckEffects(deck: DeckUi, sessionId: Int) {
        try {
            deck.equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) { deck.equalizer = null }
        try {
            deck.reverb = PresetReverb(0, sessionId).apply {
                preset = PresetReverb.PRESET_LARGEHALL
                enabled = false
            }
            deck.player.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
        } catch (_: Exception) { deck.reverb = null }
        try {
            deck.bassBoost = BassBoost(0, sessionId).apply { enabled = false }
        } catch (_: Exception) { deck.bassBoost = null }
    }

    private fun setEqBand(deckIndex: Int, frequencyHz: Float, progress: Int) {
        val deck = decks.getOrNull(deckIndex) ?: return
        if (deck.audioMode) {
            val db = ((progress - 100) / 100f) * 12f
            val gain = Math.pow(10.0, (db / 20f).toDouble()).toFloat()
            when {
                frequencyHz < 200f -> deck.eqLow = gain
                frequencyHz < 3000f -> deck.eqMid = gain
                else -> deck.eqHigh = gain
            }
            val low = if (deck.bassEnabled) maxOf(deck.eqLow, 1.5f) else deck.eqLow
            NativeAudioBridge.nativeSetDeckEq(deckIndex, low, deck.eqMid, deck.eqHigh)
            return
        }
        val eq = deck.equalizer ?: return
        try {
            var nearest = 0
            var nearestDistance = Float.MAX_VALUE
            for (band in 0 until eq.numberOfBands.toInt()) {
                val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                val distance = kotlin.math.abs(centerHz - frequencyHz)
                if (distance < nearestDistance) {
                    nearest = band
                    nearestDistance = distance
                }
            }
            val range = eq.bandLevelRange
            val level = (range[0] + (range[1] - range[0]) * (progress / 200f))
                .toInt().coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(nearest.toShort(), level.toShort())
        } catch (_: Exception) { }
    }

    private fun toggleReverb(index: Int) {
        val deck = decks[index]
        if (deck.audioMode) {
            deck.reverbEnabled = !deck.reverbEnabled
            NativeAudioBridge.nativeSetDeckEcho(index, deck.reverbEnabled)
            toast(if (deck.reverbEnabled) "ECHO ON • Deck ${'A' + index}" else "ECHO OFF")
            return
        }
        val reverb = deck.reverb
        if (reverb == null) {
            toast("Reverb effect is not supported by this device.")
            return
        }
        try {
            deck.reverbEnabled = !deck.reverbEnabled
            reverb.enabled = deck.reverbEnabled
            deck.player.setAuxEffectInfo(
                if (deck.reverbEnabled) AuxEffectInfo(reverb.id, 0.65f)
                else AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
            )
            toast(if (deck.reverbEnabled) "Reverb ON • Deck ${'A' + index}" else "Reverb OFF")
        } catch (_: Exception) { toast("Could not enable reverb on this phone.") }
    }

    private fun toggleBassBoost(index: Int) {
        val deck = decks[index]
        if (deck.audioMode) {
            deck.bassEnabled = !deck.bassEnabled
            val low = if (deck.bassEnabled) maxOf(deck.eqLow, 1.5f) else deck.eqLow
            NativeAudioBridge.nativeSetDeckEq(index, low, deck.eqMid, deck.eqHigh)
            toast(if (deck.bassEnabled) "BASS BOOST ON" else "BASS BOOST OFF")
            return
        }
        val bass = deck.bassBoost
        if (bass == null) {
            toast("Bass Boost is not supported by this device.")
            return
        }
        try {
            deck.bassEnabled = !deck.bassEnabled
            if (deck.bassEnabled) bass.setStrength(650.toShort())
            bass.enabled = deck.bassEnabled
            toast(if (deck.bassEnabled) "Bass Boost ON" else "Bass Boost OFF")
        } catch (_: Exception) { toast("Could not change Bass Boost.") }
    }

    private fun createMasterStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setBackgroundColor(Color.rgb(13, 22, 35))
        }
        val master = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        master.addView(label("MASTER VOL", 9f))
        val masterBar = SeekBar(this).apply { max = 100; progress = 90 }
        masterBar.setOnSeekBarChangeListener(simpleSeek {
            masterGain = it / 100f
            applyDeckVolumes()
        })
        master.addView(masterBar, LinearLayout.LayoutParams(dp(110), dp(28)))
        strip.addView(master)

        val cross = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        cross.addView(label("CROSSFADER   A/C  ◀────●────▶  B/D", 9f,
            Color.rgb(45, 205, 255)))
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

    private fun createSamplerSection(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setBackgroundColor(Color.rgb(18, 28, 43))
        }
        panel.addView(label("PERFORMANCE SAMPLER • 8 PADS", 13f,
            Color.rgb(255, 205, 50)))
        panel.addView(label("Tap a pad to play. Hold a pad to load a sample.", 9f, Color.LTGRAY))
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (column in 0..3) {
                val index = row * 4 + column
                val pad = button("PAD ${index + 1}\nLOAD") { playSampler(index) }
                pad.setOnLongClickListener {
                    openSamplerPicker(index)
                    true
                }
                samplerPads[index].button = pad
                rowLayout.addView(pad, LinearLayout.LayoutParams(0, dp(52), 1f)
                    .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            panel.addView(rowLayout)
        }
        return panel
    }

    private fun simpleSeek(onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onValue(progress)
            }
        }

    private fun openDeckPicker(deckIndex: Int) {
        pendingDeck = deckIndex
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_DECK_MEDIA)
    }

    private fun openSamplerPicker(padIndex: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_SAMPLER_BASE + padIndex)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) { }

        when {
            requestCode == REQUEST_DECK_MEDIA -> {
                if (pendingDeck in 0..3) loadTrack(pendingDeck, uri)
            }
            requestCode in REQUEST_SAMPLER_BASE until REQUEST_SAMPLER_BASE + 8 -> {
                loadSampler(requestCode - REQUEST_SAMPLER_BASE, uri)
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "Media file"
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
        deck.decoder?.stop()
        deck.decoder = null
        deck.audioPlaying = false
        NativeAudioBridge.nativeSetDeckPlaying(index, false)
        NativeAudioBridge.nativeClearDeck(index)
        deck.analysisToken++
        deck.mediaUri = uri
        deck.player.stop()
        deck.player.clearMediaItems()
        deck.loaded = false
        deck.durationMs = 0L
        deck.loopEnabled = false
        deck.syncEnabled = false
        deck.bpm = 0f
        deck.beatZeroMs = 0L
        deck.cueMs = 0L
        deck.hotCues = LongArray(4) { -1L }
        deck.title.text = getDisplayName(uri)
        deck.status.text = "PREPARING MEDIA…"
        deck.playButton.text = "LOADING"

        val mime = contentResolver.getType(uri).orEmpty()
        deck.audioMode = mime.startsWith("audio/")
        deck.videoView.visibility = if (mime.startsWith("video/")) View.VISIBLE else View.GONE

        if (deck.audioMode) {
            deck.status.text = "DECODING INTO SHARED MIXER…"
            val decoder = PcmDeckDecoder(
                applicationContext, index, uri, 0L,
                onReady = { duration, _ ->
                    runOnUiThread {
                        deck.durationMs = duration
                        deck.loaded = true
                        deck.playButton.text = "PLAY"
                        deck.status.text = "PCM DECK READY • SHARED CLOCK"
                        if (duration > 0L) deck.seek.max =
                            duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        applyDeckVolumes()
                    }
                },
                onFinished = {
                    runOnUiThread { deck.status.text = "TRACK DECODED • BUFFER DRAINING" }
                },
                onError = { message ->
                    runOnUiThread {
                        deck.loaded = false
                        deck.playButton.text = "LOAD"
                        deck.status.text = "AUDIO DECODE ERROR"
                        toast(message)
                    }
                }
            )
            deck.decoder = decoder
            decoder.start()
        } else {
            deck.audioMode = false
            deck.player.setMediaItem(MediaItem.fromUri(uri))
            deck.player.prepare()
            deck.player.playWhenReady = false
        }
        analyzeBpm(index)
    }

    private fun currentPositionMs(index: Int): Long {
        val deck = decks[index]
        return if (deck.audioMode) NativeAudioBridge.nativeGetDeckPositionMs(index)
        else deck.player.currentPosition
    }

    private fun seekAudioDeck(index: Int, targetMs: Long, resume: Boolean) {
        val deck = decks[index]
        val uri = deck.mediaUri ?: return
        deck.decoder?.stop()
        deck.decoder = null
        deck.audioPlaying = false
        NativeAudioBridge.nativeSetDeckPlaying(index, false)
        NativeAudioBridge.nativeClearDeck(index)
        deck.loaded = false
        deck.status.text = "SEEKING / REBUFFERING…"
        deck.playButton.text = "BUFFERING"
        val next = PcmDeckDecoder(
            applicationContext, index, uri, targetMs.coerceAtLeast(0L),
            onReady = { duration, _ ->
                runOnUiThread {
                    deck.durationMs = duration
                    deck.loaded = true
                    deck.audioPlaying = resume
                    NativeAudioBridge.nativeSetDeckPlaying(index, resume)
                    deck.playButton.text = if (resume) "PAUSE" else "PLAY"
                    deck.status.text = if (resume) "PLAYING • SHARED CLOCK" else "CUE / SEEK READY"
                }
            },
            onFinished = { },
            onError = { message ->
                runOnUiThread {
                    deck.loaded = false
                    deck.audioPlaying = false
                    deck.status.text = "AUDIO SEEK ERROR"
                    toast(message)
                }
            }
        )
        deck.decoder = next
        next.start()
    }

    private fun analyzeBpm(index: Int) {
        val deck = decks.getOrNull(index) ?: return
        val uri = deck.mediaUri ?: run {
            toast("Load a track before automatic BPM analysis.")
            return
        }
        val token = ++deck.analysisToken
        deck.status.text = "ANALYZING TEMPO…"
        Thread {
            val result = BpmAnalyzer.analyze(this, uri)
            runOnUiThread {
                if (isFinishing || index !in decks.indices || decks[index].analysisToken != token) return@runOnUiThread
                val current = decks[index]
                if (result == null) {
                    current.status.text = "BPM ANALYSIS FAILED • SET MANUALLY"
                    toast("Could not detect a reliable tempo. Try another section or enter BPM manually.")
                } else {
                    current.bpm = result.bpm
                    current.beatZeroMs = result.beatZeroMs
                    current.bpmButton.text = "BPM %.1f".format(result.bpm)
                    current.waveform.setWaveform(result.waveform)
                    current.status.text = "AUTO BPM %.1f • BEAT GRID ESTIMATE".format(result.bpm)
                    applyAllDeckSpeeds()
                    toast("Detected %.1f BPM on Deck %s".format(result.bpm, 'A' + index))
                }
            }
        }.start()
    }

    private fun toggleDeck(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) {
            toast("Load an audio or video file into this deck first.")
            return
        }
        if (deck.audioMode) {
            deck.audioPlaying = !deck.audioPlaying
            NativeAudioBridge.nativeSetDeckPlaying(index, deck.audioPlaying)
            deck.status.text = if (deck.audioPlaying) "PLAYING • SHARED MIXER" else "PAUSED"
            deck.playButton.text = if (deck.audioPlaying) "PAUSE" else "PLAY"
            return
        }
        if (deck.player.playbackState != Player.STATE_READY) {
            toast("Video is still preparing.")
            return
        }
        if (deck.player.isPlaying) {
            deck.player.pause()
            deck.status.text = "PAUSED"
            deck.playButton.text = "PLAY"
        } else {
            deck.player.play()
            deck.status.text = "PLAYING"
            deck.playButton.text = "PAUSE"
        }
    }

    private fun stopDeck(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) return
        if (deck.audioMode) {
            seekAudioDeck(index, 0L, false)
        } else {
            deck.player.pause()
            deck.player.seekTo(0L)
            deck.playButton.text = "PLAY"
            deck.status.text = "STOPPED"
        }
    }

    private fun setCue(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) {
            toast("Load a track before setting a cue point.")
            return
        }
        deck.cueMs = currentPositionMs(index)
        deck.status.text = "CUE MARKED"
        toast("Cue point saved on Deck ${'A' + index}.")
    }

    private fun setBeatZero(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) {
            toast("Load a track before marking its first beat.")
            return
        }
        deck.beatZeroMs = currentPositionMs(index)
        toast("Beat marker set on Deck ${'A' + index}.")
    }

    private fun returnToCue(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) return
        if (deck.audioMode) {
            seekAudioDeck(index, deck.cueMs, false)
        } else {
            deck.player.pause()
            deck.player.seekTo(deck.cueMs)
            deck.playButton.text = "PLAY"
            deck.status.text = "CUE POSITION"
        }
    }

    private fun useHotCue(index: Int, cueIndex: Int) {
        val deck = decks[index]
        if (!deck.loaded) {
            toast("Load a track first.")
            return
        }
        if (deck.hotCues[cueIndex] < 0L) {
            deck.hotCues[cueIndex] = currentPositionMs(index)
            toast("Hot Cue ${cueIndex + 1} saved.")
        } else {
            if (deck.audioMode) seekAudioDeck(index, deck.hotCues[cueIndex], deck.audioPlaying)
            else deck.player.seekTo(deck.hotCues[cueIndex])
            toast("Jumped to Hot Cue ${cueIndex + 1}.")
        }
    }

    private fun toggleLoop(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) {
            toast("Load a track first.")
            return
        }
        if (deck.loopEnabled) {
            deck.loopEnabled = false
            deck.status.text = "LOOP OFF"
            return
        }
        if (deck.bpm <= 0f) {
            toast("Set this deck's BPM before using a 4-beat loop.")
            return
        }
        val beatMs = (60_000f / deck.bpm).toLong().coerceAtLeast(1L)
        val start = deck.player.currentPosition
        val end = (start + beatMs * 4L).coerceAtMost(deck.player.duration)
        if (end <= start) {
            toast("There is not enough track remaining for this loop.")
            return
        }
        deck.loopStartMs = start
        deck.loopEndMs = end
        deck.loopEnabled = true
        deck.status.text = "4-BEAT LOOP ON"
    }

    private fun deckLoopOff(index: Int) {
        decks[index].loopEnabled = false
        decks[index].status.text = if (decks[index].loaded) "LOOP OFF" else "READY"
    }

    private fun editBpm(index: Int) {
        val deck = decks[index]
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "Example: 128.0"
            if (deck.bpm > 0f) setText(deck.bpm.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Set BPM — Deck ${'A' + index}")
            .setMessage("Enter or correct the tempo. Use AUTO BPM to estimate the original track tempo first.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = field.text.toString().toFloatOrNull()
                if (value == null || value !in 50f..240f) {
                    toast("Enter a BPM between 50 and 240.")
                } else {
                    deck.bpm = value
                    deck.bpmButton.text = "BPM %.1f".format(value)
                    deck.status.text = "BPM %.1f SET".format(value)
                    applyAllDeckSpeeds()
                }
            }
            .show()
    }

    private fun setMasterDeck(index: Int) {
        decks.forEachIndexed { i, deck ->
            deck.isMaster = i == index
            deck.masterButton.text = if (deck.isMaster) "MASTER" else "SET MASTER"
        }
        applyAllDeckSpeeds()
        toast("Deck ${'A' + index} is the tempo master.")
    }

    private fun syncDeck(index: Int) {
        val target = decks[index]
        val masterIndex = decks.indexOfFirst { it.isMaster }
        if (masterIndex < 0 || masterIndex == index) {
            toast("Choose another deck as the tempo master.")
            return
        }
        val master = decks[masterIndex]
        if (!master.loaded || !target.loaded || master.bpm <= 0f || target.bpm <= 0f) {
            toast("Load both tracks and detect BPM on both decks first.")
            return
        }
        val masterEffectiveBpm = master.bpm * (1f + master.pitchPercent / 100f)
        val targetSpeed = masterEffectiveBpm / target.bpm
        if (targetSpeed !in 0.5f..2.0f) {
            toast("The BPM difference is too large for safe sync. Adjust pitch or BPM.")
            return
        }
        target.syncEnabled = true
        if (target.audioMode) NativeAudioBridge.nativeSetDeckSpeed(index, targetSpeed)
        else target.player.setPlaybackParameters(PlaybackParameters(targetSpeed, 1f))

        val masterPosition = currentPositionMs(masterIndex)
        val targetPosition = currentPositionMs(index)
        val masterBeatMs = 60_000.0 / masterEffectiveBpm.toDouble()
        val phase = (masterPosition - master.beatZeroMs).toDouble() / masterBeatMs
        val phaseWithinBeat = phase - floor(phase)
        val targetBeatMs = 60_000.0 / target.bpm.toDouble()
        val targetBeatNumber = floor((targetPosition - target.beatZeroMs).toDouble() / targetBeatMs)
        val aligned = target.beatZeroMs +
            (targetBeatNumber * targetBeatMs + phaseWithinBeat * targetBeatMs).toLong()
        if (target.audioMode) seekAudioDeck(index, aligned.coerceAtLeast(0L), target.audioPlaying)
        else target.player.seekTo(aligned.coerceAtLeast(0L))
        target.status.text = "SYNC LOCKED • ${masterEffectiveBpm} BPM"
        toast("Deck ${'A' + index} synced to Deck ${'A' + masterIndex} on the shared output engine.")
    }

    private fun applyDeckSpeed(index: Int) {
        val deck = decks[index]
        if (!deck.loaded) return
        val master = decks.firstOrNull { it.isMaster }
        val masterBpm = if (master != null && master.bpm > 0f)
            master.bpm * (1f + master.pitchPercent / 100f) else 0f
        val pitchRatio = 1f + deck.pitchPercent / 100f
        val finalSpeed = if (deck.syncEnabled && !deck.isMaster &&
            deck.bpm > 0f && masterBpm > 0f) {
            (masterBpm / deck.bpm).coerceIn(0.5f, 2.0f)
        } else pitchRatio.coerceIn(0.5f, 2.0f)
        try {
            if (deck.audioMode) NativeAudioBridge.nativeSetDeckSpeed(index, finalSpeed)
            else deck.player.setPlaybackParameters(PlaybackParameters(finalSpeed, 1f))
        } catch (_: Exception) { }
    }

    private fun applyAllDeckSpeeds() {
        decks.indices.forEach { applyDeckSpeed(it) }
    }

    private fun applyDeckVolumes() {
        NativeAudioBridge.nativeSetMasterGain(masterGain)
        NativeAudioBridge.nativeSetCrossfader(crossfader)
        val leftGain = cos(crossfader * Math.PI / 2.0).toFloat()
        val rightGain = sin(crossfader * Math.PI / 2.0).toFloat()
        decks.forEachIndexed { index, deck ->
            NativeAudioBridge.nativeSetDeckGain(index, deck.gain)
            val crossGain = if (index == 0 || index == 2) leftGain else rightGain
            if (!deck.audioMode) {
                try { deck.player.volume = (deck.gain * masterGain * crossGain).coerceIn(0f, 1f) }
                catch (_: IllegalStateException) { }
            }
        }
    }

    private fun loadSampler(index: Int, uri: Uri) {
        val pad = samplerPads[index]
        try { pad.player?.release() } catch (_: Exception) { }
        pad.player = null
        pad.ready = false
        pad.name = getDisplayName(uri)
        val player = MediaPlayer()
        pad.player = player
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener {
                pad.ready = true
                pad.button?.text = "PAD ${index + 1}\nPLAY"
                toast("Sample loaded into Pad ${index + 1}.")
            }
            player.setOnCompletionListener {
                try { it.seekTo(0) } catch (_: Exception) { }
            }
            player.setOnErrorListener { _, _, _ ->
                pad.ready = false
                pad.button?.text = "PAD ${index + 1}\nERROR"
                toast("This sample could not be decoded.")
                true
            }
            player.prepareAsync()
            pad.button?.text = "PAD ${index + 1}\nLOADING"
        } catch (_: Exception) {
            try { player.release() } catch (_: Exception) { }
            pad.player = null
            pad.button?.text = "PAD ${index + 1}\nLOAD"
            toast("Could not load this sample.")
        }
    }

    private fun playSampler(index: Int) {
        val pad = samplerPads[index]
        val player = pad.player
        if (!pad.ready || player == null) {
            openSamplerPicker(index)
            return
        }
        try {
            player.seekTo(0)
            player.start()
        } catch (_: Exception) {
            toast("Reload this sampler pad and try again.")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds < 0L || milliseconds == C.TIME_UNSET) return "--:--"
        val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTask)
        decks.forEachIndexed { index, deck ->
            try { deck.decoder?.stop() } catch (_: Exception) { }
            NativeAudioBridge.nativeSetDeckPlaying(index, false)
            try { deck.player.release() } catch (_: Exception) { }
            try { deck.equalizer?.release() } catch (_: Exception) { }
            try { deck.reverb?.release() } catch (_: Exception) { }
            try { deck.bassBoost?.release() } catch (_: Exception) { }
        }
        samplerPads.forEach { try { it.player?.release() } catch (_: Exception) { } }
        try { NativeAudioBridge.nativeStopEngine() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_DECK_MEDIA = 4102
        private const val REQUEST_SAMPLER_BASE = 4200
    }
}
