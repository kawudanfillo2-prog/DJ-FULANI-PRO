package com.djfulanipro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import kotlin.math.max

internal class WaveformView(context: Context) : View(context) {
    private val barsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 190, 255)
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 85, 100)
        strokeWidth = 1f
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 65, 85)
        strokeWidth = 2.5f
    }
    private var amplitudes = FloatArray(160) { 0.08f }
    private var position = 0f

    fun setWaveform(values: FloatArray) {
        if (values.isNotEmpty()) amplitudes = values.copyOf()
        invalidate()
    }

    fun setPlaybackPosition(fraction: Float) {
        position = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, centerPaint)
        val step = width.toFloat() / amplitudes.size.coerceAtLeast(1)
        for (i in amplitudes.indices) {
            val amp = max(0.035f, amplitudes[i]) * height * 0.43f
            val x = (i + 0.5f) * step
            canvas.drawLine(x, mid - amp, x, mid + amp, barsPaint)
        }
        val x = position * width
        canvas.drawLine(x, 0f, x, height.toFloat(), playheadPaint)
    }
}
