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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors

/**
 * Animated convergence view showing multiple active links (Wi-Fi, Cellular, LAN Peers)
 * merging smoothly into a single unified high-speed tunnel.
 */
class DualMergeFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class LinkStream(
        val label: String,
        val share: Float,
        val kind: Int, // 0 = Wi-Fi, 1 = Cellular, 2 = LAN Peer
    )

    private var flowing = false
    private var links: List<LinkStream> = listOf(
        LinkStream("Wi-Fi", 0.5f, 0),
        LinkStream("5G", 0.5f, 1),
    )

    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val colorPrimary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
    private val colorTertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, 0xFF7D5260.toInt())
    private val colorSecondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, 0xFF625B71.toInt())
    private val colorTrack = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt())

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val pathMeasure = PathMeasure()
    private val pos = FloatArray(2)

    fun updateLinks(flowing: Boolean, newLinks: List<LinkStream>) {
        this.flowing = flowing
        if (newLinks.isNotEmpty()) {
            this.links = newLinks
        }
        if (flowing && isAttachedToWindow && visibility == VISIBLE) {
            ensureAnimator()
        } else {
            stopAnimator()
            invalidate()
        }
    }

    private fun ensureAnimator() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (flowing && visibility == VISIBLE) ensureAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && flowing && isAttachedToWindow) {
            ensureAnimator()
        } else {
            stopAnimator()
        }
    }

    private fun colorForKind(kind: Int): Int = when (kind) {
        0 -> colorPrimary
        1 -> colorTertiary
        else -> colorSecondary
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val startX = 12f * density
        val mergeX = w * 0.60f
        val endX = w - 12f * density
        val mergeY = h * 0.5f

        val count = links.size.coerceAtLeast(1)
        val vPad = 8f * density
        val usableH = (h - vPad * 2f).coerceAtLeast(1f)

        // 1. Draw each source branch curving into the merge node
        links.forEachIndexed { idx, link ->
            val srcY = if (count == 1) mergeY else vPad + usableH * (idx.toFloat() / (count - 1))
            val linkColor = colorForKind(link.kind)
            val activeAlpha = if (flowing) (110 + (link.share.coerceIn(0f, 1f) * 145)).toInt() else 70

            // Base track
            path.reset()
            path.moveTo(startX, srcY)
            path.cubicTo(
                startX + (mergeX - startX) * 0.55f, srcY,
                startX + (mergeX - startX) * 0.45f, mergeY,
                mergeX, mergeY,
            )

            trackPaint.color = colorTrack
            trackPaint.alpha = 140
            trackPaint.strokeWidth = 2.5f * density
            canvas.drawPath(path, trackPaint)

            trackPaint.color = linkColor
            trackPaint.alpha = activeAlpha
            trackPaint.strokeWidth = (2.0f + link.share.coerceIn(0f, 1f) * 1.8f) * density
            canvas.drawPath(path, trackPaint)

            // Source dot
            nodePaint.color = linkColor
            nodePaint.alpha = if (flowing) 255 else 130
            canvas.drawCircle(startX, srcY, 3.5f * density, nodePaint)

            // Moving pulses along branch -> merge point
            if (flowing && link.share > 0.01f) {
                pathMeasure.setPath(path, false)
                val len = pathMeasure.length
                val pulses = 2
                for (p in 0 until pulses) {
                    val t = (phase + p.toFloat() / pulses + idx * 0.17f) % 1f
                    if (pathMeasure.getPosTan(t * len, pos, null)) {
                        pulsePaint.color = linkColor
                        pulsePaint.alpha = 235
                        canvas.drawCircle(pos[0], pos[1], 3.2f * density, pulsePaint)
                    }
                }
            }
        }

        // 2. Draw unified merged trunk (mergeX -> endX)
        trackPaint.color = colorPrimary
        trackPaint.alpha = if (flowing) 220 else 90
        trackPaint.strokeWidth = (if (flowing) 4.5f else 3.0f) * density
        canvas.drawLine(mergeX, mergeY, endX, mergeY, trackPaint)

        // Merged pulses shooting out of the merge node
        if (flowing) {
            val trunkLen = endX - mergeX
            val trunkPulses = 3
            for (p in 0 until trunkPulses) {
                val t = (phase * 1.4f + p.toFloat() / trunkPulses) % 1f
                val px = mergeX + t * trunkLen
                pulsePaint.color = colorPrimary
                pulsePaint.alpha = ((1f - t * 0.35f) * 255).toInt()
                canvas.drawCircle(px, mergeY, 3.8f * density, pulsePaint)
            }
        }

        // 3. Central merge hub with subtle breathing halo
        val haloScale = if (flowing) 1f + 0.25f * kotlin.math.sin(phase * 2f * Math.PI).toFloat() else 1f
        nodePaint.color = colorPrimary
        nodePaint.alpha = if (flowing) 55 else 30
        canvas.drawCircle(mergeX, mergeY, 9f * density * haloScale, nodePaint)

        nodePaint.alpha = if (flowing) 255 else 140
        canvas.drawCircle(mergeX, mergeY, 4.8f * density, nodePaint)

        // Output arrow head at endX
        nodePaint.color = colorPrimary
        nodePaint.alpha = if (flowing) 255 else 120
        canvas.drawCircle(endX, mergeY, 3.5f * density, nodePaint)
    }
}
