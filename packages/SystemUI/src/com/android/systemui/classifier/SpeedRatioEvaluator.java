/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

public class SpeedRatioEvaluator {
    public static float evaluate(float value) {
        float evaluation = 0.0f;
        if (value == 0) return 0;
        if (value <= 1.0) evaluation++;
        if (value <= 0.5) evaluation++;
        if (value > 9.0) evaluation++;
        if (value > 18.0) evaluation++;
        return evaluation;
    }
}
