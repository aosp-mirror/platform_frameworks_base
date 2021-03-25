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
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.android.systemui.statusbar.charging.RippleShader

private const val RIPPLE_ANIMATION_DURATION: Long = 950
private const val RIPPLE_SPARKLE_STRENGTH: Float = 0.4f

/**
 * Expanding ripple effect on the transition from biometric authentication success to showing
 * launcher.
 */
class AuthRippleView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var rippleInProgress: Boolean = false
    private val rippleShader = RippleShader()
    private val defaultColor: Int = 0xffffffff.toInt()
    private val ripplePaint = Paint()

    init {
        rippleShader.color = defaultColor
        rippleShader.progress = 0f
        rippleShader.sparkleStrength = RIPPLE_SPARKLE_STRENGTH
        ripplePaint.shader = rippleShader
        visibility = View.GONE
    }

    fun setSensorLocation(x: Float, y: Float) {
        rippleShader.origin = PointF(x, y)
        rippleShader.radius = maxOf(x, y, width - x, height - y).toFloat()
    }

    fun startRipple() {
        if (rippleInProgress) {
            return // Ignore if ripple effect is already playing
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = RIPPLE_ANIMATION_DURATION
        animator.addUpdateListener { animator ->
            val now = animator.currentPlayTime
            val phase = now / 30000f
            rippleShader.progress = animator.animatedValue as Float
            rippleShader.noisePhase = phase
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                rippleInProgress = false
                visibility = View.GONE
            }
        })
        animator.start()
        visibility = View.VISIBLE
        rippleInProgress = true
    }

    fun setColor(color: Int) {
        rippleShader.color = color
    }

    override fun onDraw(canvas: Canvas?) {
        // draw over the entire screen
        canvas?.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ripplePaint)
    }
}
