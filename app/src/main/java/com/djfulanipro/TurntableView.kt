package com.djfulanipro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

internal class TurntableView(context: Context, private val accent: Int) : View(context) {
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(75, 83, 96) }
    private val vinyl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 17, 22) }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(47, 55, 67)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val spindle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f
        isFakeBoldText = true
    }
    private var angle = 0f

    fun setPlaying(playing: Boolean) {
        angle = if (playing) (angle + 12f) % 360f else angle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.46f
        canvas.drawCircle(cx, cy, radius, outer)
        canvas.drawCircle(cx, cy, radius - 5f, vinyl)
        for (ring in 1..7) {
            canvas.drawCircle(cx, cy, radius * (0.30f + ring * 0.085f), groove)
        }
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawArc(cx - radius * 0.78f, cy - radius * 0.78f,
            cx + radius * 0.78f, cy + radius * 0.78f, 0f, 72f, false, groove)
        canvas.drawArc(cx - radius * 0.78f, cy - radius * 0.78f,
            cx + radius * 0.78f, cy + radius * 0.78f, 180f, 72f, false, groove)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius * 0.29f, label)
        canvas.drawCircle(cx, cy, radius * 0.08f, spindle)
        canvas.drawText("DJ FULANI", cx, cy + radius * 0.48f, text)
    }
}
