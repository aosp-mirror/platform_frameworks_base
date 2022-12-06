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
package com.android.systemui.surfaceeffects.turbulencenoise

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import java.util.Random
import kotlin.math.sin

/** View that renders turbulence noise effect. */
class TurbulenceNoiseView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        private const val MS_TO_SEC = 0.001f
        private const val TWO_PI = Math.PI.toFloat() * 2f
    }

    @VisibleForTesting val turbulenceNoiseShader = TurbulenceNoiseShader()
    private val paint = Paint().apply { this.shader = turbulenceNoiseShader }
    private val random = Random()
    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)
    private var config: TurbulenceNoiseAnimationConfig? = null

    val isPlaying: Boolean
        get() = animator.isRunning

    init {
        // Only visible during the animation.
        visibility = INVISIBLE
    }

    /** Updates the color during the animation. No-op if there's no animation playing. */
    fun updateColor(color: Int) {
        config?.let {
            it.color = color
            applyConfig(it)
        }
    }

    override fun onDraw(canvas: Canvas?) {
        if (canvas == null || !canvas.isHardwareAccelerated) {
            // Drawing with the turbulence noise shader requires hardware acceleration, so skip
            // if it's unsupported.
            return
        }

        canvas.drawPaint(paint)
    }

    fun play(config: TurbulenceNoiseAnimationConfig) {
        if (isPlaying) {
            return // Ignore if the animation is playing.
        }
        visibility = VISIBLE
        applyConfig(config)

        // Add random offset to avoid same patterned noise.
        val offsetX = random.nextFloat()
        val offsetY = random.nextFloat()

        animator.duration = config.duration.toLong()
        animator.addUpdateListener { updateListener ->
            val timeInSec = updateListener.currentPlayTime * MS_TO_SEC
            // Remap [0,1] to [0, 2*PI]
            val progress = TWO_PI * updateListener.animatedValue as Float

            turbulenceNoiseShader.setNoiseMove(
                offsetX + timeInSec * config.noiseMoveSpeedX,
                offsetY + timeInSec * config.noiseMoveSpeedY,
                timeInSec * config.noiseMoveSpeedZ
            )

            // Fade in and out the noise as the animation progress.
            // TODO: replace it with a better curve
            turbulenceNoiseShader.setOpacity(sin(TWO_PI - progress) * config.luminosityMultiplier)

            invalidate()
        }

        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = INVISIBLE
                    config.onAnimationEnd?.run()
                }
            }
        )
        animator.start()
    }

    private fun applyConfig(config: TurbulenceNoiseAnimationConfig) {
        this.config = config
        with(turbulenceNoiseShader) {
            setGridCount(config.gridCount)
            setColor(ColorUtils.setAlphaComponent(config.color, config.opacity))
            setBackgroundColor(config.backgroundColor)
            setSize(config.width, config.height)
            setPixelDensity(config.pixelDensity)
        }
        paint.blendMode = config.blendMode
    }
}
