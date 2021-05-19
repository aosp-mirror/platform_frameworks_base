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
            + "uniform float in_turbulencePhase;\n"
            + "uniform vec2 in_tCircle1;\n"
            + "uniform vec2 in_tCircle2;\n"
            + "uniform vec2 in_tCircle3;\n"
            + "uniform vec2 in_tRotation1;\n"
            + "uniform vec2 in_tRotation2;\n"
            + "uniform vec2 in_tRotation3;\n"
            + "uniform vec4 in_color;\n"
            + "uniform vec4 in_sparkleColor;\n"
            + "uniform shader in_shader;\n";
    private static final String SHADER_LIB =
            "float triangleNoise(vec2 n) {\n"
            + "  n  = fract(n * vec2(5.3987, 5.4421));\n"
            + "  n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));\n"
            + "  float xy = n.x * n.y;\n"
            + "  return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;\n"
            + "}"
            + "const float PI = 3.1415926535897932384626;\n"
            + "\n"
            + "float threshold(float v, float l, float h) {\n"
            + "    return step(l, v) * (1.0 - step(h, v));\n"
            + "}\n"
            + "float sparkles(vec2 uv, float t) {\n"
            + "  float n = triangleNoise(uv);\n"
            + "  float s = 0.0;\n"
            + "  for (float i = 0; i < 4; i += 1) {\n"
            + "    float l = i * 0.1;\n"
            + "    float h = l + 0.025;\n"
            + "    float o = sin(PI * (t + 0.35 * i));\n"
            + "    s += threshold(n + o, l, h);\n"
            + "  }\n"
            + "  return saturate(s) * in_sparkleColor.a;\n"
            + "}\n"
            + "float softCircle(vec2 uv, vec2 xy, float radius, float blur) {\n"
            + "  float blurHalf = blur * 0.5;\n"
            + "  float d = distance(uv, xy);\n"
            + "  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);\n"
            + "}\n"
            + "float softRing(vec2 uv, vec2 xy, float radius, float progress, float blur) {\n"
            + "  float thickness = 0.3 * radius;\n"
            + "  float currentRadius = radius * progress;\n"
            + "  float circle_outer = softCircle(uv, xy, currentRadius + thickness, blur);\n"
            + "  float circle_inner = softCircle(uv, xy, max(currentRadius - thickness, 0.), "
            + "    blur);\n"
            + "  return saturate(circle_outer - circle_inner);\n"
            + "}\n"
            + "float subProgress(float start, float end, float progress) {\n"
            + "    float sub = clamp(progress, start, end);\n"
            + "    return (sub - start) / (end - start); \n"
            + "}\n"
            + "mat2 rotate2d(vec2 rad){\n"
            + "  return mat2(rad.x, -rad.y, rad.y, rad.x);\n"
            + "}\n"
            + "float circle_grid(vec2 resolution, vec2 coord, float time, vec2 center,\n"
            + "    vec2 rotation, float cell_diameter) {\n"
            + "  coord = rotate2d(rotation) * (center - coord) + center;\n"
            + "  coord = mod(coord, cell_diameter) / resolution;\n"
            + "  float normal_radius = cell_diameter / resolution.y * 0.5;\n"
            + "  float radius = 0.65 * normal_radius;\n"
            + "  return softCircle(coord, vec2(normal_radius), radius, radius * 50.0);\n"
            + "}\n"
            + "float turbulence(vec2 uv, float t) {\n"
            + "  const vec2 scale = vec2(1.5);\n"
            + "  uv = uv * scale;\n"
            + "  float g1 = circle_grid(scale, uv, t, in_tCircle1, in_tRotation1, 0.17);\n"
            + "  float g2 = circle_grid(scale, uv, t, in_tCircle2, in_tRotation2, 0.2);\n"
            + "  float g3 = circle_grid(scale, uv, t, in_tCircle3, in_tRotation3, 0.275);\n"
            + "  float v = (g1 * g1 + g2 - g3) * 0.5;\n"
            + "  return saturate(0.45 + 0.8 * v);\n"
            + "}\n";
    private static final String SHADER_MAIN = "vec4 main(vec2 p) {\n"
            + "    float fadeIn = subProgress(0., 0.1, in_progress);\n"
            + "    float scaleIn = subProgress(0., 0.45, in_progress);\n"
            + "    float fadeOutNoise = subProgress(0.5, 0.95, in_progress);\n"
            + "    float fadeOutRipple = subProgress(0.5, 1., in_progress);\n"
            + "    vec2 center = mix(in_touch, in_origin, scaleIn);\n"
            + "    float ring = softRing(p, center, in_maxRadius, scaleIn, 0.45);\n"
            + "    float alpha = min(fadeIn, 1. - fadeOutNoise);\n"
            + "    vec2 uv = p * in_resolutionScale;\n"
            + "    vec2 densityUv = uv - mod(uv, in_noiseScale);\n"
            + "    float turbulence = turbulence(uv, in_turbulencePhase);\n"
            + "    float sparkleAlpha = sparkles(densityUv, in_noisePhase) * ring * alpha "
            + "* turbulence;\n"
            + "    float fade = min(fadeIn, 1. - fadeOutRipple);\n"
            + "    float waveAlpha = softCircle(p, center, in_maxRadius * scaleIn, 0.2) * fade "
            + "* in_color.a;\n"
            + "    vec4 waveColor = vec4(in_color.rgb * waveAlpha, waveAlpha);\n"
            + "    vec4 sparkleColor = vec4(in_sparkleColor.rgb * in_sparkleColor.a, "
            + "in_sparkleColor.a);\n"
            + "    float mask = in_hasMask == 1. ? sample(in_shader, p).a > 0. ? 1. : 0. : 1.;\n"
            + "    return mix(waveColor, sparkleColor, sparkleAlpha) * mask;\n"
            + "}";
    private static final String SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN;
    private static final double PI_ROTATE_RIGHT = Math.PI * 0.0078125;
    private static final double PI_ROTATE_LEFT = Math.PI * -0.0078125;

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
     * Continuous offset used as noise phase.
     */
    public void setNoisePhase(float phase) {
        setUniform("in_noisePhase", phase * 0.001f);

        //
        // Keep in sync with: frameworks/base/libs/hwui/pipeline/skia/AnimatedDrawables.h
        //
        final float turbulencePhase = phase;
        setUniform("in_turbulencePhase", turbulencePhase);
        final float scale = 1.5f;
        setUniform("in_tCircle1", new float[]{
                (float) (scale * 0.5 + (turbulencePhase * 0.01 * Math.cos(scale * 0.55))),
                (float) (scale * 0.5 + (turbulencePhase * 0.01 * Math.sin(scale * 0.55)))
        });
        setUniform("in_tCircle2", new float[]{
                (float) (scale * 0.2 + (turbulencePhase * -0.0066 * Math.cos(scale * 0.45))),
                (float) (scale * 0.2 + (turbulencePhase * -0.0066 * Math.sin(scale * 0.45)))
        });
        setUniform("in_tCircle3", new float[]{
                (float) (scale + (turbulencePhase * -0.0066 * Math.cos(scale * 0.35))),
                (float) (scale + (turbulencePhase * -0.0066 * Math.sin(scale * 0.35)))
        });
        final double rotation1 = turbulencePhase * PI_ROTATE_RIGHT + 1.7 * Math.PI;
        setUniform("in_tRotation1", new float[]{
                (float) Math.cos(rotation1), (float) Math.sin(rotation1)
        });
        final double rotation2 = turbulencePhase * PI_ROTATE_LEFT + 2 * Math.PI;
        setUniform("in_tRotation2", new float[]{
                (float) Math.cos(rotation2), (float) Math.sin(rotation2)
        });
        final double rotation3 = turbulencePhase * PI_ROTATE_RIGHT + 2.75 * Math.PI;
        setUniform("in_tRotation3", new float[]{
                (float) Math.cos(rotation3), (float) Math.sin(rotation3)
        });
    }

    /**
     * Color of the circle that's under the sparkles. Sparkles will always be white.
     */
    public void setColor(@ColorInt int colorInt, @ColorInt int sparkleColorInt) {
        Color color = Color.valueOf(colorInt);
        Color sparkleColor = Color.valueOf(sparkleColorInt);
        setUniform("in_color", new float[] {color.red(),
                color.green(), color.blue(), color.alpha()});
        setUniform("in_sparkleColor", new float[] {sparkleColor.red(),
                sparkleColor.green(), sparkleColor.blue(), sparkleColor.alpha()});
    }

    public void setResolution(float w, float h, int density) {
        final float densityScale = density * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        setUniform("in_resolutionScale", new float[] {1f / w, 1f / h});
        setUniform("in_noiseScale", new float[] {densityScale / w, densityScale / h});
    }
}
