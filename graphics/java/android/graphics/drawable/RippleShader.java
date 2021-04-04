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

package android.graphics.drawable;

import android.annotation.ColorInt;
import android.graphics.Color;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.DisplayMetrics;

final class RippleShader extends RuntimeShader {
    private static final String SHADER_UNIFORMS =  "uniform vec2 in_origin;\n"
            + "uniform vec2 in_touch;\n"
            + "uniform float in_progress;\n"
            + "uniform float in_maxRadius;\n"
            + "uniform vec2 in_resolutionScale;\n"
            + "uniform vec2 in_noiseScale;\n"
            + "uniform float in_hasMask;\n"
            + "uniform float in_noisePhase;\n"
            + "uniform vec4 in_color;\n"
            + "uniform shader in_shader;\n";
    private static final String SHADER_LIB =
            "float triangleNoise(vec2 n) {\n"
            + "    n  = fract(n * vec2(5.3987, 5.4421));\n"
            + "    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));\n"
            + "    float xy = n.x * n.y;\n"
            + "    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;\n"
            + "}"
            + "const float PI = 3.1415926535897932384626;\n"
            + "const float SPARKLE_OPACITY = 0.55;\n"
            + "\n"
            + "float sparkles(vec2 uv, float t) {\n"
            + "  float n = triangleNoise(uv);\n"
            + "  float s = 0.0;\n"
            + "  for (float i = 0; i < 4; i += 1) {\n"
            + "    float l = i * 0.01;\n"
            + "    float h = l + 0.1;\n"
            + "    float o = smoothstep(n - l, h, n);\n"
            + "    o *= abs(sin(PI * o * (t + 0.55 * i)));\n"
            + "    s += o;\n"
            + "  }\n"
            + "  return saturate(s) * SPARKLE_OPACITY;\n"
            + "}\n"
            + "\n"
            + "float softCircle(vec2 uv, vec2 xy, float radius, float blur) {\n"
            + "  float blurHalf = blur * 0.5;\n"
            + "  float d = distance(uv, xy);\n"
            + "  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);\n"
            + "}\n"
            + "float softRing(vec2 uv, vec2 xy, float radius, float progress, float blur) {\n"
            + "  float thickness = 0.3 * radius;\n"
            + "  float currentRadius = radius * progress;\n"
            + "  float circle_outer = softCircle(uv, xy, currentRadius + thickness, blur);\n"
            + "  float circle_inner = softCircle(uv, xy, currentRadius - thickness, blur);\n"
            + "  return saturate(circle_outer - circle_inner);\n"
            + "}\n"
            + "float subProgress(float start, float end, float progress) {\n"
            + "    float sub = clamp(progress, start, end);\n"
            + "    return (sub - start) / (end - start); \n"
            + "}\n";
    private static final String SHADER_MAIN = "vec4 main(vec2 p) {\n"
            + "    float fadeIn = subProgress(0., 0.175, in_progress);\n"
            + "    float fadeOutNoise = subProgress(0.375, 1., in_progress);\n"
            + "    float fadeOutRipple = subProgress(0.375, 0.75, in_progress);\n"
            + "    vec2 center = mix(in_touch, in_origin, fadeIn);\n"
            + "    float ring = softRing(p, center, in_maxRadius, fadeIn, 0.45);\n"
            + "    float alpha = 1. - fadeOutNoise;\n"
            + "    vec2 uv = p * in_resolutionScale;\n"
            + "    vec2 densityUv = uv - mod(uv, in_noiseScale);\n"
            + "    float sparkle = sparkles(densityUv, in_noisePhase) * ring * alpha;\n"
            + "    float fade = min(fadeIn, 1. - fadeOutRipple);\n"
            + "    vec4 circle = in_color * (softCircle(p, center, in_maxRadius "
            + "      * fadeIn, 0.2) * fade);\n"
            + "    float mask = in_hasMask == 1. ? sample(in_shader).a > 0. ? 1. : 0. : 1.;\n"
            + "    return mix(circle, vec4(sparkle), sparkle) * mask;\n"
            + "}";
    private static final String SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN;

    RippleShader() {
        super(SHADER, false);
    }

    public void setShader(Shader shader) {
        if (shader != null) {
            setInputShader("in_shader", shader);
        }
        setUniform("in_hasMask", shader == null ? 0 : 1);
    }

    public void setRadius(float radius) {
        setUniform("in_maxRadius", radius);
    }

    /**
     * Continuous offset used as noise phase.
     */
    public void setNoisePhase(float t) {
        setUniform("in_noisePhase", t);
    }

    public void setOrigin(float x, float y) {
        setUniform("in_origin", new float[] {x, y});
    }

    public void setTouch(float x, float y) {
        setUniform("in_touch", new float[] {x, y});
    }

    public void setProgress(float progress) {
        setUniform("in_progress", progress);
    }

    /**
     * Color of the circle that's under the sparkles. Sparkles will always be white.
     */
    public void setColor(@ColorInt int colorIn) {
        Color color = Color.valueOf(colorIn);
        this.setUniform("in_color", new float[] {color.red(),
                color.green(), color.blue(), color.alpha()});
    }

    public void setResolution(float w, float h, int density) {
        float densityScale = density * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        setUniform("in_resolutionScale", new float[] {1f / w, 1f / h});
        setUniform("in_noiseScale", new float[] {densityScale / w, densityScale / h});
    }
}
