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

package com.android.systemui.statusbar.charging

import android.graphics.PointF
import android.graphics.RuntimeShader
import android.util.MathUtils

/**
 * Shader class that renders a distorted ripple for the UDFPS dwell effect.
 * Adjustable shader parameters:
 *   - progress
 *   - origin
 *   - color
 *   - time
 *   - maxRadius
 *   - distortionStrength.
 * See per field documentation for more details.
 *
 * Modeled after frameworks/base/graphics/java/android/graphics/drawable/RippleShader.java.
 */
class DwellRippleShader internal constructor() : RuntimeShader(SHADER) {
    companion object {
        private const val SHADER_UNIFORMS = """uniform vec2 in_origin;
                uniform float in_time;
                uniform float in_radius;
                uniform float in_blur;
                layout(color) uniform vec4 in_color;
                uniform float in_phase1;
                uniform float in_phase2;
                uniform float in_distortion_strength;"""
        private const val SHADER_LIB = """
                float softCircle(vec2 uv, vec2 xy, float radius, float blur) {
                  float blurHalf = blur * 0.5;
                  float d = distance(uv, xy);
                  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);
                }

                float softRing(vec2 uv, vec2 xy, float radius, float blur) {
                  float thickness_half = radius * 0.25;
                  float circle_outer = softCircle(uv, xy, radius + thickness_half, blur);
                  float circle_inner = softCircle(uv, xy, radius - thickness_half, blur);
                  return circle_outer - circle_inner;
                }

                vec2 distort(vec2 p, float time, float distort_amount_xy, float frequency) {
                    return p + vec2(sin(p.y * frequency + in_phase1),
                                    cos(p.x * frequency * -1.23 + in_phase2)) * distort_amount_xy;
                }

                vec4 ripple(vec2 p, float distort_xy, float frequency) {
                    vec2 p_distorted = distort(p, in_time, distort_xy, frequency);
                    float circle = softCircle(p_distorted, in_origin, in_radius * 1.2, in_blur);
                    float rippleAlpha = max(circle,
                        softRing(p_distorted, in_origin, in_radius, in_blur)) * 0.25;
                    return in_color * rippleAlpha;
                }
                """
        private const val SHADER_MAIN = """vec4 main(vec2 p) {
                    vec4 color1 = ripple(p,
                        34 * in_distortion_strength, // distort_xy
                        0.012 // frequency
                    );
                    vec4 color2 = ripple(p,
                        49 * in_distortion_strength, // distort_xy
                        0.018 // frequency
                    );
                    // Alpha blend between two layers.
                    return vec4(color1.xyz + color2.xyz
                        * (1 - color1.w), color1.w + color2.w * (1-color1.w));
                }"""
        private const val SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN
    }

    /**
     * Maximum radius of the ripple.
     */
    var maxRadius: Float = 0.0f

    /**
     * Origin coordinate of the ripple.
     */
    var origin: PointF = PointF()
        set(value) {
            field = value
            setFloatUniform("in_origin", value.x, value.y)
        }

    /**
     * Progress of the ripple. Float value between [0, 1].
     */
    var progress: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_radius",
                    (1 - (1 - value) * (1 - value) * (1 - value))* maxRadius)
            setFloatUniform("in_blur", MathUtils.lerp(1f, 0.7f, value))
        }

    /**
     * Distortion strength between [0, 1], with 0 being no distortion and 1 being full distortion.
     */
    var distortionStrength: Float = 0.0f
        set(value) {
            field = value
            setFloatUniform("in_distortion_strength", value)
        }

    /**
     * Play time since the start of the effect in seconds.
     */
    var time: Float = 0.0f
        set(value) {
            field = value * 0.001f
            setFloatUniform("in_time", field)
            setFloatUniform("in_phase1", field * 3f + 0.367f)
            setFloatUniform("in_phase2", field * 7.2f * 1.531f)
        }

    /**
     * A hex value representing the ripple color, in the format of ARGB
     */
    var color: Int = 0xffffff.toInt()
        set(value) {
            field = value
            setColorUniform("in_color", value)
        }
}
