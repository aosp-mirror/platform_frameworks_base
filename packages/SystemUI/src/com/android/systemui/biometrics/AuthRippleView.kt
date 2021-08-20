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
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.charging.RippleShader

private const val RIPPLE_ANIMATION_DURATION: Long = 1533
private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.4f

/**
 * Expanding ripple effect on the transition from biometric authentication success to showing
 * launcher.
 */
class AuthRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var alphaInDuration: Long = 0
    private var rippleInProgress: Boolean = false
    private val rippleShader = RippleShader()
    private val ripplePaint = Paint()
    private var radius: Float = 0.0f
        set(value) {
            rippleShader.radius = value
            field = value
        }
    private var origin: PointF = PointF()
        set(value) {
            rippleShader.origin = value
            field = value
        }

    init {
        rippleShader.color = 0xffffffff.toInt() // default color
        rippleShader.progress = 0f
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        ripplePaint.shader = rippleShader
        visibility = GONE
    }

    fun setSensorLocation(location: PointF) {
        origin = location
        radius = maxOf(location.x, location.y, width - location.x, height - location.y)
            .toFloat()
    }

    fun setAlphaInDuration(duration: Long) {
        alphaInDuration = duration
    }

    fun startRipple(onAnimationEnd: Runnable?, lightReveal: LightRevealScrim?) {
        if (rippleInProgress) {
            return // Ignore if ripple effect is already playing
        }

        val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = PathInterpolator(0f, 0f, .2f, 1f)
            duration = RIPPLE_ANIMATION_DURATION
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                rippleShader.progress = animator.animatedValue as Float
                rippleShader.time = now.toFloat()

                invalidate()
            }
        }

        val revealAnimator = ValueAnimator.ofFloat(.1f, 1f).apply {
            interpolator = rippleAnimator.interpolator
            duration = rippleAnimator.duration
            addUpdateListener { animator ->
                lightReveal?.revealAmount = animator.animatedValue as Float
            }
        }

        val alphaInAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = alphaInDuration
            addUpdateListener { animator ->
                rippleShader.color = ColorUtils.setAlphaComponent(
                    rippleShader.color,
                    animator.animatedValue as Int
                )
                invalidate()
            }
        }

        val animatorSet = AnimatorSet().apply {
            playTogether(
                rippleAnimator,
                revealAnimator,
                alphaInAnimator
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    rippleInProgress = true
                    visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    onAnimationEnd?.run()
                    rippleInProgress = false
                    visibility = GONE
                }
            })
        }
        animatorSet.start()
    }

    fun setColor(color: Int) {
        rippleShader.color = color
    }

    override fun onDraw(canvas: Canvas?) {
        // To reduce overdraw, we mask the effect to a circle whose radius is big enough to cover
        // the active effect area. Values here should be kept in sync with the
        // animation implementation in the ripple shader.
        val maskRadius = (1 - (1 - rippleShader.progress) * (1 - rippleShader.progress) *
            (1 - rippleShader.progress)) * radius * 2f
        canvas?.drawCircle(origin.x, origin.y, maskRadius, ripplePaint)
    }
}
