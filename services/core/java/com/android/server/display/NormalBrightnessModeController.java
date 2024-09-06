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

import android.annotation.NonNull;
import android.os.PowerManager;

import com.android.server.display.DisplayDeviceConfig.BrightnessLimitMapType;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits brightness for normal-brightness mode, based on ambient lux
 **/
class NormalBrightnessModeController {
    @NonNull
    private Map<BrightnessLimitMapType, Map<Float, Float>> mMaxBrightnessLimits = new HashMap<>();
    private float mAmbientLux = Float.MAX_VALUE;
    private boolean mAutoBrightnessEnabled = false;

    // brightness limit in normal brightness mode, based on ambient lux.
    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;

    boolean onAmbientLuxChange(float ambientLux) {
        mAmbientLux = ambientLux;
        return recalculateMaxBrightness();
    }

    boolean setAutoBrightnessState(int state) {
        boolean isEnabled = state == AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
        if (isEnabled != mAutoBrightnessEnabled) {
            mAutoBrightnessEnabled = isEnabled;
            return recalculateMaxBrightness();
        }
        return false;
    }

    float getCurrentBrightnessMax() {
        return mMaxBrightness;
    }

    boolean resetNbmData(
            @NonNull Map<BrightnessLimitMapType, Map<Float, Float>> maxBrightnessLimits) {
        mMaxBrightnessLimits = maxBrightnessLimits;
        return recalculateMaxBrightness();
    }

    void dump(PrintWriter pw) {
        pw.println("NormalBrightnessModeController:");
        pw.println("  mAutoBrightnessEnabled=" + mAutoBrightnessEnabled);
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mMaxBrightness=" + mMaxBrightness);
        pw.println("  mMaxBrightnessLimits=" + mMaxBrightnessLimits);
    }

    private boolean recalculateMaxBrightness() {
        float foundAmbientBoundary = Float.MAX_VALUE;
        float foundMaxBrightness = PowerManager.BRIGHTNESS_MAX;

        Map<Float, Float> maxBrightnessPoints = null;

        if (mAutoBrightnessEnabled) {
            maxBrightnessPoints = mMaxBrightnessLimits.get(BrightnessLimitMapType.ADAPTIVE);
        }

        // AutoBrightnessController sends ambientLux values *only* when auto brightness enabled.
        // Temporary disabling this Controller if auto brightness is off, to avoid capping
        // brightness based on stale ambient lux. The issue is tracked here: b/322445088
        if (mAutoBrightnessEnabled && maxBrightnessPoints == null) {
            maxBrightnessPoints = mMaxBrightnessLimits.get(BrightnessLimitMapType.DEFAULT);
        }
        if (maxBrightnessPoints != null) {
            for (Map.Entry<Float, Float> brightnessPoint : maxBrightnessPoints.entrySet()) {
                float ambientBoundary = brightnessPoint.getKey();
                // find ambient lux upper boundary closest to current ambient lux
                if (ambientBoundary > mAmbientLux && ambientBoundary < foundAmbientBoundary) {
                    foundMaxBrightness = brightnessPoint.getValue();
                    foundAmbientBoundary = ambientBoundary;
                }
            }
        }

        if (mMaxBrightness != foundMaxBrightness) {
            mMaxBrightness = foundMaxBrightness;
            return true;
        }
        return false;
    }
}
