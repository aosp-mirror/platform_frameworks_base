/*
 * Copyright (C) 2022 The Android Open Source Project
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

/** A common utility functions that are used for computing [RippleShader]. */
class RippleShaderUtilLibrary {
    //language=AGSL
    companion object {
        const val SHADER_LIB = """
            float triangleNoise(vec2 n) {
                    n  = fract(n * vec2(5.3987, 5.4421));
                    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));
                    float xy = n.x * n.y;
                    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
                }
                const float PI = 3.1415926535897932384626;

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

                vec2 distort(vec2 p, float time, float distort_amount_radial,
                    float distort_amount_xy) {
                        float angle = atan(p.y, p.x);
                          return p + vec2(sin(angle * 8 + time * 0.003 + 1.641),
                                    cos(angle * 5 + 2.14 + time * 0.00412)) * distort_amount_radial
                             + vec2(sin(p.x * 0.01 + time * 0.00215 + 0.8123),
                                    cos(p.y * 0.01 + time * 0.005931)) * distort_amount_xy;
            }"""
    }
}
