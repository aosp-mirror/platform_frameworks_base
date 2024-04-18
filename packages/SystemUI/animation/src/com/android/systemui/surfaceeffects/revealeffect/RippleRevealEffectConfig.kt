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

import android.graphics.Color

/** Defines parameters needed for [RippleRevealEffect]. */
data class RippleRevealEffectConfig(
    /** Total duration of the animation. */
    val duration: Float = 0f,
    /** Timestamp of when the inner mask starts fade out. (Linear fadeout) */
    val innerFadeOutStart: Float = 0f,
    /** Timestamp of when the outer mask starts fade out. (Linear fadeout) */
    val outerFadeOutStart: Float = 0f,
    /** Center x position of the effect. */
    val centerX: Float = 0f,
    /** Center y position of the effect. */
    val centerY: Float = 0f,
    /** Start radius of the inner circle. */
    val innerRadiusStart: Float = 0f,
    /** End radius of the inner circle. */
    val innerRadiusEnd: Float = 0f,
    /** Start radius of the outer circle. */
    val outerRadiusStart: Float = 0f,
    /** End radius of the outer circle. */
    val outerRadiusEnd: Float = 0f,
    /**
     * Pixel density of the display. Do not pass a random value. The value must come from
     * [context.resources.displayMetrics.density].
     */
    val pixelDensity: Float = 1f,
    /**
     * The amount the circle masks should be softened. Higher value will make the edge of the circle
     * mask soft.
     */
    val blurAmount: Float = 0f,
    /** Color of the inner circle mask. */
    val innerColor: Int = Color.WHITE,
    /** Color of the outer circle mask. */
    val outerColor: Int = Color.WHITE,
    /** Multiplier to make the sparkles visible. */
    val sparkleStrength: Float = SPARKLE_STRENGTH,
    /** Size of the sparkle. Expected range [0, 1]. */
    val sparkleScale: Float = SPARKLE_SCALE
) {
    /** Default parameters. */
    companion object {
        const val SPARKLE_STRENGTH: Float = 0.3f
        const val SPARKLE_SCALE: Float = 0.8f
    }
}
