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

public class AnglesPercentageEvaluator {
    public static float evaluate(float value, int type) {
        final boolean secureUnlock = type == Classifier.BOUNCER_UNLOCK;
        float evaluation = 0.0f;
        if (value < 1.00 && !secureUnlock) evaluation++;
        if (value < 0.90 && !secureUnlock) evaluation++;
        if (value < 0.70) evaluation++;
        return evaluation;
    }
}
