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

package com.android.systemui.surfaceeffects.gloweffect

import android.animation.ValueAnimator
import android.animation.ValueAnimator.INFINITE
import android.graphics.RenderEffect
import androidx.annotation.VisibleForTesting
import com.android.systemui.surfaceeffects.RenderEffectDrawCallback
import com.android.systemui.surfaceeffects.utils.MathUtils

/** Renders rotating pie with glow on top, masked with a rounded box. */
class GlowPieEffect(
    config: GlowPieEffectConfig,
    private val renderEffectDrawCallback: RenderEffectDrawCallback
) {

    private val glowPieShader = GlowPieShader().apply { applyConfig(config) }

    @VisibleForTesting
    val mainAnimator: ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            // We want to loop the full cycle.
            duration = DURATION_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = INFINITE
        }

    /** Plays glow pie until [finish] is called. */
    fun play() {
        if (mainAnimator.isRunning) return

        baseGlow.resetProgress()
        firstGlowPie.resetProgress()
        secondGlowPie.resetProgress()

        mainAnimator.addUpdateListener { updateListener ->
            val time = updateListener.currentPlayTime.toFloat() % mainAnimator.duration

            // Remap each glow pie progress.
            baseGlow.updateProgress(time)
            firstGlowPie.updateProgress(time)
            secondGlowPie.updateProgress(time)

            // TODO(b/335315940): Consider passing in 2D Matrix.
            glowPieShader.setAngles(baseGlow.angle(), firstGlowPie.angle(), secondGlowPie.angle())
            glowPieShader.setBottomAngleThresholds(
                baseGlow.bottomThreshold(),
                firstGlowPie.bottomThreshold(),
                secondGlowPie.bottomThreshold()
            )
            glowPieShader.setTopAngleThresholds(
                baseGlow.topThreshold(),
                firstGlowPie.topThreshold(),
                secondGlowPie.topThreshold()
            )
            glowPieShader.setAlphas(baseGlow.alpha(), firstGlowPie.alpha(), secondGlowPie.alpha())

            // Finally trigger the draw callback.
            renderEffectDrawCallback.onDraw(
                RenderEffect.createRuntimeShaderEffect(
                    glowPieShader,
                    GlowPieShader.BACKGROUND_UNIFORM
                )
            )
        }

        mainAnimator.start()
    }

    fun finish() {
        // TODO(b/335315940) Add alpha fade.
        mainAnimator.cancel()
    }

    companion object {
        @VisibleForTesting const val PI = Math.PI.toFloat()
        @VisibleForTesting const val FEATHER = 0.3f
        @VisibleForTesting const val DURATION_MS = 3000L

        private val baseGlow = BaseGlow()
        private val firstGlowPie = FirstGlowPie()
        private val secondGlowPie = SecondGlowPie()
    }

    /** Contains animation parameters for each layer of glow pie. */
    interface GlowPie {
        /**
         * The start & end timestamps of the animation. Must be smaller than or equal to the full
         * [DURATION_MS].
         */
        val startMs: Float
        val endMs: Float
        /**
         * Start & end angles in radian. This determines how many cycles you want to rotate. e.g.
         * startAngle = 0f endAngle = 4f * PI, will give you the 2 cycles.
         */
        val startAngle: Float
        val endAngle: Float
        /**
         * Start & end timestamps of the fade out duration. You may want to override [alpha] if you
         * want to make it fade in. See [BaseGlow].
         */
        val alphaFadeStartMs: Float
        val alphaFadeEndMs: Float

        /** Below two values are expected to be updated through [updateProgress]. */
        /** Normalized progress. */
        var progress: Float
        /** current time of the animation in ms. */
        var time: Float

        // Must be called before retrieving angle, bottom & top thresholds, and alpha.
        // Otherwise the values would be stale.
        fun updateProgress(time: Float) {
            progress = MathUtils.constrainedMap(0f, 1f, startMs, endMs, time)
            this.time = time
        }

        fun resetProgress() {
            progress = 0f
            time = 0f
        }

        fun angle(): Float {
            // Negate the angle since we want clock-wise rotation.
            val angle =
                MathUtils.constrainedMap(startAngle, endAngle, 0f, 1f, progress) + progress * PI
            return -angle
        }

        fun bottomThreshold(): Float {
            return MathUtils.lerp(1f, -FEATHER, progress)
        }

        fun topThreshold(): Float {
            return MathUtils.lerp(1f + FEATHER, 0f, progress)
        }

        // By default, it fades "out".
        fun alpha(): Float {
            // Remap timestamps (in MS) to alpha [0, 1].
            return MathUtils.constrainedMap(1f, 0f, alphaFadeStartMs, alphaFadeEndMs, time)
        }
    }

    data class BaseGlow(
        override val startMs: Float = 0f,
        override val endMs: Float = 0f,
        override val startAngle: Float = 0f,
        override val endAngle: Float = 0f,
        override val alphaFadeStartMs: Float = 2250f,
        override val alphaFadeEndMs: Float = 2950f,
    ) : GlowPie {

        override var progress: Float = 1f
        override var time: Float = 0f
        override fun updateProgress(time: Float) {}

        override fun resetProgress() {}

        override fun angle(): Float = 0f

        override fun bottomThreshold(): Float = 0f

        override fun topThreshold(): Float = 0f

        // Base glow fade "in" (i.e. reveals).
        override fun alpha(): Float {
            return MathUtils.constrainedMap(0f, 1f, alphaFadeStartMs, alphaFadeEndMs, time)
        }
    }

    data class FirstGlowPie(
        override val startMs: Float = 250f,
        override val endMs: Float = 2500f,
        override val startAngle: Float = -PI / 2f,
        override val endAngle: Float = 4f * PI,
        override val alphaFadeStartMs: Float = 2500f,
        override val alphaFadeEndMs: Float = 2750f,
        override var progress: Float = 0f,
        override var time: Float = 0f
    ) : GlowPie

    data class SecondGlowPie(
        override val startMs: Float = 350f,
        override val endMs: Float = 2600f,
        override val startAngle: Float = -PI / 2f,
        override val endAngle: Float = 3f * PI,
        override val alphaFadeStartMs: Float = 2600f,
        override val alphaFadeEndMs: Float = 2850f,
        override var progress: Float = 0f,
        override var time: Float = 0f
    ) : GlowPie
}
