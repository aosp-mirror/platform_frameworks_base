/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities.easing;

/**
 * Provide a specific bouncing easing function
 */
public class BounceCurve extends Easing {
    private static final float N1 = 7.5625f;
    private static final float D1 = 2.75f;

    BounceCurve(int type) {
        mType = type;
    }

    @Override
    public float get(float x) {
        float t = x;
        if (t < 0) {
            return 0f;
        }
        if (t < 1 / D1) {
            return 1 / (1 + 1 / D1) * (N1 * t * t + t);
        } else if (t < 2 / D1) {
            t -= 1.5f / D1;
            return N1 * t * t + 0.75f;
        } else if (t < 2.5 / D1) {
            t -= 2.25f / D1;
            return N1 * t * t + 0.9375f;
        } else if (t <= 1) {
            t -= 2.625f / D1;
            return N1 * t * t + 0.984375f;
        }
        return 1f;
    }

    @Override
    public float getDiff(float x) {
        if (x < 0) {
            return 0f;
        }
        if (x < 1 / D1) {
            return 2 * N1 * x / (1 + 1 / D1) + 1 / (1 + 1 / D1);
        } else if (x < 2 / D1) {
            return 2 * N1 * (x - 1.5f / D1);
        } else if (x < 2.5 / D1) {
            return 2 * N1 * (x - 2.25f / D1);
        } else if (x <= 1) {
            return 2 * N1 * (x - 2.625f / D1);
        }
        return 0f;
    }

}
