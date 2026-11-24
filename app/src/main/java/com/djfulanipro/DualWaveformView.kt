package com.djfulanipro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * DJ-style dual vertical waveform display. A red playhead stays fixed at the
 * center while each deck's waveform scrolls beneath it.
 */
internal class DualWaveformView(context: Context) : View(context) {
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 58, 73)
        strokeWidth = 1f
    }
    private val playhead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 54, 73)
        strokeWidth = 2.5f
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var left = FloatArray(160) { 0.04f }
    private var right = FloatArray(160) { 0.04f }
    private var leftPosition = 0f
    private var rightPosition = 0f
    private var leftName = "DECK A"
    private var rightName = "DECK B"

    fun setDeckData(index: Int, values: FloatArray, position: Float, name: String) {
        if (index == 0) {
            left = values.copyOf()
            leftPosition = position.coerceIn(0f, 1f)
            leftName = name
        } else if (index == 1) {
            right = values.copyOf()
            rightPosition = position.coerceIn(0f, 1f)
            rightName = name
        }
        invalidate()
    }

    fun setPositions(leftValue: Float, rightValue: Float) {
        leftPosition = leftValue.coerceIn(0f, 1f)
        rightPosition = rightValue.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = width / 2f
        val centerY = height / 2f
        canvas.drawColor(Color.rgb(6, 10, 17))
        canvas.drawLine(half, 0f, half, height.toFloat(), divider)
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, playhead)
        canvas.drawText(leftName.take(24), half * 0.5f, 20f, titlePaint)
        canvas.drawText(rightName.take(24), half * 1.5f, 20f, titlePaint)
        drawDeck(canvas, left, leftPosition, 0f, half)
        drawDeck(canvas, right, rightPosition, half, half)
    }

    private fun drawDeck(canvas: Canvas, values: FloatArray, position: Float, xStart: Float, panelWidth: Float) {
        if (values.isEmpty()) return
        val usableHeight = (height - 38f).coerceAtLeast(1f)
        val stepY = usableHeight / 132f
        val middleX = xStart + panelWidth / 2f
        val visibleCount = 132
        for (row in 0 until visibleCount) {
            val relative = (row.toFloat() / (visibleCount - 1)) - 0.5f
            val trackPosition = (position + relative * 0.30f + 1f) % 1f
            val sampleIndex = min(values.size - 1, (trackPosition * values.size).toInt())
            val amplitude = max(0.025f, values[sampleIndex].coerceIn(0f, 1f))
            val barWidth = amplitude * panelWidth * 0.42f
            val y = 30f + row * stepY
            val hue = when {
                amplitude > 0.75f -> 8f
                amplitude > 0.55f -> 35f
                amplitude > 0.35f -> 110f
                amplitude > 0.18f -> 175f
                else -> 220f
            }
            bar.color = Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.96f))
            canvas.drawLine(middleX - barWidth, y, middleX + barWidth, y, bar)
        }
        canvas.drawLine(xStart + 10f, 30f, xStart + 10f, height - 8f, divider)
        canvas.drawLine(xStart + panelWidth - 10f, 30f, xStart + panelWidth - 10f, height - 8f, divider)
    }
}
