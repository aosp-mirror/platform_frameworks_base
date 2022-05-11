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

package com.android.systemui.statusbar.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.3f

/**
 * Expanding ripple effect that shows when charging begins.
 */
class ChargingRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val rippleShader = RippleShader()
    private val defaultColor: Int = 0xffffffff.toInt()
    private val ripplePaint = Paint()

    var rippleInProgress: Boolean = false
    var radius: Float = 0.0f
        set(value) {
            rippleShader.radius = value
            field = value
        }
    var origin: PointF = PointF()
        set(value) {
            rippleShader.origin = value
            field = value
        }
    var duration: Long = 1750

    init {
        rippleShader.color = defaultColor
        rippleShader.progress = 0f
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        ripplePaint.shader = rippleShader
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        rippleShader.pixelDensity = resources.displayMetrics.density
        super.onConfigurationChanged(newConfig)
    }

    override fun onAttachedToWindow() {
        rippleShader.pixelDensity = resources.displayMetrics.density
        super.onAttachedToWindow()
    }

    @JvmOverloads
    fun startRipple(onAnimationEnd: Runnable? = null) {
        if (rippleInProgress) {
            return // Ignore if ripple effect is already playing
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.addUpdateListener { animator ->
            val now = animator.currentPlayTime
            val progress = animator.animatedValue as Float
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

    fun setColor(color: Int) {
        rippleShader.color = color
    }

    override fun onDraw(canvas: Canvas?) {
        if (canvas == null || !canvas.isHardwareAccelerated) {
            // Drawing with the ripple shader requires hardware acceleration, so skip
            // if it's unsupported.
            return
        }
        // To reduce overdraw, we mask the effect to a circle whose radius is big enough to cover
        // the active effect area. Values here should be kept in sync with the
        // animation implementation in the ripple shader.
        val maskRadius = (1 - (1 - rippleShader.progress) * (1 - rippleShader.progress) *
                (1 - rippleShader.progress)) * radius * 2
        canvas?.drawCircle(origin.x, origin.y, maskRadius, ripplePaint)
    }
}
