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
import android.annotation.NonNull;
import android.graphics.Color;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

final class RippleShader extends RuntimeShader {
    private static final String SHADER = "uniform float2 in_origin;\n"
            + "uniform float in_maxRadius;\n"
            + "uniform float in_progress;\n"
            + "uniform float in_hasMask;\n"
            + "uniform float4 in_color;\n"
            + "uniform shader in_shader;\n"
            + "float dist2(float2 p0, float2 pf) { return sqrt((pf.x - p0.x) * (pf.x - p0.x) + "
            + "(pf.y - p0.y) * (pf.y - p0.y)); }\n"
            + "float mod2(float a, float b) { return a - (b * floor(a / b)); }\n"
            + "float rand(float2 src) { return fract(sin(dot(src.xy, float2(12.9898, 78.233))) * "
            + "43758.5453123); }\n"
            + "float4 main(float2 p)\n"
            + "{\n"
            + "    float fraction = in_progress;\n"
            + "    float2 fragCoord = p;//sk_FragCoord.xy;\n"
            + "    float maxDist = in_maxRadius;\n"
            + "    float fragDist = dist2(in_origin, fragCoord.xy);\n"
            + "    float circleRadius = maxDist * fraction;\n"
            + "    float colorVal = (fragDist - circleRadius) / maxDist;\n"
            + "    float d = fragDist < circleRadius \n"
            + "        ? 1. - abs(colorVal * 3. * smoothstep(0., 1., fraction)) \n"
            + "        : 1. - abs(colorVal * 5.);\n"
            + "    d = smoothstep(0., 1., d);\n"
            + "    float divider = 2.;\n"
            + "    float x = floor(fragCoord.x / divider);\n"
            + "    float y = floor(fragCoord.y / divider);\n"
            + "    float density = .95;\n"
            + "    d = rand(float2(x, y)) > density ? d : d * .2;\n"
            + "    d = d * rand(float2(fraction, x * y));\n"
            + "    float alpha = 1. - pow(fraction, 2.);\n"
            + "    if (in_hasMask != 0.) {return sample(in_shader).a * in_color * d * alpha;}\n"
            + "    return in_color * d * alpha;\n"
            + "}\n";

    RippleShader() {
        super(SHADER, false);
    }

    public void setShader(@NonNull Shader s) {
        setInputShader("in_shader", s);
    }

    public void setRadius(float radius) {
        setUniform("in_maxRadius", radius);
    }

    public void setOrigin(float x, float y) {
        setUniform("in_origin", new float[] {x, y});
    }

    public void setProgress(float progress) {
        setUniform("in_progress", progress);
    }

    public void setHasMask(boolean hasMask) {
        setUniform("in_hasMask", hasMask ? 1 : 0);
    }

    public void setColor(@ColorInt int colorIn) {
        Color color = Color.valueOf(colorIn);
        this.setUniform("in_color", new float[] {color.red(),
                color.green(), color.blue(), color.alpha()});
    }
}
