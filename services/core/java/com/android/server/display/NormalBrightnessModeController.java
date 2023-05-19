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

package com.android.server.display;

import android.os.PowerManager;

import java.util.HashMap;
import java.util.Map;

class NormalBrightnessModeController {
    private Map<Float, Float> mTransitionPoints = new HashMap<>();

    // brightness limit in normal brightness mode, based on ambient lux.
    private float mVirtualTransitionPoint = PowerManager.BRIGHTNESS_MAX;

    boolean onAmbientLuxChange(float ambientLux) {
        float currentAmbientBoundary = Float.MAX_VALUE;
        float currentTransitionPoint = PowerManager.BRIGHTNESS_MAX;
        for (Map.Entry<Float, Float> transitionPoint: mTransitionPoints.entrySet()) {
            float ambientBoundary = transitionPoint.getKey();
            // find ambient lux upper boundary closest to current ambient lux
            if (ambientBoundary > ambientLux && ambientBoundary < currentAmbientBoundary) {
                currentTransitionPoint = transitionPoint.getValue();
                currentAmbientBoundary = ambientBoundary;
            }
        }
        if (mVirtualTransitionPoint != currentTransitionPoint) {
            mVirtualTransitionPoint = currentTransitionPoint;
            return true;
        }
        return false;
    }

    float getCurrentBrightnessMax() {
        return mVirtualTransitionPoint;
    }
}
