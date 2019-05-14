/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.util.Slog;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A helper class for handling access to illuminance hysteresis level values.
 */
final class HysteresisLevels {
    private static final String TAG = "HysteresisLevels";

    // Default hysteresis constraints for brightening or darkening.
    // The recent value must have changed by at least this fraction relative to the
    // current value before a change will be considered.
    private static final float DEFAULT_BRIGHTENING_HYSTERESIS = 0.10f;
    private static final float DEFAULT_DARKENING_HYSTERESIS = 0.20f;

    private static final boolean DEBUG = false;

    private final float[] mBrighteningThresholds;
    private final float[] mDarkeningThresholds;
    private final float[] mThresholdLevels;

    /**
     * Creates a {@code HysteresisLevels} object with the given equal-length
     * integer arrays.
     * @param brighteningThresholds an array of brightening hysteresis constraint constants.
     * @param darkeningThresholds an array of darkening hysteresis constraint constants.
     * @param thresholdLevels a monotonically increasing array of threshold levels.
    */
    HysteresisLevels(int[] brighteningThresholds, int[] darkeningThresholds,
            int[] thresholdLevels) {
        if (brighteningThresholds.length != darkeningThresholds.length
                || darkeningThresholds.length != thresholdLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrighteningThresholds = setArrayFormat(brighteningThresholds, 1000.0f);
        mDarkeningThresholds = setArrayFormat(darkeningThresholds, 1000.0f);
        mThresholdLevels = setArrayFormat(thresholdLevels, 1.0f);
    }

    /**
     * Return the brightening hysteresis threshold for the given value level.
     */
    float getBrighteningThreshold(float value) {
        float brightConstant = getReferenceLevel(value, mBrighteningThresholds);
        float brightThreshold = value * (1.0f + brightConstant);
        if (DEBUG) {
            Slog.d(TAG, "bright hysteresis constant=" + brightConstant + ", threshold="
                    + brightThreshold + ", value=" + value);
        }
        return brightThreshold;
    }

    /**
     * Return the darkening hysteresis threshold for the given value level.
     */
    float getDarkeningThreshold(float value) {
        float darkConstant = getReferenceLevel(value, mDarkeningThresholds);
        float darkThreshold = value * (1.0f - darkConstant);
        if (DEBUG) {
            Slog.d(TAG, "dark hysteresis constant=: " + darkConstant + ", threshold="
                    + darkThreshold + ", value=" + value);
        }
        return darkThreshold;
    }

    /**
     * Return the hysteresis constant for the closest threshold value from the given array.
     */
    private float getReferenceLevel(float value, float[] referenceLevels) {
        int index = 0;
        while (mThresholdLevels.length > index && value >= mThresholdLevels[index]) {
            ++index;
        }
        return referenceLevels[index];
    }

    /**
     * Return a float array where each i-th element equals {@code configArray[i]/divideFactor}.
     */
    private float[] setArrayFormat(int[] configArray, float divideFactor) {
        float[] levelArray = new float[configArray.length];
        for (int index = 0; levelArray.length > index; ++index) {
            levelArray[index] = (float)configArray[index] / divideFactor;
        }
        return levelArray;
    }

    void dump(PrintWriter pw) {
        pw.println("HysteresisLevels");
        pw.println("  mBrighteningThresholds=" + Arrays.toString(mBrighteningThresholds));
        pw.println("  mDarkeningThresholds=" + Arrays.toString(mDarkeningThresholds));
        pw.println("  mThresholdLevels=" + Arrays.toString(mThresholdLevels));
    }
}
