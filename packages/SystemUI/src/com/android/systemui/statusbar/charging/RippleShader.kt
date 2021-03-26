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
                uniform float in_noisePhase;
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
                    float l = i * 0.25;
                    float h = l + 0.005;
                    float o = abs(sin(0.1 * PI * (t + i)));
                    s += threshold(n + o, l, h);
                  }
                  return saturate(s);
                }

                float softCircle(vec2 uv, vec2 xy, float radius, float blur) {
                  float blurHalf = blur * 0.5;
                  float d = distance(uv, xy);
                  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);
                }

                float softRing(vec2 uv, vec2 xy, float radius, float blur) {
                  float thickness = 0.4;
                  float circle_outer = softCircle(uv, xy,
                      radius + thickness * radius * 0.5, blur);
                  float circle_inner = softCircle(uv, xy,
                      radius - thickness * radius * 0.5, blur);
                  return circle_outer - circle_inner;
                }

                float subProgress(float start, float end, float progress) {
                    float sub = clamp(progress, start, end);
                    return (sub - start) / (end - start);
                }

                float smoothstop2(float t) {
                  return 1 - (1 - t) * (1 - t);
                }"""
        private const val SHADER_MAIN = """vec4 main(vec2 p) {
                    float fadeIn = subProgress(0., 0.1, in_progress);
                    float fadeOutNoise = subProgress(0.8, 1., in_progress);
                    float fadeOutRipple = subProgress(0.7, 1., in_progress);
                    float fadeCircle = subProgress(0., 0.5, in_progress);
                    float radius = smoothstop2(in_progress) * in_maxRadius;
                    float sparkleRing = softRing(p, in_origin, radius, 0.5);
                    float sparkleAlpha = min(fadeIn, 1. - fadeOutNoise);
                    float sparkle = sparkles(p, in_noisePhase) * sparkleRing * sparkleAlpha;
                    float circle = softCircle(p, in_origin, radius * 1.2, 0.5)
                        * (1 - fadeCircle);
                    float fadeRipple = min(fadeIn, 1.-fadeOutRipple);
                    float rippleAlpha = softRing(p, in_origin, radius, 0.5)
                        * fadeRipple * in_color.a;
                    vec4 ripple = in_color * max(circle, rippleAlpha) * 0.3;
                    return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);
                }"""
        private const val SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN
    }

    /**
     * Maximum radius of the ripple.
     */
    var radius: Float = 0.0f
        set(value) { setUniform("in_maxRadius", value) }

    /**
     * Origin coordinate of the ripple.
     */
    var origin: PointF = PointF()
        set(value) { setUniform("in_origin", floatArrayOf(value.x, value.y)) }

    /**
     * Progress of the ripple. Float value between [0, 1].
     */
    var progress: Float = 0.0f
        set(value) { setUniform("in_progress", value) }

    /**
     * Continuous offset used as noise phase.
     */
    var noisePhase: Float = 0.0f
        set(value) { setUniform("in_noisePhase", value) }

    /**
     * A hex value representing the ripple color, in the format of ARGB
     */
    var color: Int = 0xffffff.toInt()
        set(value) {
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
        set(value) { setUniform("in_sparkle_strength", value) }
}
