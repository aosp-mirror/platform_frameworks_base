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

import android.graphics.RuntimeShader
import com.android.systemui.surfaceeffects.shaderutil.SdfShaderLibrary
import com.android.systemui.surfaceeffects.shaderutil.ShaderUtilLibrary

/** Circular reveal effect with sparkles. */
class RippleRevealShader : RuntimeShader(SHADER) {
    // language=AGSL
    companion object {
        const val BACKGROUND_UNIFORM = "in_dst"
        private const val MAIN =
            """
            uniform shader ${BACKGROUND_UNIFORM};
            uniform half in_dstAlpha;
            uniform half in_time;
            uniform vec2 in_center;
            uniform half in_innerRadius;
            uniform half in_outerRadius;
            uniform half in_sparkleStrength;
            uniform half in_blur;
            uniform half in_pixelDensity;
            uniform half in_sparkleScale;
            uniform half in_sparkleAlpha;
            layout(color) uniform vec4 in_innerColor;
            layout(color) uniform vec4 in_outerColor;

            vec4 main(vec2 p) {
                half innerMask = soften(sdCircle(p - in_center, in_innerRadius), in_blur);
                half outerMask = soften(sdCircle(p - in_center, in_outerRadius), in_blur);

                // Flip it since we are interested in the circle.
                innerMask = 1.-innerMask;
                outerMask = 1.-outerMask;

                // Color two circles using the mask.
                vec4 inColor = vec4(in_innerColor.rgb, 1.) * in_innerColor.a;
                vec4 outColor = vec4(in_outerColor.rgb, 1.) * in_outerColor.a;
                vec4 blend = mix(inColor, outColor, innerMask);

                vec4 dst = vec4(in_dst.eval(p).rgb, 1.);
                dst *= in_dstAlpha;

                blend *= blend.a;
                // Do normal blend with the background.
                blend = blend + dst * (1. - blend.a);

                half sparkle =
                    sparkles(p - mod(p, in_pixelDensity * in_sparkleScale), in_time);
                // Add sparkles using additive blending.
                blend += sparkle * in_sparkleStrength * in_sparkleAlpha;

                // Mask everything at the end.
                blend *= outerMask;

                return blend;
            }
        """

        private const val SHADER =
            ShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.CIRCLE_SDF +
                MAIN
    }

    fun applyConfig(config: RippleRevealEffectConfig) {
        setCenter(config.centerX, config.centerY)
        setInnerRadius(config.innerRadiusStart)
        setOuterRadius(config.outerRadiusStart)
        setBlurAmount(config.blurAmount)
        setPixelDensity(config.pixelDensity)
        setSparkleScale(config.sparkleScale)
        setSparkleStrength(config.sparkleStrength)
        setInnerColor(config.innerColor)
        setOuterColor(config.outerColor)
    }

    fun setTime(time: Float) {
        setFloatUniform("in_time", time)
    }

    fun setCenter(centerX: Float, centerY: Float) {
        setFloatUniform("in_center", centerX, centerY)
    }

    fun setInnerRadius(radius: Float) {
        setFloatUniform("in_innerRadius", radius)
    }

    fun setOuterRadius(radius: Float) {
        setFloatUniform("in_outerRadius", radius)
    }

    fun setBlurAmount(blurAmount: Float) {
        setFloatUniform("in_blur", blurAmount)
    }

    fun setPixelDensity(density: Float) {
        setFloatUniform("in_pixelDensity", density)
    }

    fun setSparkleScale(scale: Float) {
        setFloatUniform("in_sparkleScale", scale)
    }

    fun setSparkleStrength(strength: Float) {
        setFloatUniform("in_sparkleStrength", strength)
    }

    fun setInnerColor(color: Int) {
        setColorUniform("in_innerColor", color)
    }

    fun setOuterColor(color: Int) {
        setColorUniform("in_outerColor", color)
    }

    /** Sets the background alpha. Range [0,1]. */
    fun setBackgroundAlpha(alpha: Float) {
        setFloatUniform("in_dstAlpha", alpha)
    }

    /** Sets the sparkle alpha. Range [0,1]. */
    fun setSparkleAlpha(alpha: Float) {
        setFloatUniform("in_sparkleAlpha", alpha)
    }
}
