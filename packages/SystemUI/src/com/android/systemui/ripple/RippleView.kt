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

package com.android.systemui.ripple

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.systemui.ripple.RippleShader.RippleShape

private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.3f
private const val RIPPLE_DEFAULT_COLOR: Int = 0xffffffff.toInt()
const val RIPPLE_DEFAULT_ALPHA: Int = 45

/**
 * A generic expanding ripple effect.
 *
 * Set up the shader with a desired [RippleShape] using [setupShader], [setMaxSize] and [setCenter],
 * then call [startRipple] to trigger the ripple expansion.
 */
open class RippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private lateinit var rippleShader: RippleShader
    lateinit var rippleShape: RippleShape
        private set

    private val ripplePaint = Paint()

    var rippleInProgress: Boolean = false
    var duration: Long = 1750

    private var maxWidth: Float = 0.0f
    private var maxHeight: Float = 0.0f
    fun setMaxSize(maxWidth: Float, maxHeight: Float) {
        this.maxWidth = maxWidth
        this.maxHeight = maxHeight
        rippleShader.setMaxSize(maxWidth, maxHeight)
    }

    private var centerX: Float = 0.0f
    private var centerY: Float = 0.0f
    fun setCenter(x: Float, y: Float) {
        this.centerX = x
        this.centerY = y
        rippleShader.setCenter(x, y)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        rippleShader.pixelDensity = resources.displayMetrics.density
        super.onConfigurationChanged(newConfig)
    }

    override fun onAttachedToWindow() {
        rippleShader.pixelDensity = resources.displayMetrics.density
        super.onAttachedToWindow()
    }

    /** Initializes the shader. Must be called before [startRipple]. */
    fun setupShader(rippleShape: RippleShape = RippleShape.CIRCLE) {
        this.rippleShape = rippleShape
        rippleShader = RippleShader(rippleShape)

        rippleShader.color = RIPPLE_DEFAULT_COLOR
        rippleShader.progress = 0f
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        rippleShader.pixelDensity = resources.displayMetrics.density

        ripplePaint.shader = rippleShader
    }

    @JvmOverloads
    fun startRipple(onAnimationEnd: Runnable? = null) {
        if (rippleInProgress) {
            return // Ignore if ripple effect is already playing
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.addUpdateListener { updateListener ->
            val now = updateListener.currentPlayTime
            val progress = updateListener.animatedValue as Float
            rippleShader.progress = progress
            rippleShader.distortionStrength = 1 - progress
            rippleShader.time = now.toFloat()
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                rippleInProgress = false
                onAnimationEnd?.run()
            }
        })
        animator.start()
        rippleInProgress = true
    }

    /** Set the color to be used for the ripple.
     *
     * The alpha value of the color will be applied to the ripple. The alpha range is [0-100].
     */
    fun setColor(color: Int, alpha: Int = RIPPLE_DEFAULT_ALPHA) {
        rippleShader.color = ColorUtils.setAlphaComponent(color, alpha)
    }

    /**
     * Set whether the ripple should remain filled as the ripple expands.
     *
     * See [RippleShader.rippleFill].
     */
    fun setRippleFill(rippleFill: Boolean) {
        rippleShader.rippleFill = rippleFill
    }

    /**
     * Set the intensity of the sparkles.
     */
    fun setSparkleStrength(strength: Float) {
        rippleShader.sparkleStrength = strength
    }

    override fun onDraw(canvas: Canvas?) {
        if (canvas == null || !canvas.isHardwareAccelerated) {
            // Drawing with the ripple shader requires hardware acceleration, so skip
            // if it's unsupported.
            return
        }
        // To reduce overdraw, we mask the effect to a circle or a rectangle that's bigger than the
        // active effect area. Values here should be kept in sync with the animation implementation
        // in the ripple shader.
        if (rippleShape == RippleShape.CIRCLE) {
            val maskRadius = (1 - (1 - rippleShader.progress) * (1 - rippleShader.progress) *
                    (1 - rippleShader.progress)) * maxWidth
            canvas.drawCircle(centerX, centerY, maskRadius, ripplePaint)
        } else {
            val maskWidth = (1 - (1 - rippleShader.progress) * (1 - rippleShader.progress) *
                    (1 - rippleShader.progress)) * maxWidth * 2
            val maskHeight = (1 - (1 - rippleShader.progress) * (1 - rippleShader.progress) *
                    (1 - rippleShader.progress)) * maxHeight * 2
            canvas.drawRect(
                    /* left= */ centerX - maskWidth,
                    /* top= */ centerY - maskHeight,
                    /* right= */ centerX + maskWidth,
                    /* bottom= */ centerY + maskHeight,
                    ripplePaint)
        }
    }
}
