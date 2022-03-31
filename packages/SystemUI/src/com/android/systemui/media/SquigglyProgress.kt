package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.android.internal.graphics.ColorUtils
import com.android.systemui.animation.Interpolators
import kotlin.math.abs
import kotlin.math.cos

private const val TAG = "Squiggly"

private const val TWO_PI = (Math.PI * 2f).toFloat()
@VisibleForTesting
internal const val DISABLED_ALPHA = 77

class SquigglyProgress : Drawable() {

    private val wavePaint = Paint()
    private val linePaint = Paint()
    private val path = Path()
    private var heightFraction = 0f
    private var heightAnimator: ValueAnimator? = null
    private var phaseOffset = 0f
    private var lastFrameTime = -1L

    // Horizontal length of the sine wave
    var waveLength = 0f
    // Height of each peak of the sine wave
    var lineAmplitude = 0f
    // Line speed in px per second
    var phaseSpeed = 0f
    // Progress stroke width, both for wave and solid line
    var strokeWidth = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            wavePaint.strokeWidth = value
            linePaint.strokeWidth = value
        }

    init {
        wavePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.style = Paint.Style.STROKE
        wavePaint.style = Paint.Style.STROKE
        linePaint.alpha = DISABLED_ALPHA
    }

    var animate: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (field) {
                lastFrameTime = SystemClock.uptimeMillis()
            }
            heightAnimator?.cancel()
            heightAnimator = ValueAnimator.ofFloat(heightFraction, if (animate) 1f else 0f).apply {
                if (animate) {
                    startDelay = 60
                    duration = 800
                    interpolator = Interpolators.EMPHASIZED_DECELERATE
                } else {
                    duration = 550
                    interpolator = Interpolators.STANDARD_DECELERATE
                }
                addUpdateListener {
                    heightFraction = it.animatedValue as Float
                    invalidateSelf()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        heightAnimator = null
                    }
                })
                start()
            }
        }

    override fun draw(canvas: Canvas) {
        if (animate) {
            invalidateSelf()
            val now = SystemClock.uptimeMillis()
            phaseOffset -= (now - lastFrameTime) / 1000f * phaseSpeed
            phaseOffset %= waveLength
            lastFrameTime = now
        }

        val totalProgressPx = (bounds.width() * (level / 10_000f))
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.centerY().toFloat())
        // Clip drawing, so we stop at the thumb
        canvas.clipRect(
                0f,
                -lineAmplitude - strokeWidth,
                totalProgressPx,
                lineAmplitude + strokeWidth)

        // The squiggly line
        val start = phaseOffset
        var currentX = start
        var waveSign = 1f
        path.rewind()
        path.moveTo(start, lineAmplitude * heightFraction)
        while (currentX < totalProgressPx) {
            val nextX = currentX + waveLength / 2f
            val nextWaveSign = waveSign * -1
            path.cubicTo(
                    currentX + waveLength / 4f, lineAmplitude * waveSign * heightFraction,
                    nextX - waveLength / 4f, lineAmplitude * nextWaveSign * heightFraction,
                    nextX, lineAmplitude * nextWaveSign * heightFraction)
            currentX = nextX
            waveSign = nextWaveSign
        }
        wavePaint.style = Paint.Style.STROKE
        canvas.drawPath(path, wavePaint)
        canvas.restore()

        // Draw round line cap at the beginning of the wave
        val startAmp = cos(abs(phaseOffset) / waveLength * TWO_PI)
        val p = Paint()
        p.color = Color.WHITE
        canvas.drawPoint(
                bounds.left.toFloat(),
                bounds.centerY() + startAmp * lineAmplitude * heightFraction,
                wavePaint)

        // Draw continuous line, to the right of the thumb
        canvas.drawLine(
                bounds.left.toFloat() + totalProgressPx,
                bounds.centerY().toFloat(),
                bounds.width().toFloat(),
                bounds.centerY().toFloat(),
                linePaint)
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wavePaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
    }

    override fun setAlpha(alpha: Int) {
        updateColors(wavePaint.color, alpha)
    }

    override fun getAlpha(): Int {
        return wavePaint.alpha
    }

    override fun setTint(tintColor: Int) {
        updateColors(tintColor, alpha)
    }

    override fun onLevelChange(level: Int): Boolean {
        return animate
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tint == null) {
            return
        }
        updateColors(tint.defaultColor, alpha)
    }

    private fun updateColors(tintColor: Int, alpha: Int) {
        wavePaint.color = ColorUtils.setAlphaComponent(tintColor, alpha)
        linePaint.color = ColorUtils.setAlphaComponent(tintColor,
                (DISABLED_ALPHA * (alpha / 255f)).toInt())
    }
}