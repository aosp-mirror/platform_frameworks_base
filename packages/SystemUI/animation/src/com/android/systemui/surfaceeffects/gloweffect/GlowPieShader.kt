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

import android.graphics.RuntimeShader
import android.util.Log
import com.android.systemui.surfaceeffects.shaderutil.SdfShaderLibrary
import com.android.systemui.surfaceeffects.shaderutil.ShaderUtilLibrary

/** Draws two glowing pies rotating around the center of a rounded box on a base. */
class GlowPieShader : RuntimeShader(GLOW_PIE_SHADER_COMP) {
    // language=AGSL
    companion object {
        const val BACKGROUND_UNIFORM = "in_dst"
        const val NUM_PIE = 3

        private const val UNIFORMS =
            """
            uniform shader ${BACKGROUND_UNIFORM};
            uniform vec2 in_center;
            uniform vec2 in_size;
            uniform half in_cornerRad;
            uniform float[${NUM_PIE}] in_angles;
            uniform float[${NUM_PIE}] in_alphas;
            uniform float[${NUM_PIE}] in_bottomThresholds;
            uniform float[${NUM_PIE}] in_topThresholds;
            layout(color) uniform vec4 in_colors0;
            layout(color) uniform vec4 in_colors1;
            layout(color) uniform vec4 in_colors2;
        """

        private const val GLOW_PIE_MAIN =
            """
        vec4 main(vec2 p) {
            vec4 pie = vec4(0.);
            vec4 glow = vec4(0.);

            vec2 c = p - in_center;
            half box = sdRoundedBox(c, in_size, in_cornerRad);

            // Base glow (drawn at the bottom)
            pieGlow(
                box,
                c,
                in_angles[0],
                in_colors0.rgb,
                /* pieAlpha= */ 1., // We always show the base color.
                /* glowAlpha= */ in_alphas[0],
                vec2(in_bottomThresholds[0], in_topThresholds[0]),
                pie,
                glow
            );

            // First pie
            pieGlow(
                box,
                c,
                in_angles[1],
                in_colors1.rgb,
                /* pieAlpha= */ in_alphas[1],
                /* glowAlpha= */ in_alphas[1],
                vec2(in_bottomThresholds[1], in_topThresholds[1]),
                pie,
                glow
            );

            // Second pie (drawn on top)
            pieGlow(
                box,
                c,
                in_angles[2],
                in_colors2.rgb,
                /* pieAlpha= */ in_alphas[2],
                /* glowAlpha= */ in_alphas[2],
                vec2(in_bottomThresholds[2], in_topThresholds[2]),
                pie,
                glow
            );

            return vec4(pie.rgb + glow.rgb * 0.3, pie.a);
        }
        """

        private const val REMAP =
            """
            float remap(float in_start, float in_end, float out_start, float out_end, float x) {
                x = (x - in_start) / (in_end - in_start);
                x = clamp(x, 0., 1.);
                return x * (out_end - out_start) + out_start;
            }
        """

        /**
         * This function draws a pie slice, an a glow on top. The glow also has the same pie shape
         * but with more blur and additive blending.
         */
        private const val GLOW_PIE =
            """
            void pieGlow(
                half box,
                vec2 c,
                half angle,
                vec3 color,
                half pieAlpha,
                half glowAlpha,
                vec2 angleThresholds,
                inout vec4 inout_pie,
                inout vec4 inout_glow) {

                // Apply angular rotation.
                half co = cos(angle), si = sin(angle);
                mat2 rotM = mat2(co, -si, si, co); // 2D rotation matrix
                c *= rotM;

                // We rotate based on the cosine value, since we want to avoid using inverse
                // trig function, which in this case is atan.

                // Dot product with vec2(1., 0.) and bring the range to [0,1].
                // Same as dot(normalize(c), vec2(1.,0) * 0.5 + 0.5
                half d = normalize(c).x * 0.5 + 0.5;

                // Those thresholds represents each end of the pie.
                float bottomThreshold = angleThresholds[0];
                float topThreshold = angleThresholds[1];
                float angleMask = remap(bottomThreshold, topThreshold, 0., 1., d);

                half boxMask = 1. - smoothstep(-0.02, 0.02, box);
                vec4 pie = vec4(color, 1.0) * angleMask * boxMask * pieAlpha;

                // We are drawing the same pie but with more blur.
                half glowMask = 1. - smoothstep(0., 0.6, box);
                // Glow outside only.
                glowMask = min(glowMask, smoothstep(-0.02, 0.02, box));
                // Apply some curve for the glow. (Can take out)
                glowMask *= glowMask * glowMask;
                // Glow mask should also be sliced with the angle mask.
                glowMask *= angleMask;
                vec4 glow = vec4(color, 1.0) * glowMask * glowAlpha;

                inout_pie = pie + inout_pie * (1. - pie.a);
                // Additive blending.
                inout_glow += glow;
            }
            """

        private const val GLOW_PIE_SHADER_COMP =
            ShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.ROUNDED_BOX_SDF +
                UNIFORMS +
                REMAP +
                GLOW_PIE +
                GLOW_PIE_MAIN

        private val TAG = GlowPieShader::class.java.simpleName
    }

    fun applyConfig(config: GlowPieEffectConfig) {
        setCenter(config.centerX, config.centerY)
        setSize(config.width, config.height)
        setCornerRadius(config.cornerRadius)
        setColor(config.colors)
    }

    fun setCenter(centerX: Float, centerY: Float) {
        setFloatUniform("in_center", centerX, centerY)
    }

    fun setSize(width: Float, height: Float) {
        setFloatUniform("in_size", width, height)
    }

    fun setCornerRadius(cornerRadius: Float) {
        setFloatUniform("in_cornerRad", cornerRadius)
    }

    /** Ignores alpha value, as fade in/out is handled within shader. */
    fun setColor(colors: IntArray) {
        if (colors.size != NUM_PIE) {
            Log.wtf(TAG, "The number of colors must be $NUM_PIE")
            return
        }
        setColorUniform("in_colors0", colors[0])
        setColorUniform("in_colors1", colors[1])
        setColorUniform("in_colors2", colors[2])
    }

    fun setAngles(vararg angles: Float) {
        if (angles.size != NUM_PIE) {
            Log.wtf(TAG, "The number of angles must be $NUM_PIE")
            return
        }
        setFloatUniform("in_angles", angles)
    }

    fun setAlphas(vararg alphas: Float) {
        if (alphas.size != NUM_PIE) {
            Log.wtf(TAG, "The number of angles must be $NUM_PIE")
            return
        }
        setFloatUniform("in_alphas", alphas)
    }

    fun setBottomAngleThresholds(vararg bottomThresholds: Float) {
        if (bottomThresholds.size != NUM_PIE) {
            Log.wtf(TAG, "The number of bottomThresholds must be $NUM_PIE")
            return
        }
        setFloatUniform("in_bottomThresholds", bottomThresholds)
    }

    fun setTopAngleThresholds(vararg topThresholds: Float) {
        if (topThresholds.size != NUM_PIE) {
            Log.wtf(TAG, "The number of topThresholds must be $NUM_PIE")
            return
        }
        setFloatUniform("in_topThresholds", topThresholds)
    }
}
