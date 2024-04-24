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

/** Parameter values needed to draw [GlowPieEffect]. */
data class GlowPieEffectConfig(
    /** Center x position of the effect. */
    val centerX: Float,
    /** Center y position of the effect. */
    val centerY: Float,
    /** Width of the rounded box mask. */
    val width: Float,
    /** Height of the rounded box mask. */
    val height: Float,
    /** Corner radius of the rounded box mask. */
    val cornerRadius: Float,
    /**
     * Colors of the effect. The number must match 3, which is defined in [GlowPieShader.NUM_PIE].
     * Each color corresponds to baseColor (bottom), firstLayerColor, and secondLayerColor (top).
     */
    val colors: IntArray
)
