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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Material You (Monet) energy-injection view for Co-op Network (协同网络).
 * A central Internet orb receives animated energy streams from surrounding Wi-Fi,
 * Cellular, and LAN peer device nodes, each rendered with Monet dynamic color roles.
 */
class CoNetEnergyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class LinkNode(
        val label: String,
        val share: Float,
        val kind: Int, // 0 = Wi-Fi, 1 = Cellular, 2 = LAN Peer
    )

    private var flowing = false
    private var nodes: List<LinkNode> = listOf(
        LinkNode("Wi-Fi", 0.5f, 0),
        LinkNode("5G", 0.5f, 1),
    )

    private var phase = 0f
    private var animator: ValueAnimator? = null

    // Monet dynamic color roles
    private val colorPrimary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
    private val colorPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF.toInt())
    private val colorOnPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D.toInt())

    private val colorTertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, 0xFF7D5260.toInt())
    private val colorTertiaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4.toInt())
    private val colorOnTertiaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D.toInt())

    private val colorSecondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, 0xFF625B71.toInt())
    private val colorSecondaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt())
    private val colorOnSecondaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt())

    private val colorOutlineVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt())
    private val colorOnSurfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())

    private val iconGlobe: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_home_globe)?.mutate()
    private val iconWifi: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_home_wifi)?.mutate()
    private val iconCell: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_home_cellular)?.mutate()
    private val iconDevice: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_home_device)?.mutate()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorOnSurfaceVariant
    }

    private val path = Path()
    private val pathMeasure = PathMeasure()
    private val pos = FloatArray(2)

    fun updateLinks(flowing: Boolean, newNodes: List<LinkNode>) {
        this.flowing = flowing
        if (newNodes.isNotEmpty()) {
            this.nodes = newNodes
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
            duration = 1800L
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

    private fun colorsForNode(kind: Int, peerIndex: Int): Triple<Int, Int, Int> {
        return when (kind) {
            0 -> Triple(colorPrimary, colorPrimaryContainer, colorOnPrimaryContainer)
            1 -> Triple(colorTertiary, colorTertiaryContainer, colorOnTertiaryContainer)
            else -> {
                // Harmonious Monet variation if multiple LAN devices are present
                val accent = when (peerIndex % 3) {
                    0 -> colorSecondary
                    1 -> MaterialColors.layer(colorSecondary, colorTertiary, 0.38f)
                    else -> MaterialColors.layer(colorSecondary, colorPrimary, 0.38f)
                }
                Triple(accent, colorSecondaryContainer, colorOnSecondaryContainer)
            }
        }
    }

    private fun iconForKind(kind: Int): Drawable? = when (kind) {
        0 -> iconWifi
        1 -> iconCell
        else -> iconDevice
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val cx = w * 0.5f
        val cy = h * 0.48f
        val hubRadius = min(w, h) * 0.21f
        val nodeRadius = 15f * density
        val orbitRx = (w * 0.36f).coerceAtLeast(hubRadius + nodeRadius + 24f * density)
        val orbitRy = (h * 0.34f).coerceAtLeast(hubRadius + nodeRadius + 10f * density)

        labelPaint.textSize = 10.5f * density

        val count = nodes.size.coerceAtLeast(1)
        var peerIdx = 0

        // Compute outer node positions: spread left & right arcs symmetrically around center
        val positions = ArrayList<Pair<Float, Float>>(count)
        for (i in 0 until count) {
            val angleDeg = when (count) {
                1 -> 180.0
                2 -> if (i == 0) 180.0 else 0.0
                3 -> when (i) {
                    0 -> 195.0
                    1 -> 345.0
                    else -> 90.0
                }
                else -> {
                    // Distribute around circle starting from left (180 deg)
                    180.0 + (360.0 * i / count)
                }
            }
            val rad = Math.toRadians(angleDeg)
            val nx = cx + (orbitRx * cos(rad)).toFloat()
            val ny = cy + (orbitRy * sin(rad)).toFloat()
            positions.add(nx to ny)
        }

        // 1. Draw energy conduits and animated particles from each outer node -> center Internet orb
        nodes.forEachIndexed { idx, node ->
            val (nx, ny) = positions[idx]
            val pIdx = if (node.kind == 2) peerIdx++ else 0
            val (accent, _, _) = colorsForNode(node.kind, pIdx)

            // Gentle curve toward center
            val ctrlX = (nx + cx) * 0.5f + (cy - ny) * 0.14f
            val ctrlY = (ny + cy) * 0.5f - (cx - nx) * 0.08f
            path.reset()
            path.moveTo(nx, ny)
            path.quadTo(ctrlX, ctrlY, cx, cy)

            strokePaint.color = colorOutlineVariant
            strokePaint.alpha = 110
            strokePaint.strokeWidth = 2.0f * density
            canvas.drawPath(path, strokePaint)

            val shareClamped = node.share.coerceIn(0f, 1f)
            strokePaint.color = accent
            strokePaint.alpha = if (flowing) (100 + (shareClamped * 140)).toInt() else 60
            strokePaint.strokeWidth = (1.8f + shareClamped * 2.0f) * density
            canvas.drawPath(path, strokePaint)

            if (flowing && shareClamped > 0.01f) {
                pathMeasure.setPath(path, false)
                val len = pathMeasure.length
                val particleCount = if (shareClamped >= 0.4f) 3 else 2
                for (p in 0 until particleCount) {
                    val t = (phase + p.toFloat() / particleCount + idx * 0.19f) % 1f
                    // Fade slightly as it enters the center hub
                    if (pathMeasure.getPosTan(t * len, pos, null)) {
                        particlePaint.color = accent
                        particlePaint.alpha = ((1f - t * 0.25f) * 245).toInt()
                        val r = (2.8f + shareClamped * 1.4f) * density
                        canvas.drawCircle(pos[0], pos[1], r, particlePaint)
                    }
                }
            }
        }

        // 2. Draw outer satellite nodes (Wi-Fi, 5G, LAN Devices)
        peerIdx = 0
        nodes.forEachIndexed { idx, node ->
            val (nx, ny) = positions[idx]
            val pIdx = if (node.kind == 2) peerIdx++ else 0
            val (accent, container, onContainer) = colorsForNode(node.kind, pIdx)

            fillPaint.color = container
            fillPaint.alpha = if (flowing) 255 else 180
            canvas.drawCircle(nx, ny, nodeRadius, fillPaint)

            strokePaint.color = accent
            strokePaint.alpha = if (flowing) 220 else 100
            strokePaint.strokeWidth = 1.6f * density
            canvas.drawCircle(nx, ny, nodeRadius, strokePaint)

            iconForKind(node.kind)?.let { d ->
                DrawableCompat.setTint(d, onContainer)
                val iconSize = (15f * density).toInt()
                val half = iconSize / 2
                d.setBounds(
                    (nx - half).toInt(),
                    (ny - half).toInt(),
                    (nx + half).toInt(),
                    (ny + half).toInt(),
                )
                d.draw(canvas)
            }

            // Short label under node
            val shortName = if (node.label.length > 10) node.label.take(9) + "…" else node.label
            canvas.drawText(shortName, nx, ny + nodeRadius + 11f * density, labelPaint)
        }

        // 3. Draw central Internet orb with Monet breathing energy halo
        if (flowing) {
            val pulse = 0.5f + 0.5f * sin(phase * 2.0 * Math.PI).toFloat()
            fillPaint.color = colorPrimary
            fillPaint.alpha = (28 + pulse * 36).toInt()
            canvas.drawCircle(cx, cy, hubRadius + (6f + pulse * 5f) * density, fillPaint)
        }

        fillPaint.color = colorPrimaryContainer
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, hubRadius, fillPaint)

        strokePaint.color = colorPrimary
        strokePaint.alpha = if (flowing) 230 else 120
        strokePaint.strokeWidth = 2.2f * density
        canvas.drawCircle(cx, cy, hubRadius, strokePaint)

        iconGlobe?.let { d ->
            DrawableCompat.setTint(d, colorOnPrimaryContainer)
            val iconSize = (hubRadius * 1.05f).toInt()
            val half = iconSize / 2
            d.setBounds(
                (cx - half).toInt(),
                (cy - half).toInt(),
                (cx + half).toInt(),
                (cy + half).toInt(),
            )
            d.draw(canvas)
        }
    }
}
