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

        mainAnimator.addUpdateListener { updateListener ->
            val time = updateListener.currentPlayTime.toFloat() % mainAnimator.duration

            // TODO(b/335315940): Extract the timestamps to config.
            val progress1 = MathUtils.constrainedMap(0f, 1f, 250f, 2500f, time)
            val progress2 = MathUtils.constrainedMap(0f, 1f, 350f, 2600f, time)

            // TODO(b/335315940): Consider passing in 2D Matrix.
            val angle0 = 0f // No rotation for the base.
            // Negate the angle since we want clock-wise rotation.
            val angle1 =
                -(MathUtils.constrainedMap(-PI / 2f, 4f * PI, 0f, 1f, progress1) + progress1 * PI)
            val angle2 =
                -(MathUtils.constrainedMap(-PI / 2f, 3f * PI, 0f, 1f, progress2) + progress2 * PI)
            glowPieShader.setAngle(angle0, angle1, angle2)
            val bottomThreshold0 = 0f
            val topThreshold0 = 0f

            val bottomThreshold1 = MathUtils.lerp(1f, -FEATHER, progress1)
            val topThreshold1 = MathUtils.lerp(1f + FEATHER, 0f, progress1)

            val bottomThreshold2 = MathUtils.lerp(1f, -FEATHER, progress2)
            val topThreshold2 = MathUtils.lerp(1f + FEATHER, 0f, progress2)

            glowPieShader.setBottomAngleThresholds(
                bottomThreshold0,
                bottomThreshold1,
                bottomThreshold2
            )
            glowPieShader.setTopAngleThresholds(topThreshold0, topThreshold1, topThreshold2)

            // Remap timestamps (in MS) to alpha [0, 1].
            val alpha0 = MathUtils.constrainedMap(0f, 1f, 2250f, 2950f, time)
            val alpha1 = MathUtils.constrainedMap(1f, 0f, 2500f, 2750f, time)
            val alpha2 = MathUtils.constrainedMap(1f, 0f, 2600f, 2850f, time)
            glowPieShader.setAlphas(alpha0, alpha1, alpha2)

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

    private companion object {
        private const val PI = Math.PI.toFloat()
        private const val FEATHER = 0.3f
        // This indicates a single loop of the animation.
        private const val DURATION_MS = 3000L
    }
}
