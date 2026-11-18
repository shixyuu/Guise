/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Guise contributors                                     *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.sin

/**
 * Guise v2.0: Hybrid-car style Dual-Engine Network Energy Monitor ("双擎网络动力流").
 * Visualizes real-time traffic allocation, latency, and active flow between Wi-Fi and Cellular
 * into the Guise Dual-Engine Core and out to the encrypted tunnel.
 */
class DualEngineFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var wifiWeight: Float = 0.5f
    private var cellWeight: Float = 0.5f
    private var wifiMeta: String = "待命"
    private var cellMeta: String = "待命"
    private var hubSub: String = "动态权重"
    private var isFlowing: Boolean = false

    private var phase: Float = 0f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity

    private val wifiPath = Path()
    private val cellPath = Path()
    private val outPath = Path()
    private val pathMeasure = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    private val boxRect = RectF()

    private val podPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dashEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9.5f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun updateState(
        flowing: Boolean,
        wifiShare: Float,
        cellShare: Float,
        wifiDetail: String,
        cellDetail: String,
        coreLabel: String,
    ) {
        val changed = isFlowing != flowing ||
            wifiWeight != wifiShare ||
            cellWeight != cellShare ||
            wifiMeta != wifiDetail ||
            cellMeta != cellDetail ||
            hubSub != coreLabel
        isFlowing = flowing
        wifiWeight = wifiShare.coerceIn(0f, 1f)
        cellWeight = cellShare.coerceIn(0f, 1f)
        wifiMeta = wifiDetail
        cellMeta = cellDetail
        hubSub = coreLabel
        if (isFlowing && isAttachedToWindow) {
            startAnim()
        } else if (!isFlowing) {
            stopAnim()
        }
        if (changed) invalidate()
    }

    private fun startAnim() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isFlowing) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colorSurfaceHigh = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF252730.toInt())
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFE3E2E9.toInt())
        val colorOnVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFC5C6D0.toInt())
        val colorOutline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0xFF44474F.toInt())
        val colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFFB4C5FF.toInt())
        val colorPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF28417E.toInt())
        val colorOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFDBE1FF.toInt())
        val colorTertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary, 0xFFE2BBDB.toInt())

        val leftW = dp(92f)
        val podH = dp(38f)
        val topY = dp(6f)
        val botY = h - podH - dp(6f)
        val centerY = h * 0.5f

        val hubW = dp(74f)
        val hubH = dp(44f)
        val hubX = (w - hubW) * 0.53f
        val hubY = centerY - hubH * 0.5f

        val outW = dp(68f)
        val outH = dp(40f)
        val outX = w - outW - dp(2f)
        val outY = centerY - outH * 0.5f

        // 1. Build Bezier curves
        val startX = leftW
        val wifiStartY = topY + podH * 0.5f
        val cellStartY = botY + podH * 0.5f
        val hubInX = hubX
        val wifiEndY = centerY - dp(7f)
        val cellEndY = centerY + dp(7f)
        val midX = (startX + hubInX) * 0.5f

        wifiPath.reset()
        wifiPath.moveTo(startX, wifiStartY)
        wifiPath.cubicTo(midX, wifiStartY, midX, wifiEndY, hubInX, wifiEndY)

        cellPath.reset()
        cellPath.moveTo(startX, cellStartY)
        cellPath.cubicTo(midX, cellStartY, midX, cellEndY, hubInX, cellEndY)

        outPath.reset()
        outPath.moveTo(hubX + hubW, centerY)
        outPath.lineTo(outX, centerY)

        // 2. Draw tracks & flowing energy particles
        drawTrackAndParticles(
            canvas = canvas,
            path = wifiPath,
            weight = if (isFlowing) wifiWeight else 0f,
            activeColor = colorPrimary,
            idleColor = colorOutline,
            phaseOffset = 0f,
        )
        drawTrackAndParticles(
            canvas = canvas,
            path = cellPath,
            weight = if (isFlowing) cellWeight else 0f,
            activeColor = colorTertiary,
            idleColor = colorOutline,
            phaseOffset = 0.35f,
        )
        drawTrackAndParticles(
            canvas = canvas,
            path = outPath,
            weight = if (isFlowing && (wifiWeight + cellWeight) > 0f) 0.9f else 0f,
            activeColor = colorPrimary,
            idleColor = colorOutline,
            phaseOffset = 0.15f,
        )

        // 3. Draw Wi-Fi Engine Pod (Top Left)
        boxRect.set(dp(2f), topY, leftW, topY + podH)
        podPaint.color = colorSurfaceHigh
        canvas.drawRoundRect(boxRect, dp(12f), dp(12f), podPaint)
        if (isFlowing && wifiWeight > 0.05f) {
            strokePaint.color = colorPrimary
            strokePaint.alpha = (90 + (wifiWeight * 140).toInt()).coerceIn(0, 255)
            canvas.drawRoundRect(boxRect, dp(12f), dp(12f), strokePaint)
        }
        titlePaint.color = colorOnSurface
        titlePaint.textAlign = Paint.Align.LEFT
        val wifiPct = if (isFlowing) "${(wifiWeight * 100).toInt()}%" else "—"
        canvas.drawText("Wi-Fi · $wifiPct", boxRect.left + dp(10f), boxRect.top + dp(16f), titlePaint)
        subPaint.color = colorOnVariant
        subPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(wifiMeta, boxRect.left + dp(10f), boxRect.top + dp(30f), subPaint)

        // 4. Draw Cellular Engine Pod (Bottom Left)
        boxRect.set(dp(2f), botY, leftW, botY + podH)
        podPaint.color = colorSurfaceHigh
        canvas.drawRoundRect(boxRect, dp(12f), dp(12f), podPaint)
        if (isFlowing && cellWeight > 0.05f) {
            strokePaint.color = colorTertiary
            strokePaint.alpha = (90 + (cellWeight * 140).toInt()).coerceIn(0, 255)
            canvas.drawRoundRect(boxRect, dp(12f), dp(12f), strokePaint)
        }
        val cellPct = if (isFlowing) "${(cellWeight * 100).toInt()}%" else "—"
        canvas.drawText("蜂窝 · $cellPct", boxRect.left + dp(10f), boxRect.top + dp(16f), titlePaint)
        canvas.drawText(cellMeta, boxRect.left + dp(10f), boxRect.top + dp(30f), subPaint)

        // 5. Draw Central Hybrid Hub
        boxRect.set(hubX, hubY, hubX + hubW, hubY + hubH)
        if (isFlowing) {
            val pulse = (sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
            strokePaint.color = colorPrimary
            strokePaint.alpha = (40 + (pulse * 70).toInt()).coerceIn(0, 255)
            val expand = dp(2f + pulse * 2.5f)
            val haloRect = RectF(boxRect.left - expand, boxRect.top - expand, boxRect.right + expand, boxRect.bottom + expand)
            canvas.drawRoundRect(haloRect, dp(16f), dp(16f), strokePaint)
        }
        podPaint.color = if (isFlowing) colorPrimaryContainer else colorSurfaceHigh
        canvas.drawRoundRect(boxRect, dp(14f), dp(14f), podPaint)
        titlePaint.color = if (isFlowing) colorOnPrimaryContainer else colorOnSurface
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⚡ 双擎核", boxRect.centerX(), boxRect.top + dp(18f), titlePaint)
        subPaint.color = if (isFlowing) colorOnPrimaryContainer else colorOnVariant
        subPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(hubSub, boxRect.centerX(), boxRect.top + dp(33f), subPaint)

        // 6. Draw Output Tunnel Pod (Right)
        boxRect.set(outX, outY, outX + outW, outY + outH)
        podPaint.color = colorSurfaceHigh
        canvas.drawRoundRect(boxRect, dp(12f), dp(12f), podPaint)
        titlePaint.color = colorOnSurface
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🛡️ 隧道", boxRect.centerX(), boxRect.top + dp(17f), titlePaint)
        subPaint.color = colorOnVariant
        subPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (isFlowing) "并发输出" else "未连接", boxRect.centerX(), boxRect.top + dp(31f), subPaint)
    }

    private fun drawTrackAndParticles(
        canvas: Canvas,
        path: Path,
        weight: Float,
        activeColor: Int,
        idleColor: Int,
        phaseOffset: Float,
    ) {
        if (weight <= 0.02f) {
            trackPaint.color = idleColor
            trackPaint.alpha = 90
            trackPaint.strokeWidth = dp(1.5f)
            trackPaint.pathEffect = dashEffect
            canvas.drawPath(path, trackPaint)
            trackPaint.pathEffect = null
            return
        }

        // Base glowing rail
        trackPaint.pathEffect = null
        trackPaint.color = activeColor
        trackPaint.alpha = (55 + (weight * 95).toInt()).coerceIn(40, 170)
        trackPaint.strokeWidth = dp(1.8f + weight * 1.6f)
        canvas.drawPath(path, trackPaint)

        // Flowing energy particles along path
        pathMeasure.setPath(path, false)
        val len = pathMeasure.length
        if (len <= 1f) return

        val count = if (weight >= 0.45f) 3 else 2
        val radius = dp(2.4f + weight * 1.6f)
        particlePaint.color = activeColor
        for (i in 0 until count) {
            val t = ((phase + phaseOffset + i.toFloat() / count) % 1f)
            if (pathMeasure.getPosTan(t * len, pos, tan)) {
                // Soft outer glow
                particlePaint.alpha = 85
                canvas.drawCircle(pos[0], pos[1], radius * 1.7f, particlePaint)
                // Bright core particle
                particlePaint.alpha = 245
                canvas.drawCircle(pos[0], pos[1], radius, particlePaint)
            }
        }
    }
}
