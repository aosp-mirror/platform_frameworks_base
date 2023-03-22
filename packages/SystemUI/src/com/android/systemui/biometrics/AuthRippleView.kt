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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.animation.Interpolators
import com.android.systemui.surfaceeffects.ripple.RippleShader

private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.3f

/**
 * Handles two ripple effects: dwell ripple and unlocked ripple
 * Dwell Ripple:
 *     - startDwellRipple: dwell ripple expands outwards around the biometric area
 *     - retractDwellRipple: retracts the dwell ripple to radius 0 to signal a failure
 *     - fadeDwellRipple: fades the dwell ripple away to alpha 0
 * Unlocked ripple:
 *     - startUnlockedRipple: ripple expands from biometric auth location to the edges of the screen
 */
class AuthRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val retractInterpolator = PathInterpolator(.05f, .93f, .1f, 1f)

    private val dwellPulseDuration = 100L
    private val dwellExpandDuration = 2000L - dwellPulseDuration

    private var drawDwell: Boolean = false
    private var drawRipple: Boolean = false

    private var lockScreenColorVal = Color.WHITE
    private val fadeDuration = 83L
    private val retractDuration = 400L
    private val dwellShader = DwellRippleShader()
    private val dwellPaint = Paint()
    private val rippleShader = RippleShader()
    private val ripplePaint = Paint()
    private var unlockedRippleAnimator: Animator? = null
    private var fadeDwellAnimator: Animator? = null
    private var retractDwellAnimator: Animator? = null
    private var dwellPulseOutAnimator: Animator? = null
    private var dwellRadius: Float = 0f
        set(value) {
            dwellShader.maxRadius = value
            field = value
        }
    private var dwellOrigin: Point = Point()
        set(value) {
            dwellShader.origin = value
            field = value
        }
    private var radius: Float = 0f
        set(value) {
            field = value * .9f
            rippleShader.rippleSize.setMaxSize(field * 2f, field * 2f)
        }
    private var origin: Point = Point()
        set(value) {
            rippleShader.setCenter(value.x.toFloat(), value.y.toFloat())
            field = value
        }

    init {
        rippleShader.rawProgress = 0f
        rippleShader.pixelDensity = resources.displayMetrics.density
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        updateRippleFadeParams()
        ripplePaint.shader = rippleShader
        setLockScreenColor(0xffffffff.toInt()) // default color

        dwellShader.color = 0xffffffff.toInt() // default color
        dwellShader.progress = 0f
        dwellShader.distortionStrength = .4f
        dwellPaint.shader = dwellShader
        visibility = GONE
    }

    fun setSensorLocation(location: Point) {
        origin = location
        radius = maxOf(location.x, location.y, width - location.x, height - location.y).toFloat()
    }

    fun setFingerprintSensorLocation(location: Point, sensorRadius: Float) {
        origin = location
        radius = maxOf(location.x, location.y, width - location.x, height - location.y).toFloat()
        dwellOrigin = location
        dwellRadius = sensorRadius * 1.5f
    }

    /**
     * Animate dwell ripple inwards back to radius 0
     */
    fun retractDwellRipple() {
        if (retractDwellAnimator?.isRunning == true || fadeDwellAnimator?.isRunning == true) {
            return // let the animation finish
        }

        if (dwellPulseOutAnimator?.isRunning == true) {
            val retractDwellRippleAnimator = ValueAnimator.ofFloat(dwellShader.progress, 0f)
                    .apply {
                interpolator = retractInterpolator
                duration = retractDuration
                addUpdateListener { animator ->
                    val now = animator.currentPlayTime
                    dwellShader.progress = animator.animatedValue as Float
                    dwellShader.time = now.toFloat()

                    invalidate()
                }
            }

            val retractAlphaAnimator = ValueAnimator.ofInt(255, 0).apply {
                interpolator = Interpolators.LINEAR
                duration = retractDuration
                addUpdateListener { animator ->
                    dwellShader.color = ColorUtils.setAlphaComponent(
                            dwellShader.color,
                            animator.animatedValue as Int
                    )
                    invalidate()
                }
            }

            retractDwellAnimator = AnimatorSet().apply {
                playTogether(retractDwellRippleAnimator, retractAlphaAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        dwellPulseOutAnimator?.cancel()
                        drawDwell = true
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        drawDwell = false
                        resetDwellAlpha()
                    }
                })
                start()
            }
        }
    }

    /**
     * Animate ripple fade to alpha=0
     */
    fun fadeDwellRipple() {
        if (fadeDwellAnimator?.isRunning == true) {
            return // let the animation finish
        }

        if (dwellPulseOutAnimator?.isRunning == true || retractDwellAnimator?.isRunning == true) {
            fadeDwellAnimator = ValueAnimator.ofInt(Color.alpha(dwellShader.color), 0).apply {
                interpolator = Interpolators.LINEAR
                duration = fadeDuration
                addUpdateListener { animator ->
                    dwellShader.color = ColorUtils.setAlphaComponent(
                            dwellShader.color,
                            animator.animatedValue as Int
                    )
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        retractDwellAnimator?.cancel()
                        dwellPulseOutAnimator?.cancel()
                        drawDwell = true
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        drawDwell = false
                        resetDwellAlpha()
                    }
                })
                start()
            }
        }
    }

    /**
     * Plays a ripple animation that grows to the dwellRadius with distortion.
     */
    fun startDwellRipple(isDozing: Boolean) {
        if (unlockedRippleAnimator?.isRunning == true || dwellPulseOutAnimator?.isRunning == true) {
            return
        }

        updateDwellRippleColor(isDozing)

        val dwellPulseOutRippleAnimator = ValueAnimator.ofFloat(0f, .8f).apply {
            interpolator = Interpolators.LINEAR
            duration = dwellPulseDuration
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                dwellShader.progress = animator.animatedValue as Float
                dwellShader.time = now.toFloat()

                invalidate()
            }
        }

        // slowly animate outwards until we receive a call to retractRipple or startUnlockedRipple
        val expandDwellRippleAnimator = ValueAnimator.ofFloat(.8f, 1f).apply {
            interpolator = Interpolators.LINEAR_OUT_SLOW_IN
            duration = dwellExpandDuration
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                dwellShader.progress = animator.animatedValue as Float
                dwellShader.time = now.toFloat()

                invalidate()
            }
        }

        dwellPulseOutAnimator = AnimatorSet().apply {
            playSequentially(
                    dwellPulseOutRippleAnimator,
                    expandDwellRippleAnimator
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    retractDwellAnimator?.cancel()
                    fadeDwellAnimator?.cancel()
                    visibility = VISIBLE
                    drawDwell = true
                }

                override fun onAnimationEnd(animation: Animator?) {
                    drawDwell = false
                }
            })
            start()
        }
    }

    /**
     * Ripple that bursts outwards from the position of the sensor to the edges of the screen
     */
    fun startUnlockedRipple(onAnimationEnd: Runnable?) {
        unlockedRippleAnimator?.cancel()

        val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AuthRippleController.RIPPLE_ANIMATION_DURATION
            addUpdateListener { animator ->
                val now = animator.currentPlayTime
                rippleShader.rawProgress = animator.animatedValue as Float
                rippleShader.time = now.toFloat()

                invalidate()
            }
        }

        unlockedRippleAnimator = rippleAnimator.apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    drawRipple = true
                    visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    onAnimationEnd?.run()
                    drawRipple = false
                    visibility = GONE
                    unlockedRippleAnimator = null
                }
            })
        }
        unlockedRippleAnimator?.start()
    }

    fun setLockScreenColor(color: Int) {
        lockScreenColorVal = color
        rippleShader.color = ColorUtils.setAlphaComponent(
                lockScreenColorVal,
                62
        )
    }

    fun updateDwellRippleColor(isDozing: Boolean) {
        if (isDozing) {
            dwellShader.color = Color.WHITE
        } else {
            dwellShader.color = lockScreenColorVal
        }
        resetDwellAlpha()
    }

    fun resetDwellAlpha() {
        dwellShader.color = ColorUtils.setAlphaComponent(
                dwellShader.color,
                255
        )
    }

    private fun updateRippleFadeParams() {
        with(rippleShader) {
            baseRingFadeParams.fadeInStart = 0f
            baseRingFadeParams.fadeInEnd = .2f
            baseRingFadeParams.fadeOutStart = .2f
            baseRingFadeParams.fadeOutEnd = 1f

            centerFillFadeParams.fadeInStart = 0f
            centerFillFadeParams.fadeInEnd = .15f
            centerFillFadeParams.fadeOutStart = .15f
            centerFillFadeParams.fadeOutEnd = .56f
        }
    }

    override fun onDraw(canvas: Canvas?) {
        // To reduce overdraw, we mask the effect to a circle whose radius is big enough to cover
        // the active effect area. Values here should be kept in sync with the
        // animation implementation in the ripple shader. (Twice bigger)
        if (drawDwell) {
            val maskRadius = (1 - (1 - dwellShader.progress) * (1 - dwellShader.progress) *
                    (1 - dwellShader.progress)) * dwellRadius * 2f
            canvas?.drawCircle(dwellOrigin.x.toFloat(), dwellOrigin.y.toFloat(),
                    maskRadius, dwellPaint)
        }

        if (drawRipple) {
            canvas?.drawCircle(origin.x.toFloat(), origin.y.toFloat(),
                    rippleShader.rippleSize.currentWidth, ripplePaint)
        }
    }
}
