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

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RuntimeShader
import android.util.MathUtils

/**
 * Shader class that renders an expanding charging ripple effect. A charging ripple contains
 * three elements:
 * 1. an expanding filled circle that appears in the beginning and quickly fades away
 * 2. an expanding ring that appears throughout the effect
 * 3. an expanding ring-shaped area that reveals noise over #2.
 *
 * Modeled after frameworks/base/graphics/java/android/graphics/drawable/RippleShader.java.
 */
class RippleShader internal constructor() : RuntimeShader(SHADER, false) {
    companion object {
        private const val SHADER_UNIFORMS = """uniform vec2 in_origin;
                uniform float in_progress;
                uniform float in_maxRadius;
                uniform float in_time;
                uniform float in_distort_radial;
                uniform float in_distort_xy;
                uniform float in_radius;
                uniform float in_fadeSparkle;
                uniform float in_fadeCircle;
                uniform float in_fadeRing;
                uniform float in_blur;
                uniform float in_pixelDensity;
                uniform vec4 in_color;
                uniform float in_sparkle_strength;"""
        private const val SHADER_LIB = """float triangleNoise(vec2 n) {
                    n  = fract(n * vec2(5.3987, 5.4421));
                    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));
                    float xy = n.x * n.y;
                    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
                }
                const float PI = 3.1415926535897932384626;

                float threshold(float v, float l, float h) {
                  return step(l, v) * (1.0 - step(h, v));
                }

                float sparkles(vec2 uv, float t) {
                  float n = triangleNoise(uv);
                  float s = 0.0;
                  for (float i = 0; i < 4; i += 1) {
                    float l = i * 0.01;
                    float h = l + 0.1;
                    float o = smoothstep(n - l, h, n);
                    o *= abs(sin(PI * o * (t + 0.55 * i)));
                    s += o;
                  }
                  return s;
                }

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

                vec2 distort(vec2 p, vec2 origin, float time,
                    float distort_amount_radial, float distort_amount_xy) {
                    float2 distance = origin - p;
                    float angle = atan(distance.y, distance.x);
                    return p + vec2(sin(angle * 8 + time * 0.003 + 1.641),
                                    cos(angle * 5 + 2.14 + time * 0.00412)) * distort_amount_radial
                             + vec2(sin(p.x * 0.01 + time * 0.00215 + 0.8123),
                                    cos(p.y * 0.01 + time * 0.005931)) * distort_amount_xy;
                }"""
        private const val SHADER_MAIN = """vec4 main(vec2 p) {
                    vec2 p_distorted = distort(p, in_origin, in_time, in_distort_radial,
                        in_distort_xy);

                    // Draw shapes
                    float sparkleRing = softRing(p_distorted, in_origin, in_radius, in_blur);
                    float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time * 0.00175)
                        * sparkleRing * in_fadeSparkle;
                    float circle = softCircle(p_distorted, in_origin, in_radius * 1.2, in_blur);
                    float rippleAlpha = max(circle * in_fadeCircle,
                        softRing(p_distorted, in_origin, in_radius, in_blur) * in_fadeRing) * 0.45;
                    vec4 ripple = in_color * rippleAlpha;
                    return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);
                }"""
        private const val SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN

        private fun subProgress(start: Float, end: Float, progress: Float): Float {
            val min = Math.min(start, end)
            val max = Math.max(start, end)
            val sub = Math.min(Math.max(progress, min), max)
            return (sub - start) / (end - start)
        }
    }

    /**
     * Maximum radius of the ripple.
     */
    var radius: Float = 0.0f
        set(value) {
            field = value
            setUniform("in_maxRadius", value)
        }

    /**
     * Origin coordinate of the ripple.
     */
    var origin: PointF = PointF()
        set(value) {
            field = value
            setUniform("in_origin", floatArrayOf(value.x, value.y))
        }

    /**
     * Progress of the ripple. Float value between [0, 1].
     */
    var progress: Float = 0.0f
        set(value) {
            field = value
            setUniform("in_progress", value)
            setUniform("in_radius",
                    (1 - (1 - value) * (1 - value) * (1 - value))* radius)
            setUniform("in_blur", MathUtils.lerp(1.25f, 0.5f, value))

            val fadeIn = subProgress(0f, 0.1f, value)
            val fadeOutNoise = subProgress(0.4f, 1f, value)
            val fadeOutRipple = subProgress(0.3f, 1f, value)
            val fadeCircle = subProgress(0f, 0.2f, value)
            setUniform("in_fadeSparkle", Math.min(fadeIn, 1 - fadeOutNoise))
            setUniform("in_fadeCircle", 1 - fadeCircle)
            setUniform("in_fadeRing", Math.min(fadeIn, 1 - fadeOutRipple))
        }

    /**
     * Play time since the start of the effect.
     */
    var time: Float = 0.0f
        set(value) {
            field = value
            setUniform("in_time", value)
        }

    /**
     * A hex value representing the ripple color, in the format of ARGB
     */
    var color: Int = 0xffffff.toInt()
        set(value) {
            field = value
            val color = Color.valueOf(value)
            setUniform("in_color", floatArrayOf(color.red(),
                    color.green(), color.blue(), color.alpha()))
        }

    /**
     * Noise sparkle intensity. Expected value between [0, 1]. The sparkle is white, and thus
     * with strength 0 it's transparent, leaving the ripple fully smooth, while with strength 1
     * it's opaque white and looks the most grainy.
     */
    var sparkleStrength: Float = 0.0f
        set(value) {
            field = value
            setUniform("in_sparkle_strength", value)
        }

    /**
     * Distortion strength of the ripple. Expected value between[0, 1].
     */
    var distortionStrength: Float = 0.0f
        set(value) {
            field = value
            setUniform("in_distort_radial", 75 * progress * value)
            setUniform("in_distort_xy", 75 * value)
        }

    var pixelDensity: Float = 1.0f
        set(value) {
            field = value
            setUniform("in_pixelDensity", value)
        }
}
