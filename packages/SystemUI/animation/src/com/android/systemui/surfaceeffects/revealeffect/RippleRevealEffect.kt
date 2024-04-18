/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.surfaceeffects.revealeffect

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import androidx.core.graphics.ColorUtils
import com.android.systemui.surfaceeffects.RenderEffectDrawCallback
import com.android.systemui.surfaceeffects.utils.MathUtils
import kotlin.math.max
import kotlin.math.min

/** Creates a reveal effect with a circular ripple sparkles on top. */
class RippleRevealEffect(
    private val config: RippleRevealEffectConfig,
    private val renderEffectCallback: RenderEffectDrawCallback,
    private val stateChangedCallback: AnimationStateChangedCallback? = null
) {
    private val rippleRevealShader = RippleRevealShader().apply { applyConfig(config) }
    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    fun play() {
        if (animator.isRunning) {
            return
        }

        animator.duration = config.duration.toLong()
        animator.addUpdateListener { updateListener ->
            val playTime = updateListener.currentPlayTime.toFloat()
            rippleRevealShader.setTime(playTime * TIME_SCALE_FACTOR)

            // Compute radius.
            val progress = updateListener.animatedValue as Float
            val innerRad = MathUtils.lerp(config.innerRadiusStart, config.innerRadiusEnd, progress)
            val outerRad = MathUtils.lerp(config.outerRadiusStart, config.outerRadiusEnd, progress)
            rippleRevealShader.setInnerRadius(innerRad)
            rippleRevealShader.setOuterRadius(outerRad)

            // Compute alphas.
            val innerAlphaProgress =
                MathUtils.constrainedMap(
                    1f,
                    0f,
                    config.innerFadeOutStart,
                    config.duration,
                    playTime
                )
            val outerAlphaProgress =
                MathUtils.constrainedMap(
                    1f,
                    0f,
                    config.outerFadeOutStart,
                    config.duration,
                    playTime
                )
            val innerAlpha = MathUtils.lerp(0f, 255f, innerAlphaProgress)
            val outerAlpha = MathUtils.lerp(0f, 255f, outerAlphaProgress)

            val innerColor = ColorUtils.setAlphaComponent(config.innerColor, innerAlpha.toInt())
            val outerColor = ColorUtils.setAlphaComponent(config.outerColor, outerAlpha.toInt())
            rippleRevealShader.setInnerColor(innerColor)
            rippleRevealShader.setOuterColor(outerColor)

            // Pass in progresses since those functions take in normalized alpha values.
            rippleRevealShader.setBackgroundAlpha(max(innerAlphaProgress, outerAlphaProgress))
            rippleRevealShader.setSparkleAlpha(min(innerAlphaProgress, outerAlphaProgress))

            // Trigger draw callback.
            renderEffectCallback.onDraw(
                RenderEffect.createRuntimeShaderEffect(
                    rippleRevealShader,
                    RippleRevealShader.BACKGROUND_UNIFORM
                )
            )
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    stateChangedCallback?.onAnimationEnd()
                }
            }
        )
        animator.start()
        stateChangedCallback?.onAnimationStart()
    }

    interface AnimationStateChangedCallback {
        fun onAnimationStart()
        fun onAnimationEnd()
    }

    private companion object {
        private const val TIME_SCALE_FACTOR = 0.00175f
    }
}
