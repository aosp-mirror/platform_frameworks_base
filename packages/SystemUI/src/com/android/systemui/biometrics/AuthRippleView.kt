/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.biometrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.MathUtils
import android.view.View
import android.view.animation.PathInterpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.statusbar.charging.RippleShader

private const val RIPPLE_ANIMATION_DURATION: Long = 1533
private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.4f
private const val RIPPLE_VIBRATION_PRIMITIVE: Int = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
private const val RIPPLE_VIBRATION_SIZE: Int = 60
private const val RIPPLE_VIBRATION_SCALE_START: Float = 0.6f
private const val RIPPLE_VIBRATION_SCALE_DECAY: Float = -0.1f

/**
 * Expanding ripple effect on the transition from biometric authentication success to showing
 * launcher.
 */
class AuthRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val vibrator: Vibrator? = context?.getSystemService(Vibrator::class.java)
    private var rippleInProgress: Boolean = false
    private val rippleShader = RippleShader()
    private val ripplePaint = Paint()
    private val rippleVibrationEffect = createVibrationEffect(vibrator)
    private val rippleVibrationAttrs =
            AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()

    init {
        rippleShader.color = 0xffffffff.toInt() // default color
        rippleShader.progress = 0f
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        ripplePaint.shader = rippleShader
        visibility = GONE
    }

    fun setSensorLocation(location: PointF) {
        rippleShader.origin = location
        rippleShader.radius = maxOf(location.x, location.y, width - location.x, height - location.y)
            .toFloat()
    }

    fun startRipple(onAnimationEnd: Runnable?) {
        if (rippleInProgress) {
            return // Ignore if ripple effect is already playing
        }

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
        animator.duration = RIPPLE_ANIMATION_DURATION
        animator.addUpdateListener { animator ->
            val now = animator.currentPlayTime
            rippleShader.progress = animator.animatedValue as Float
            rippleShader.time = now.toFloat()
            rippleShader.distortionStrength = 1 - rippleShader.progress
            invalidate()
        }
        val alphaInAnimator = ValueAnimator.ofInt(0, 127)
        alphaInAnimator.duration = 167
        alphaInAnimator.addUpdateListener { alphaInAnimator ->
            rippleShader.color = ColorUtils.setAlphaComponent(rippleShader.color,
                alphaInAnimator.animatedValue as Int)
            invalidate()
        }
        val alphaOutAnimator = ValueAnimator.ofInt(127, 0)
        alphaOutAnimator.startDelay = 417
        alphaOutAnimator.duration = 1116
        alphaOutAnimator.addUpdateListener { alphaOutAnimator ->
            rippleShader.color = ColorUtils.setAlphaComponent(rippleShader.color,
                alphaOutAnimator.animatedValue as Int)
            invalidate()
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(animator, alphaInAnimator, alphaOutAnimator)
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                onAnimationEnd?.run()
                rippleInProgress = false
                visibility = GONE
            }
        })
        // TODO (b/185124905):  custom haptic TBD
        // vibrate()
        animatorSet.start()
        visibility = VISIBLE
        rippleInProgress = true
    }

    fun setColor(color: Int) {
        rippleShader.color = color
    }

    override fun onDraw(canvas: Canvas?) {
        // draw over the entire screen
        canvas?.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ripplePaint)
    }

    private fun vibrate() {
        if (rippleVibrationEffect != null) {
            vibrator?.vibrate(rippleVibrationEffect, rippleVibrationAttrs)
        }
    }

    private fun createVibrationEffect(vibrator: Vibrator?): VibrationEffect? {
        if (vibrator?.areAllPrimitivesSupported(RIPPLE_VIBRATION_PRIMITIVE) == false) {
            return null
        }
        val composition = VibrationEffect.startComposition()
        for (i in 0 until RIPPLE_VIBRATION_SIZE) {
            val scale =
                    RIPPLE_VIBRATION_SCALE_START * MathUtils.exp(RIPPLE_VIBRATION_SCALE_DECAY * i)
            composition.addPrimitive(RIPPLE_VIBRATION_PRIMITIVE, scale, 0 /* delay */)
        }
        return composition.compose()
    }
}
