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
package com.android.systemui.surfaceeffects.ripple

import android.graphics.PointF
import android.graphics.RuntimeShader
import android.util.MathUtils
import com.android.systemui.animation.Interpolators
import com.android.systemui.surfaceeffects.shaderutil.SdfShaderLibrary
import com.android.systemui.surfaceeffects.shaderutil.ShaderUtilLibrary

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
class RippleShader(rippleShape: RippleShape = RippleShape.CIRCLE) :
    RuntimeShader(buildShader(rippleShape)) {

    /** Shapes that the [RippleShader] supports. */
    enum class RippleShape {
        CIRCLE,
        ROUNDED_BOX,
        ELLIPSE
    }
    // language=AGSL
    companion object {
        // Default fade in/ out values. The value range is [0,1].
        const val DEFAULT_FADE_IN_START = 0f
        const val DEFAULT_FADE_OUT_END = 1f

        const val DEFAULT_BASE_RING_FADE_IN_END = 0.1f
        const val DEFAULT_BASE_RING_FADE_OUT_START = 0.3f

        const val DEFAULT_SPARKLE_RING_FADE_IN_END = 0.1f
        const val DEFAULT_SPARKLE_RING_FADE_OUT_START = 0.4f

        const val DEFAULT_CENTER_FILL_FADE_IN_END = 0f
        const val DEFAULT_CENTER_FILL_FADE_OUT_START = 0f
        const val DEFAULT_CENTER_FILL_FADE_OUT_END = 0.6f

        private const val SHADER_UNIFORMS =
            """
            uniform vec2 in_center;
            uniform vec2 in_size;
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
            uniform float in_sparkle_strength;
        """

        private const val SHADER_CIRCLE_MAIN =
            """
            vec4 main(vec2 p) {
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

        private const val SHADER_ROUNDED_BOX_MAIN =
            """
            vec4 main(vec2 p) {
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

        private const val SHADER_ELLIPSE_MAIN =
            """
            vec4 main(vec2 p) {
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

        private const val CIRCLE_SHADER =
            SHADER_UNIFORMS +
                ShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.CIRCLE_SDF +
                SHADER_CIRCLE_MAIN
        private const val ROUNDED_BOX_SHADER =
            SHADER_UNIFORMS +
                ShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.ROUNDED_BOX_SDF +
                SHADER_ROUNDED_BOX_MAIN
        private const val ELLIPSE_SHADER =
            SHADER_UNIFORMS +
                ShaderUtilLibrary.SHADER_LIB +
                SdfShaderLibrary.SHADER_SDF_OPERATION_LIB +
                SdfShaderLibrary.ELLIPSE_SDF +
                SHADER_ELLIPSE_MAIN

        private fun buildShader(rippleShape: RippleShape): String =
            when (rippleShape) {
                RippleShape.CIRCLE -> CIRCLE_SHADER
                RippleShape.ROUNDED_BOX -> ROUNDED_BOX_SHADER
                RippleShape.ELLIPSE -> ELLIPSE_SHADER
            }

        private fun subProgress(start: Float, end: Float, progress: Float): Float {
            // Avoid division by 0.
            if (start == end) {
                // If start and end are the same and progress has exceeded the start/ end point,
                // treat it as 1, otherwise 0.
                return if (progress > start) 1f else 0f
            }

            val min = Math.min(start, end)
            val max = Math.max(start, end)
            val sub = Math.min(Math.max(progress, min), max)
            return (sub - start) / (end - start)
        }

        private fun getFade(fadeParams: FadeParams, rawProgress: Float): Float {
            val fadeIn = subProgress(fadeParams.fadeInStart, fadeParams.fadeInEnd, rawProgress)
            val fadeOut =
                1f - subProgress(fadeParams.fadeOutStart, fadeParams.fadeOutEnd, rawProgress)

            return Math.min(fadeIn, fadeOut)
        }
    }

    /** Sets the center position of the ripple. */
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
     * Linear progress of the ripple. Float value between [0, 1].
     *
     * <p>Note that the progress here is expected to be linear without any curve applied.
     */
    var rawProgress: Float = 0.0f
        set(value) {
            field = value
            progress = Interpolators.STANDARD.getInterpolation(value)

            setFloatUniform("in_fadeSparkle", getFade(sparkleRingFadeParams, value))
            setFloatUniform("in_fadeRing", getFade(baseRingFadeParams, value))
            setFloatUniform("in_fadeFill", getFade(centerFillFadeParams, value))
        }

    /** Progress with Standard easing curve applied. */
    private var progress: Float = 0.0f
        set(value) {
            currentWidth = maxSize.x * value
            currentHeight = maxSize.y * value
            setFloatUniform("in_size", currentWidth, currentHeight)

            setFloatUniform("in_thickness", maxSize.y * value * 0.5f)
            // radius should not exceed width and height values.
            setFloatUniform("in_cornerRadius", Math.min(maxSize.x, maxSize.y) * value)

            setFloatUniform("in_blur", MathUtils.lerp(1.25f, 0.5f, value))
        }

    /** Play time since the start of the effect. */
    var time: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_time", value)
        }

    /** A hex value representing the ripple color, in the format of ARGB */
    var color: Int = 0xffffff
        set(value) {
            field = value
            setColorUniform("in_color", value)
        }

    /**
     * Noise sparkle intensity. Expected value between [0, 1]. The sparkle is white, and thus with
     * strength 0 it's transparent, leaving the ripple fully smooth, while with strength 1 it's
     * opaque white and looks the most grainy.
     */
    var sparkleStrength: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_sparkle_strength", value)
        }

    /** Distortion strength of the ripple. Expected value between[0, 1]. */
    var distortionStrength: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_distort_radial", 75 * rawProgress * value)
            setFloatUniform("in_distort_xy", 75 * value)
        }

    /**
     * Pixel density of the screen that the effects are rendered to.
     *
     * <p>This value should come from [resources.displayMetrics.density].
     */
    var pixelDensity: Float = 1.0f
        set(value) {
            field = value
            setFloatUniform("in_pixelDensity", value)
        }

    var currentWidth: Float = 0f
        private set

    var currentHeight: Float = 0f
        private set

    /** Parameters that are used to fade in/ out of the sparkle ring. */
    val sparkleRingFadeParams =
        FadeParams(
            DEFAULT_FADE_IN_START,
            DEFAULT_SPARKLE_RING_FADE_IN_END,
            DEFAULT_SPARKLE_RING_FADE_OUT_START,
            DEFAULT_FADE_OUT_END
        )

    /**
     * Parameters that are used to fade in/ out of the base ring.
     *
     * <p>Note that the shader draws the sparkle ring on top of the base ring.
     */
    val baseRingFadeParams =
        FadeParams(
            DEFAULT_FADE_IN_START,
            DEFAULT_BASE_RING_FADE_IN_END,
            DEFAULT_BASE_RING_FADE_OUT_START,
            DEFAULT_FADE_OUT_END
        )

    /** Parameters that are used to fade in/ out of the center fill. */
    val centerFillFadeParams =
        FadeParams(
            DEFAULT_FADE_IN_START,
            DEFAULT_CENTER_FILL_FADE_IN_END,
            DEFAULT_CENTER_FILL_FADE_OUT_START,
            DEFAULT_CENTER_FILL_FADE_OUT_END
        )

    /**
     * Parameters used for fade in and outs of the ripple.
     *
     * <p>Note that all the fade in/ outs are "linear" progression.
     * ```
     *          (opacity)
     *          1
     *          │
     * maxAlpha ←       ――――――――――――
     *          │      /            \
     *          │     /              \
     * minAlpha ←――――/                \―――― (alpha change)
     *          │
     *          │
     *          0 ―――↑―――↑―――――――――↑―――↑――――1 (progress)
     *               fadeIn        fadeOut
     *               Start & End   Start & End
     * ```
     * <p>If no fade in/ out is needed, set [fadeInStart] and [fadeInEnd] to 0; [fadeOutStart] and
     * [fadeOutEnd] to 1.
     */
    data class FadeParams(
        /**
         * The starting point of the fade out which ends at [fadeInEnd], given that the animation
         * goes from 0 to 1.
         */
        var fadeInStart: Float = DEFAULT_FADE_IN_START,
        /**
         * The endpoint of the fade in when the fade in starts at [fadeInStart], given that the
         * animation goes from 0 to 1.
         */
        var fadeInEnd: Float,
        /**
         * The starting point of the fade out which ends at 1, given that the animation goes from 0
         * to 1.
         */
        var fadeOutStart: Float,

        /** The endpoint of the fade out, given that the animation goes from 0 to 1. */
        var fadeOutEnd: Float = DEFAULT_FADE_OUT_END,
    )
}
