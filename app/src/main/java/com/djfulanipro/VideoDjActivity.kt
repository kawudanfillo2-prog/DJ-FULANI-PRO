@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.djfulanipro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class VideoDjActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(5, 8, 14))
        }
        root.addView(TextView(this).apply {
            text = "DJ FULANI PRO  •  VIDEO DJ"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        })
        state = TextView(this).apply {
            text = "LOAD A VIDEO TO BEGIN"
            textSize = 12f
            setTextColor(Color.rgb(40, 190, 255))
        }
        root.addView(state)
        val view = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(Button(this).apply {
            text = "LOAD VIDEO"
            setOnClickListener { pickVideo() }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        controls.addView(Button(this).apply {
            text = "PLAY / PAUSE"
            setOnClickListener {
                val p = player
                if (p == null) {
                    Toast.makeText(this@VideoDjActivity, "Load a video first.", Toast.LENGTH_SHORT).show()
                } else if (p.isPlaying) p.pause() else p.play()
            }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        controls.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { player?.pause(); player?.seekTo(0L) }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        root.addView(controls)
        root.addView(Button(this).apply {
            text = "BACK TO DJ MIXER"
            setOnClickListener { finish() }
        })
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also { view.player = it }
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "BUFFERING VIDEO…"
                    Player.STATE_READY -> "VIDEO READY"
                    Player.STATE_ENDED -> "VIDEO FINISHED"
                    else -> "VIDEO DJ"
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) state.text = "VIDEO PLAYING"
            }
        })
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_VIDEO)
    }

    @Deprecated("Uses the Android document picker for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) { }
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = false
        state.text = "VIDEO LOADED • READY"
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object { private const val REQUEST_VIDEO = 7301 }
}
