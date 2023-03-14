/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.surfaceeffects.ripple

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils

/** A single ripple animation. */
class RippleAnimation(private val config: RippleAnimationConfig) {
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    val rippleShader: RippleShader = RippleShader(config.rippleShape)
    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    init {
        applyConfigToShader()
    }

    /** Updates the ripple color during the animation. */
    fun updateColor(color: Int) {
        config.apply { config.color = color }
        applyConfigToShader()
    }

    @JvmOverloads
    fun play(onAnimationEnd: Runnable? = null) {
        if (isPlaying()) {
            return // Ignore if ripple effect is already playing
        }

        animator.duration = config.duration
        animator.addUpdateListener { updateListener ->
            val now = updateListener.currentPlayTime
            val progress = updateListener.animatedValue as Float
            rippleShader.progress = progress
            rippleShader.distortionStrength = if (config.shouldDistort) 1 - progress else 0f
            rippleShader.time = now.toFloat()
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    onAnimationEnd?.run()
                }
            }
        )
        animator.start()
    }

    /** Indicates whether the animation is playing. */
    fun isPlaying(): Boolean = animator.isRunning

    private fun applyConfigToShader() {
        rippleShader.setCenter(config.centerX, config.centerY)
        rippleShader.setMaxSize(config.maxWidth, config.maxHeight)
        rippleShader.rippleFill = config.shouldFillRipple
        rippleShader.pixelDensity = config.pixelDensity
        rippleShader.color = ColorUtils.setAlphaComponent(config.color, config.opacity)
        rippleShader.sparkleStrength = config.sparkleStrength
    }
}
