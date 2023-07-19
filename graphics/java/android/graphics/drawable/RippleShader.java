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
import android.graphics.RuntimeShader;
import android.graphics.Shader;

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
            + "layout(color) uniform vec4 in_color;\n"
            + "layout(color) uniform vec4 in_sparkleColor;\n"
            + "uniform shader in_shader;\n";
    private static final String SHADER_LIB =
            "// White noise with triangular distribution\n"
            + "float triangleNoise(vec2 n) {\n"
            + "    n  = fract(n * vec2(5.3987, 5.4421));\n"
            + "    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));\n"
            + "    float xy = n.x * n.y;\n"
            + "    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;\n"
            + "}\n"
            + "\n"
            + "// PDF for Gaussian blur\n"
            + "// Specialized for mean=0 for performance\n"
            + "const float SQRT_2PI = 2.506628274631000241612355;\n"
            + "float gaussian_pdf(float stddev, float x) {\n"
            + "    float a = x / stddev;\n"
            + "    return exp(-0.5 * a*a) / (stddev * SQRT_2PI);\n"
            + "}\n"
            + "\n"
            + "// Circular wave with Gaussian blur\n"
            + "float softWave(vec2 uv, vec2 center, float maxRadius, float radius, float "
            + "blur) {\n"
            + "    // Distance from the center of the circle (touch point), normalized to"
            + " [0, 1]  radius)\n"
            + "    float dNorm = distance(uv, center) / maxRadius;\n"
            + "    // Position on the Gaussian PDF, clamped to 0 to fill the area of the circle\n"
            + "    float x = min(0.0, radius - dNorm);\n"
            + "    // Apply Gaussian blur with dynamic stddev and scale to reduce lightness\n"
            + "    return gaussian_pdf(0.05 + 0.15 * blur, x) * 0.4;\n"
            + "}\n"
            + "\n"
            + "float subProgress(float start, float end, float progress) {\n"
            + "    return saturate((progress - start) / (end - start));\n"
            + "}\n"
            + "\n"
            + "// Animation curve\n"
            + "const float PI = 3.141592653589793;\n"
            + "float easeOutSine(float x) {\n"
            + "    return sin((x * PI) / 2.0);\n"
            + "}";
    private static final String SHADER_MAIN = "vec4 main(vec2 pos) {\n"
            + "    // Curve the linear animation progress for responsiveness\n"
            + "    float progress = easeOutSine(in_progress);\n"
            + "\n"
            + "    // Show highlight immediately instead of fading in for instant feedback\n"
            + "    // Fade the entire ripple out, including base highlight\n"
            + "    float fadeOut = subProgress(0.5, 1.0, progress);\n"
            + "    float fade = 1.0 - fadeOut;\n"
            + "\n"
            + "    // Turbulence phase = time. Unlike progress, it continues moving when the\n"
            + "    // ripple is held between enter and exit animations, so we can use it to\n"
            + "    // make a hold animation.\n"
            + "\n"
            + "    // Hold time increases the radius slightly to progress the animation.\n"
            + "    float timeOffsetMs = 0.0;\n"
            + "    float waveProgress = progress + timeOffsetMs / 60.0;\n"
            + "    // Blur radius decreases as the animation progresses, but increases with hold "
            + "time\n"
            + "    // as part of gradually spreading out.\n"
            + "    float waveBlur = 1.3 - waveProgress + (timeOffsetMs / 15.0);\n"
            + "    // The wave also fades out with hold time.\n"
            + "    float waveFade = saturate(1.0 - timeOffsetMs / 20.0);\n"
            + "    // Calculate wave color, excluding fade\n"
            + "    float waveAlpha = softWave(pos, in_touch, in_maxRadius / 2.3, waveProgress, "
            + "waveBlur);\n"
            + "\n"
            + "    // Dither with triangular white noise. Unfortunately, we can't use blue noise\n"
            + "    // because RuntimeShader doesn't allow us to add custom textures.\n"
            + "    float dither = triangleNoise(pos) / 128.0;\n"
            + "\n"
            + "    // 0.5 base highlight + foreground ring\n"
            + "    float finalAlpha = (0.5 + waveAlpha * waveFade) * fade * in_color.a + dither;\n"
            + "    vec4 finalColor = vec4(in_color.rgb * finalAlpha, finalAlpha);\n"
            + "\n"
            + "    float mask = in_hasMask == 1.0 ? in_shader.eval(pos).a > 0.0 ? 1.0 : 0.0 : "
            + "1.0;\n"
            + "    return finalColor * mask;\n"
            + "}";
    private static final String SHADER = SHADER_UNIFORMS + SHADER_LIB + SHADER_MAIN;
    private static final double PI_ROTATE_RIGHT = Math.PI * 0.0078125;
    private static final double PI_ROTATE_LEFT = Math.PI * -0.0078125;

    RippleShader() {
        super(SHADER);
    }

    public void setShader(Shader shader) {
        if (shader != null) {
            setInputShader("in_shader", shader);
        }
        setFloatUniform("in_hasMask", shader == null ? 0 : 1);
    }

    public void setRadius(float radius) {
        setFloatUniform("in_maxRadius", radius * 2.3f);
    }

    public void setOrigin(float x, float y) {
        setFloatUniform("in_origin", x, y);
    }

    public void setTouch(float x, float y) {
        setFloatUniform("in_touch", x, y);
    }

    public void setProgress(float progress) {
        setFloatUniform("in_progress", progress);
    }

    /**
     * Continuous offset used as noise phase.
     */
    public void setNoisePhase(float phase) {
        setFloatUniform("in_noisePhase", phase * 0.001f);

        //
        // Keep in sync with: frameworks/base/libs/hwui/pipeline/skia/AnimatedDrawables.h
        //
        final float turbulencePhase = phase;
        setFloatUniform("in_turbulencePhase", turbulencePhase);
        final float scale = 1.5f;
        setFloatUniform("in_tCircle1",
                (float) (scale * 0.5 + (turbulencePhase * 0.01 * Math.cos(scale * 0.55))),
                (float) (scale * 0.5 + (turbulencePhase * 0.01 * Math.sin(scale * 0.55))));
        setFloatUniform("in_tCircle2",
                (float) (scale * 0.2 + (turbulencePhase * -0.0066 * Math.cos(scale * 0.45))),
                (float) (scale * 0.2 + (turbulencePhase * -0.0066 * Math.sin(scale * 0.45))));
        setFloatUniform("in_tCircle3",
                (float) (scale + (turbulencePhase * -0.0066 * Math.cos(scale * 0.35))),
                (float) (scale + (turbulencePhase * -0.0066 * Math.sin(scale * 0.35))));
        final double rotation1 = turbulencePhase * PI_ROTATE_RIGHT + 1.7 * Math.PI;
        setFloatUniform("in_tRotation1",
                (float) Math.cos(rotation1), (float) Math.sin(rotation1));
        final double rotation2 = turbulencePhase * PI_ROTATE_LEFT + 2 * Math.PI;
        setFloatUniform("in_tRotation2",
                (float) Math.cos(rotation2), (float) Math.sin(rotation2));
        final double rotation3 = turbulencePhase * PI_ROTATE_RIGHT + 2.75 * Math.PI;
        setFloatUniform("in_tRotation3",
                (float) Math.cos(rotation3), (float) Math.sin(rotation3));
    }

    /**
     * Color of the circle that's under the sparkles. Sparkles will always be white.
     */
    public void setColor(@ColorInt int colorInt, @ColorInt int sparkleColorInt) {
        setColorUniform("in_color", colorInt);
        setColorUniform("in_sparkleColor", sparkleColorInt);
    }

    public void setResolution(float w, float h) {
        final float densityScale = 2.1f;
        setFloatUniform("in_resolutionScale", 1f / w, 1f / h);
        setFloatUniform("in_noiseScale", densityScale / w, densityScale / h);
    }
}
