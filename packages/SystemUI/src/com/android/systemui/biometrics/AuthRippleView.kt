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
import com.android.internal.R.attr.interpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.animation.Interpolators
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.charging.RippleShader

private const val RIPPLE_ANIMATION_DURATION: Long = 1533
private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.4f

/**
 * Expanding ripple effect
 * - startUnlockedRipple for the transition from biometric authentication success to showing
 * launcher.
 * - startDwellRipple for the ripple expansion out when the user has their finger down on the UDFPS
 * sensor area
 * - retractRipple for the ripple animation inwards to signal a failure
 */
class AuthRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val retractInterpolator = PathInterpolator(.05f, .93f, .1f, 1f)
    private val dwellPulseDuration = 200L
    var dwellAlphaDuration = dwellPulseDuration
    private val dwellExpandDuration = 1200L - dwellPulseDuration
    private val retractDuration = 400L

    var dwellAlpha: Float = .5f
    private var alphaInDuration: Long = 0
    private var unlockedRippleInProgress: Boolean = false
    private val rippleShader = RippleShader()
    private val ripplePaint = Paint()
    private var retractAnimator: Animator? = null
    private var dwellPulseOutAnimator: Animator? = null
    private var radius: Float = 0f
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
        radius = maxOf(location.x, location.y, width - location.x, height - location.y).toFloat()
    }

    fun setAlphaInDuration(duration: Long) {
        alphaInDuration = duration
    }

    /**
     * Animate ripple inwards back to radius 0
     */
    fun retractRipple() {
        if (retractAnimator?.isRunning == true) {
            return // let the animation finish
        }

        if (dwellPulseOutAnimator?.isRunning == true) {
            val retractRippleAnimator = ValueAnimator.ofFloat(rippleShader.progress, 0f)
                    .apply {
                interpolator = retractInterpolator
                duration = retractDuration
                addUpdateListener { animator ->
                    val now = animator.currentPlayTime
                    rippleShader.progress = animator.animatedValue as Float
                    rippleShader.time = now.toFloat()

                    invalidate()
                }
            }

            val retractAlphaAnimator = ValueAnimator.ofInt(255, 0).apply {
                interpolator = Interpolators.LINEAR
                duration = retractDuration
                addUpdateListener { animator ->
                    rippleShader.color = ColorUtils.setAlphaComponent(
                            rippleShader.color,
                            animator.animatedValue as Int
                    )
                    invalidate()
                }
            }

            retractAnimator = AnimatorSet().apply {
                playTogether(retractRippleAnimator, retractAlphaAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        dwellPulseOutAnimator?.cancel()
                        rippleShader.shouldFadeOutRipple = false
                        visibility = VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        visibility = GONE
                        resetRippleAlpha()
                    }
                })
                start()
            }
        }
    }

    /**
     * Ripple that moves animates from an outer ripple ring of
     *      startRadius => endRadius => expandedRadius
     */
    fun startDwellRipple(startRadius: Float, endRadius: Float, expandedRadius: Float) {
        if (unlockedRippleInProgress || dwellPulseOutAnimator?.isRunning == true) {
            return
        }

        // we divide by 4 because the desired startRadius and endRadius is for the ripple's outer
        // ring see RippleShader
        val startDwellProgress = startRadius / radius / 4f
        val endInitialDwellProgress = endRadius / radius / 4f
        val endExpandDwellProgress = expandedRadius / radius / 4f

        val pulseOutEndAlpha = (255 * dwellAlpha).toInt()
        val expandDwellEndAlpha = kotlin.math.min((255 * (dwellAlpha + .25f)).toInt(), 255)
        val dwellPulseOutRippleAnimator = ValueAnimator.ofFloat(startDwellProgress,
                endInitialDwellProgress).apply {
            interpolator = Interpolators.LINEAR_OUT_SLOW_IN
            duration = dwellPulseDuration
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                rippleShader.progress = animator.animatedValue as Float
                rippleShader.time = now.toFloat()

                invalidate()
            }
        }

        val dwellPulseOutAlphaAnimator = ValueAnimator.ofInt(0, pulseOutEndAlpha).apply {
            interpolator = Interpolators.LINEAR
            duration = dwellAlphaDuration
            addUpdateListener { animator ->
                rippleShader.color = ColorUtils.setAlphaComponent(
                        rippleShader.color,
                        animator.animatedValue as Int
                )
                invalidate()
            }
        }

        // slowly animate outwards until we receive a call to retractRipple or startUnlockedRipple
        val expandDwellRippleAnimator = ValueAnimator.ofFloat(endInitialDwellProgress,
                endExpandDwellProgress).apply {
            interpolator = Interpolators.LINEAR_OUT_SLOW_IN
            duration = dwellExpandDuration
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                rippleShader.progress = animator.animatedValue as Float
                rippleShader.time = now.toFloat()

                invalidate()
            }
        }

        val expandDwellAlphaAnimator = ValueAnimator.ofInt(pulseOutEndAlpha, expandDwellEndAlpha)
                .apply {
            interpolator = Interpolators.LINEAR
            duration = dwellExpandDuration
            addUpdateListener { animator ->
                rippleShader.color = ColorUtils.setAlphaComponent(
                        rippleShader.color,
                        animator.animatedValue as Int
                )
                invalidate()
            }
        }

        val initialDwellPulseOutAnimator = AnimatorSet().apply {
            playTogether(dwellPulseOutRippleAnimator, dwellPulseOutAlphaAnimator)
        }
        val expandDwellAnimator = AnimatorSet().apply {
            playTogether(expandDwellRippleAnimator, expandDwellAlphaAnimator)
        }

        dwellPulseOutAnimator = AnimatorSet().apply {
            playSequentially(
                    initialDwellPulseOutAnimator,
                    expandDwellAnimator
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    retractAnimator?.cancel()
                    rippleShader.shouldFadeOutRipple = false
                    visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    visibility = GONE
                    resetRippleAlpha()
                }
            })
            start()
        }
    }

    /**
     * Ripple that bursts outwards from the position of the sensor to the edges of the screen
     */
    fun startUnlockedRipple(onAnimationEnd: Runnable?, lightReveal: LightRevealScrim?) {
        if (unlockedRippleInProgress) {
            return // Ignore if ripple effect is already playing
        }

        var rippleStart = 0f
        var alphaDuration = alphaInDuration
        if (dwellPulseOutAnimator?.isRunning == true || retractAnimator?.isRunning == true) {
            rippleStart = rippleShader.progress
            alphaDuration = 0
            dwellPulseOutAnimator?.cancel()
            retractAnimator?.cancel()
        }

        val rippleAnimator = ValueAnimator.ofFloat(rippleStart, 1f).apply {
            interpolator = Interpolators.LINEAR_OUT_SLOW_IN
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
            duration = alphaDuration
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
                    unlockedRippleInProgress = true
                    rippleShader.shouldFadeOutRipple = true
                    visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    onAnimationEnd?.run()
                    unlockedRippleInProgress = false
                    visibility = GONE
                }
            })
        }
        animatorSet.start()
    }

    fun resetRippleAlpha() {
        rippleShader.color = ColorUtils.setAlphaComponent(
                rippleShader.color,
                255
        )
    }

    fun setColor(color: Int) {
        rippleShader.color = color
        resetRippleAlpha()
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
