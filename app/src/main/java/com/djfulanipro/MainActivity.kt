package com.djfulanipro

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var engineStarted = false
    private val deckPlaying = BooleanArray(4)

    external fun nativeCreateEngine(): Boolean
    external fun nativeStartEngine(): Boolean
    external fun nativeStopEngine()
    external fun nativeSetDeckPlaying(deck: Int, playing: Boolean)
    external fun nativeSetDeckGain(deck: Int, gain: Float)
    external fun nativeSetMasterGain(gain: Float)

    companion object {
        init { System.loadLibrary("djfulanipro_audio") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 14, 22)
        window.navigationBarColor = Color.rgb(10, 14, 22)
        buildConsole()
        nativeCreateEngine()
    }

    private fun buildConsole() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(10, 14, 22))
        }
        root.addView(TextView(this).apply {
            text = "DJ FULANI PRO"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "PROFESSIONAL 4-DECK MIXING STUDIO"
            textSize = 11f
            setTextColor(Color.rgb(45, 190, 255))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 18)
        })

        val deckRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (column in 0..1) {
                val deck = row * 2 + column
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 12, 8, 12)
                    setBackgroundColor(Color.rgb(27, 39, 57))
                }
                panel.addView(TextView(this).apply {
                    text = "DECK " + (deck + 1)
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                val state = TextView(this).apply {
                    text = "READY • BPM --"
                    textSize = 11f
                    setTextColor(Color.rgb(45, 190, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 8)
                }
                panel.addView(state)
                panel.addView(Button(this).apply {
                    text = "PLAY / PAUSE"
                    setOnClickListener {
                        deckPlaying[deck] = !deckPlaying[deck]
                        nativeSetDeckPlaying(deck, deckPlaying[deck])
                        state.text = if (deckPlaying[deck]) "PLAY REQUESTED • BPM --" else "PAUSED • BPM --"
                        Toast.makeText(this@MainActivity,
                            "Deck " + (deck + 1) + ": state changed", Toast.LENGTH_SHORT).show()
                    }
                })
                val params = LinearLayout.LayoutParams(0, 190, 1f)
                params.setMargins(6, 6, 6, 6)
                rowLayout.addView(panel, params)
            }
            deckRows.addView(rowLayout)
        }
        root.addView(deckRows)
        root.addView(Button(this).apply {
            text = "START AUDIO ENGINE"
            setOnClickListener {
                engineStarted = nativeStartEngine()
                text = if (engineStarted) "AUDIO ENGINE RUNNING" else "RETRY AUDIO ENGINE"
                Toast.makeText(this@MainActivity,
                    if (engineStarted) "Audio output opened" else "Could not open audio output",
                    Toast.LENGTH_LONG).show()
            }
        })
        root.addView(TextView(this).apply {
            text = "PHASE 1 BUILD • Output foundation only. Music playback is coming next."
            textSize = 10f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        })
        setContentView(root)
    }

    override fun onDestroy() {
        if (engineStarted) nativeStopEngine()
        super.onDestroy()
    }
}
