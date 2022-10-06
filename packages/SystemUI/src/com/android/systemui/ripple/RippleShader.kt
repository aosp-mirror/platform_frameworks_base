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
package com.android.systemui.ripple

import android.graphics.PointF
import android.graphics.RuntimeShader
import android.util.MathUtils

/**
 * Shader class that renders an expanding ripple effect. The ripple contains three elements:
 *
 * 1. an expanding filled [RippleShape] that appears in the beginning and quickly fades away
 * 2. an expanding ring that appears throughout the effect
 * 3. an expanding ring-shaped area that reveals noise over #2.
 *
 * The ripple shader will be default to the circle shape if not specified.
 *
 * Modeled after frameworks/base/graphics/java/android/graphics/drawable/RippleShader.java.
 */
class RippleShader internal constructor(rippleShape: RippleShape = RippleShape.CIRCLE) :
        RuntimeShader(buildShader(rippleShape)) {

    /** Shapes that the [RippleShader] supports. */
    enum class RippleShape {
        CIRCLE,
        ROUNDED_BOX,
        ELLIPSE
    }
    //language=AGSL
    companion object {
        private const val SHADER_UNIFORMS = """uniform vec2 in_center;
                uniform vec2 in_size;
                uniform float in_progress;
                uniform float in_cornerRadius;
                uniform float in_thickness;
                uniform float in_time;
                uniform float in_distort_radial;
                uniform float in_distort_xy;
                uniform float in_fadeSparkle;
                uniform float in_fadeFill;
                uniform float in_fadeRing;
                uniform float in_blur;
                uniform float in_pixelDensity;
                layout(color) uniform vec4 in_color;
                uniform float in_sparkle_strength;"""

        private const val SHADER_CIRCLE_MAIN = """vec4 main(vec2 p) {
                vec2 p_distorted = distort(p, in_time, in_distort_radial, in_distort_xy);
                float radius = in_size.x * 0.5;
                float sparkleRing = soften(circleRing(p_distorted-in_center, radius), in_blur);
                float inside = soften(sdCircle(p_distorted-in_center, radius * 1.2), in_blur);
                float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time * 0.00175)
                    * (1.-sparkleRing) * in_fadeSparkle;

                float rippleInsideAlpha = (1.-inside) * in_fadeFill;
                float rippleRingAlpha = (1.-sparkleRing) * in_fadeRing;
                float rippleAlpha = max(rippleInsideAlpha, rippleRingAlpha) * in_color.a;
                vec4 ripple = vec4(in_color.rgb, 1.0) * rippleAlpha;
                return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);
            }
        """

        private const val SHADER_ROUNDED_BOX_MAIN = """vec4 main(vec2 p) {
                float sparkleRing = soften(roundedBoxRing(p-in_center, in_size, in_cornerRadius,
                    in_thickness), in_blur);
                float inside = soften(sdRoundedBox(p-in_center, in_size * 1.2, in_cornerRadius),
                    in_blur);
                float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time * 0.00175)
                    * (1.-sparkleRing) * in_fadeSparkle;

                float rippleInsideAlpha = (1.-inside) * in_fadeFill;
                float rippleRingAlpha = (1.-sparkleRing) * in_fadeRing;
                float rippleAlpha = max(rippleInsideAlpha, rippleRingAlpha) * in_color.a;
                vec4 ripple = vec4(in_color.rgb, 1.0) * rippleAlpha;
                return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);
            }
        """

        private const val SHADER_ELLIPSE_MAIN = """vec4 main(vec2 p) {
                vec2 p_distorted = distort(p, in_time, in_distort_radial, in_distort_xy);

                float sparkleRing = soften(ellipseRing(p_distorted-in_center, in_size), in_blur);
                float inside = soften(sdEllipse(p_distorted-in_center, in_size * 1.2), in_blur);
                float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time * 0.00175)
                    * (1.-sparkleRing) * in_fadeSparkle;

                float rippleInsideAlpha = (1.-inside) * in_fadeFill;
                float rippleRingAlpha = (1.-sparkleRing) * in_fadeRing;
                float rippleAlpha = max(rippleInsideAlpha, rippleRingAlpha) * in_color.a;
                vec4 ripple = vec4(in_color.rgb, 1.0) * rippleAlpha;
                return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);
            }
        """

        private const val CIRCLE_SHADER = SHADER_UNIFORMS + RippleShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB + SdfShaderLibrary.CIRCLE_SDF +
                SHADER_CIRCLE_MAIN
        private const val ROUNDED_BOX_SHADER = SHADER_UNIFORMS +
                RippleShaderUtilLibrary.SHADER_LIB + SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.ROUNDED_BOX_SDF + SHADER_ROUNDED_BOX_MAIN
        private const val ELLIPSE_SHADER = SHADER_UNIFORMS + RippleShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB + SdfShaderLibrary.ELLIPSE_SDF +
                SHADER_ELLIPSE_MAIN

        private fun buildShader(rippleShape: RippleShape): String =
                when (rippleShape) {
                    RippleShape.CIRCLE -> CIRCLE_SHADER
                    RippleShape.ROUNDED_BOX -> ROUNDED_BOX_SHADER
                    RippleShape.ELLIPSE -> ELLIPSE_SHADER
                }

        private fun subProgress(start: Float, end: Float, progress: Float): Float {
            val min = Math.min(start, end)
            val max = Math.max(start, end)
            val sub = Math.min(Math.max(progress, min), max)
            return (sub - start) / (end - start)
        }
    }

    /**
     * Sets the center position of the ripple.
     */
    fun setCenter(x: Float, y: Float) {
        setFloatUniform("in_center", x, y)
    }

    /** Max width of the ripple. */
    private var maxSize: PointF = PointF()
    fun setMaxSize(width: Float, height: Float) {
        maxSize.x = width
        maxSize.y = height
    }

    /**
     * Progress of the ripple. Float value between [0, 1].
     */
    var progress: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_progress", value)
            val curvedProg = 1 - (1 - value) * (1 - value) * (1 - value)

            setFloatUniform("in_size", /* width= */ maxSize.x * curvedProg,
                    /* height= */ maxSize.y * curvedProg)
            setFloatUniform("in_thickness", maxSize.y * curvedProg * 0.5f)
            // radius should not exceed width and height values.
            setFloatUniform("in_cornerRadius",
                    Math.min(maxSize.x, maxSize.y) * curvedProg)

            setFloatUniform("in_blur", MathUtils.lerp(1.25f, 0.5f, value))

            val fadeIn = subProgress(0f, 0.1f, value)
            val fadeOutNoise = subProgress(0.4f, 1f, value)
            var fadeOutRipple = 0f
            var fadeFill = 0f
            if (!rippleFill) {
                fadeFill = subProgress(0f, 0.6f, value)
                fadeOutRipple = subProgress(0.3f, 1f, value)
            }
            setFloatUniform("in_fadeSparkle", Math.min(fadeIn, 1 - fadeOutNoise))
            setFloatUniform("in_fadeFill", 1 - fadeFill)
            setFloatUniform("in_fadeRing", Math.min(fadeIn, 1 - fadeOutRipple))
        }

    /**
     * Play time since the start of the effect.
     */
    var time: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_time", value)
        }

    /**
     * A hex value representing the ripple color, in the format of ARGB
     */
    var color: Int = 0xffffff
        set(value) {
            field = value
            setColorUniform("in_color", value)
        }

    /**
     * Noise sparkle intensity. Expected value between [0, 1]. The sparkle is white, and thus
     * with strength 0 it's transparent, leaving the ripple fully smooth, while with strength 1
     * it's opaque white and looks the most grainy.
     */
    var sparkleStrength: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_sparkle_strength", value)
        }

    /**
     * Distortion strength of the ripple. Expected value between[0, 1].
     */
    var distortionStrength: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_distort_radial", 75 * progress * value)
            setFloatUniform("in_distort_xy", 75 * value)
        }

    var pixelDensity: Float = 1.0f
        set(value) {
            field = value
            setFloatUniform("in_pixelDensity", value)
        }

    /**
     * True if the ripple should stayed filled in as it expands to give a filled-in circle effect.
     * False for a ring effect.
     */
    var rippleFill: Boolean = false
}
