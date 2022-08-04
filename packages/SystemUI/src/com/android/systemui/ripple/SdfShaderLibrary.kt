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

/** Library class that contains 2D signed distance functions. */
class SdfShaderLibrary {
    companion object {
        const val CIRCLE_SDF = """
            float sdCircle(vec2 p, float r) {
                return (length(p)-r) / r;
            }

            float softCircle(vec2 p, vec2 origin, float radius, float blur) {
                float d = sdCircle(p-origin, radius);
                float blurHalf = blur * 0.5;
                return smoothstep(-blurHalf, blurHalf, d);
            }

            float softRing(vec2 p, vec2 origin, float radius, float blur) {
                float thicknessHalf = radius * 0.25;

                float outerCircle = sdCircle(p-origin, radius + thicknessHalf);
                float innerCircle = sdCircle(p-origin, radius);

                float d = max(outerCircle, -innerCircle);
                float blurHalf = blur * 0.5;

                return smoothstep(-blurHalf, blurHalf, d);
            }
        """

        const val ROUNDED_BOX_SDF = """
            float sdRoundedBox(vec2 p, vec2 size, float cornerRadius) {
                size *= 0.5;
                cornerRadius *= 0.5;
                vec2 d = abs(p)-size+cornerRadius;

                float outside = length(max(d, 0.0));
                float inside = min(max(d.x, d.y), 0.0);

                return (outside+inside-cornerRadius)/size.y;
            }

            float softRoundedBox(vec2 p, vec2 origin, vec2 size, float cornerRadius, float blur) {
                float d = sdRoundedBox(p-origin, size, cornerRadius);
                float blurHalf = blur * 0.5;
                return smoothstep(-blurHalf, blurHalf, d);
            }

            float softRoundedBoxRing(vec2 p, vec2 origin, vec2 size, float cornerRadius,
                float borderThickness, float blur) {

                float outerRoundBox = sdRoundedBox(p-origin, size, cornerRadius);
                float innerRoundBox = sdRoundedBox(p-origin, size - vec2(borderThickness),
                    cornerRadius - borderThickness);

                float d = max(outerRoundBox, -innerRoundBox);
                float blurHalf = blur * 0.5;

                return smoothstep(-blurHalf, blurHalf, d);
            }
        """
    }
}
