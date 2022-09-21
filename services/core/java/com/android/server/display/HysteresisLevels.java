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
public class HysteresisLevels {
    private static final String TAG = "HysteresisLevels";

    private static final boolean DEBUG = false;

    private final float[] mBrighteningThresholds;
    private final float[] mDarkeningThresholds;
    private final float[] mThresholdLevels;
    private final float mMinDarkening;
    private final float mMinBrightening;

    /**
     * Creates a {@code HysteresisLevels} object for ambient brightness.
     * @param brighteningThresholds an array of brightening hysteresis constraint constants.
     * @param darkeningThresholds an array of darkening hysteresis constraint constants.
     * @param thresholdLevels a monotonically increasing array of threshold levels.
     * @param minBrighteningThreshold the minimum value for which the brightening value needs to
     *                                return.
     * @param minDarkeningThreshold the minimum value for which the darkening value needs to return.
    */
    HysteresisLevels(int[] brighteningThresholds, int[] darkeningThresholds,
            int[] thresholdLevels, float minDarkeningThreshold, float minBrighteningThreshold) {
        if (brighteningThresholds.length != darkeningThresholds.length
                || darkeningThresholds.length != thresholdLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrighteningThresholds = setArrayFormat(brighteningThresholds, 1000.0f);
        mDarkeningThresholds = setArrayFormat(darkeningThresholds, 1000.0f);
        mThresholdLevels = setArrayFormat(thresholdLevels, 1.0f);
        mMinDarkening = minDarkeningThreshold;
        mMinBrightening = minBrighteningThreshold;
    }

    /**
     * Creates a {@code HysteresisLevels} object for screen brightness.
     * @param brighteningThresholds an array of brightening hysteresis constraint constants.
     * @param darkeningThresholds an array of darkening hysteresis constraint constants.
     * @param thresholdLevels a monotonically increasing array of threshold levels.
     * @param minBrighteningThreshold the minimum value for which the brightening value needs to
     *                                return.
     * @param minDarkeningThreshold the minimum value for which the darkening value needs to return.
     */
    HysteresisLevels(int[] brighteningThresholds, int[] darkeningThresholds,
            float[] thresholdLevels, float minDarkeningThreshold, float minBrighteningThreshold) {
        if (brighteningThresholds.length != darkeningThresholds.length
                || darkeningThresholds.length != thresholdLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrighteningThresholds = setArrayFormat(brighteningThresholds, 1000.0f);
        mDarkeningThresholds = setArrayFormat(darkeningThresholds, 1000.0f);
        mThresholdLevels = constraintInRangeIfNeeded(thresholdLevels);
        mMinDarkening = minDarkeningThreshold;
        mMinBrightening = minBrighteningThreshold;
    }

    /**
     * Return the brightening hysteresis threshold for the given value level.
     */
    public float getBrighteningThreshold(float value) {
        final float brightConstant = getReferenceLevel(value, mBrighteningThresholds);
        float brightThreshold = value * (1.0f + brightConstant);
        if (DEBUG) {
            Slog.d(TAG, "bright hysteresis constant=" + brightConstant + ", threshold="
                    + brightThreshold + ", value=" + value);
        }

        brightThreshold = Math.max(brightThreshold, value + mMinBrightening);
        return brightThreshold;
    }

    /**
     * Return the darkening hysteresis threshold for the given value level.
     */
    public float getDarkeningThreshold(float value) {
        final float darkConstant = getReferenceLevel(value, mDarkeningThresholds);
        float darkThreshold = value * (1.0f - darkConstant);
        if (DEBUG) {
            Slog.d(TAG, "dark hysteresis constant=: " + darkConstant + ", threshold="
                    + darkThreshold + ", value=" + value);
        }
        darkThreshold = Math.min(darkThreshold, value - mMinDarkening);
        return Math.max(darkThreshold, 0.0f);
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
            levelArray[index] = (float) configArray[index] / divideFactor;
        }
        return levelArray;
    }

    /**
     * This check is due to historical reasons, where screen thresholdLevels used to be
     * integer values in the range of [0-255], but then was changed to be float values from [0,1].
     * To accommodate both the possibilities, we first check if all the thresholdLevels are in [0,
     * 1], and if not, we divide all the levels with 255 to bring them down to the same scale.
     */
    private float[] constraintInRangeIfNeeded(float[] thresholdLevels) {
        if (isAllInRange(thresholdLevels, /* minValueInclusive = */ 0.0f, /* maxValueInclusive = */
                1.0f)) {
            return thresholdLevels;
        }

        Slog.w(TAG, "Detected screen thresholdLevels on a deprecated brightness scale");
        float[] thresholdLevelsScaled = new float[thresholdLevels.length];
        for (int index = 0; thresholdLevels.length > index; ++index) {
            thresholdLevelsScaled[index] = thresholdLevels[index] / 255.0f;
        }
        return thresholdLevelsScaled;
    }

    private boolean isAllInRange(float[] configArray, float minValueInclusive,
            float maxValueInclusive) {
        int configArraySize = configArray.length;
        for (int index = 0; configArraySize > index; ++index) {
            if (configArray[index] < minValueInclusive || configArray[index] > maxValueInclusive) {
                return false;
            }
        }
        return true;
    }

    void dump(PrintWriter pw) {
        pw.println("HysteresisLevels");
        pw.println("  mBrighteningThresholds=" + Arrays.toString(mBrighteningThresholds));
        pw.println("  mDarkeningThresholds=" + Arrays.toString(mDarkeningThresholds));
        pw.println("  mThresholdLevels=" + Arrays.toString(mThresholdLevels));
    }
}
