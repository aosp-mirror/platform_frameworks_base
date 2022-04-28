package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.MathUtils.lerp
import android.util.MathUtils.lerpInv
import android.util.MathUtils.lerpInvSat
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

    /* distance over which amplitude drops to zero, measured in wavelengths */
    private val transitionPeriods = 1.5f
    /* wave endpoint as percentage of bar when play position is zero */
    private val minWaveEndpoint = 0.2f
    /* wave endpoint as percentage of bar when play position matches wave endpoint */
    private val matchedWaveEndpoint = 0.6f

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

    var transitionEnabled = true
        set(value) {
            field = value
            invalidateSelf()
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
            phaseOffset += (now - lastFrameTime) / 1000f * phaseSpeed
            phaseOffset %= waveLength
            lastFrameTime = now
        }

        val progress = level / 10_000f
        val totalProgressPx = bounds.width() * progress
        val waveProgressPx = bounds.width() * (
            if (!transitionEnabled || progress > matchedWaveEndpoint) progress else
            lerp(minWaveEndpoint, matchedWaveEndpoint, lerpInv(0f, matchedWaveEndpoint, progress)))

        // Build Wiggly Path
        val waveStart = -phaseOffset
        val waveEnd = waveProgressPx
        val transitionLength = if (transitionEnabled) transitionPeriods * waveLength else 0.01f

        // helper function, computes amplitude for wave segment
        val computeAmplitude: (Float, Float) -> Float = { x, sign ->
            sign * heightFraction * lineAmplitude *
                    lerpInvSat(waveEnd, waveEnd - transitionLength, x)
        }

        var currentX = waveEnd
        var waveSign = if (phaseOffset < waveLength / 2) 1f else -1f
        path.rewind()

        // Draw flat line from end to wave endpoint
        path.moveTo(bounds.width().toFloat(), 0f)
        path.lineTo(waveEnd, 0f)

        // First wave has shortened wavelength
        // approx quarter wave gets us to first wave peak
        // shouldn't be big enough to notice it's not a sin wave
        currentX -= phaseOffset % (waveLength / 2)
        val controlRatio = 0.25f
        var currentAmp = computeAmplitude(currentX, waveSign)
        path.cubicTo(
            waveEnd, currentAmp * controlRatio,
            lerp(currentX, waveEnd, controlRatio), currentAmp,
            currentX, currentAmp)

        // Other waves have full wavelength
        val dist = -1 * waveLength / 2f
        while (currentX > waveStart) {
            waveSign = -waveSign
            val nextX = currentX + dist
            val midX = currentX + dist / 2
            val nextAmp = computeAmplitude(nextX, waveSign)
            path.cubicTo(
                midX, currentAmp,
                midX, nextAmp,
                nextX, nextAmp)
            currentAmp = nextAmp
            currentX = nextX
        }

        // Draw path; clip to progress position
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.centerY().toFloat())
        canvas.clipRect(
                0f,
                -lineAmplitude - strokeWidth,
                totalProgressPx,
                lineAmplitude + strokeWidth)
        canvas.drawPath(path, wavePaint)
        canvas.restore()

        // Draw path; clip between progression position & far edge
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.centerY().toFloat())
        canvas.clipRect(
                totalProgressPx,
                -lineAmplitude - strokeWidth,
                bounds.width().toFloat(),
                lineAmplitude + strokeWidth)
        canvas.drawPath(path, linePaint)
        canvas.restore()

        // Draw round line cap at the beginning of the wave
        val startAmp = cos(abs(waveEnd - phaseOffset) / waveLength * TWO_PI)
        canvas.drawPoint(
                bounds.left.toFloat(),
                bounds.centerY() + startAmp * lineAmplitude * heightFraction,
                wavePaint)
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