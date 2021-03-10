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

final class RippleShader extends RuntimeShader {
    private static final String SHADER_UNIFORMS =  "uniform vec2 in_origin;\n"
            + "uniform float in_progress;\n"
            + "uniform float in_maxRadius;\n"
            + "uniform vec2 in_resolution;\n"
            + "uniform float in_hasMask;\n"
            + "uniform float in_secondsOffset;\n"
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
            + "\n"
            + "float threshold(float v, float l, float h) {\n"
            + "  return step(l, v) * (1.0 - step(h, v));\n"
            + "}\n"
            + "\n"
            + "float sparkles(vec2 uv, float t) {\n"
            + "  float n = triangleNoise(uv);\n"
            + "  float s = 0.0;\n"
            + "  for (float i = 0; i < 4; i += 1) {\n"
            + "    float l = i * 0.25;\n"
            + "    float h = l + 0.025;\n"
            + "    float o = abs(sin(0.1 * PI * (t + i)));\n"
            + "    s += threshold(n + o, l, h);\n"
            + "  }\n"
            + "  return saturate(s);\n"
            + "}\n"
            + "\n"
            + "float softCircle(vec2 uv, vec2 xy, float radius, float blur) {\n"
            + "  float blurHalf = blur * 0.5;\n"
            + "  float d = distance(uv, xy);\n"
            + "  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);\n"
            + "}\n"
            + "\n"
            + "float softRing(vec2 uv, vec2 xy, float radius, float blur) {\n"
            + "  float thickness = 0.4;\n"
            + "  float circle_outer = softCircle(uv, xy, radius + thickness * 0.5, blur);\n"
            + "  float circle_inner = softCircle(uv, xy, radius - thickness * 0.5, blur);\n"
            + "  return circle_outer - circle_inner;\n"
            + "}\n"
            + "\n"
            + "struct Viewport {\n"
            + "  float aspect;\n"
            + "  vec2 uv;\n"
            + "  vec2 resolution_pixels;\n"
            + "};\n"
            + "\n"
            + "Viewport getViewport(vec2 frag_coord, vec2 resolution_pixels) {\n"
            + "  Viewport v;\n"
            + "  v.aspect = resolution_pixels.y / resolution_pixels.x;\n"
            + "  v.uv = frag_coord / resolution_pixels;\n"
            + "  v.uv.y = (1.0 - v.uv.y) * v.aspect;\n"
            + "  v.resolution_pixels = resolution_pixels;\n"
            + "  return v;\n"
            + "}\n"
            + "\n"
            + "vec2 getTouch(vec2 touch_position_pixels, Viewport viewport) {\n"
            + "  vec2 touch = touch_position_pixels / viewport.resolution_pixels;\n"
            + "  touch.y *= viewport.aspect;\n"
            + "  return touch;\n"
            + "}\n"
            + "\n"
            + "struct Wave {\n"
            + "  float ring;\n"
            + "  float circle;\n"
            + "};\n"
            + "\n"
            + "Wave getWave(Viewport viewport, vec2 touch, float progress) {\n"
            + "  float fade = pow((clamp(progress, 0.8, 1.0)), 8.);\n"
            + "  Wave w;\n"
            + "  w.ring = max(softRing(viewport.uv, touch, progress, 0.45) - fade, 0.);\n"
            + "  w.circle = softCircle(viewport.uv, touch, 2.0 * progress, 0.2) - progress;\n"
            + "  return w;\n"
            + "}\n"
            + "\n"
            + "vec4 getRipple(vec4 color, float loudness, float sparkle, Wave wave) {\n"
            + "  float alpha = wave.ring * sparkle * loudness\n"
            + "        + wave.circle * color.a;\n"
            + "  return vec4(color.rgb, saturate(alpha));\n"
            + "}\n"
            + "\n"
            + "float getRingMask(vec2 frag, vec2 center, float r, float progress) {\n"
            + "      float dist = distance(frag, center);\n"
            + "      float expansion = r * .6;\n"
            + "      r = r * min(1.,progress);\n"
            + "      float minD = max(r - expansion, 0.);\n"
            + "      float maxD = r + expansion;\n"
            + "      if (dist > maxD || dist < minD) return .0;\n"
            + "      return min(maxD - dist, dist - minD) / expansion;    \n"
            + "}\n"
            + "\n"
            + "float subProgress(float start, float end, float progress) {\n"
            + "    float sub = clamp(progress, start, end);\n"
            + "    return (sub - start) / (end - start); \n"
            + "}\n";
    private static final String SHADER_MAIN = "vec4 main(vec2 p) {\n"
            + "    float fadeIn = subProgress(0., 0.175, in_progress);\n"
            + "    float fadeOutNoise = subProgress(0.375, 1., in_progress);\n"
            + "    float fadeOutRipple = subProgress(0.375, 0.75, in_progress);\n"
            + "    Viewport vp = getViewport(p, in_resolution);\n"
            + "    vec2 touch = getTouch(in_origin, vp);\n"
            + "    Wave w = getWave(vp, touch, in_progress * 0.25);\n"
            + "    float ring = getRingMask(p, in_origin, in_maxRadius, fadeIn);\n"
            + "    float alpha = min(fadeIn, 1. - fadeOutNoise);\n"
            + "    float sparkle = sparkles(p, in_progress * 0.25 + in_secondsOffset)\n"
            + "        * ring * alpha;\n"
            + "    vec4 r = getRipple(in_color, 1., sparkle, w);\n"
            + "    float fade = min(fadeIn, 1.-fadeOutRipple);\n"
            + "    vec4 circle = vec4(in_color.rgb, softCircle(p, in_origin, in_maxRadius "
            + "      * fadeIn, 0.2) * fade * in_color.a);\n"
            + "    float mask = in_hasMask == 1. ? sample(in_shader).a > 0. ? 1. : 0. : 1.;\n"
            + "    return mix(circle, vec4(1.), sparkle * mask);\n"
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
    public void setSecondsOffset(float t) {
        setUniform("in_secondsOffset", t);
    }

    public void setOrigin(float x, float y) {
        setUniform("in_origin", new float[] {x, y});
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

    public void setResolution(float w, float h) {
        setUniform("in_resolution", w, h);
    }
}
