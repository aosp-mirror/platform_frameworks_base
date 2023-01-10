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

    private final float[] mBrighteningThresholdsPercentages;
    private final float[] mDarkeningThresholdsPercentages;
    private final float[] mBrighteningThresholdLevels;
    private final float[] mDarkeningThresholdLevels;
    private final float mMinDarkening;
    private final float mMinBrightening;

    /**
     * Creates a {@code HysteresisLevels} object with the given equal-length
     * float arrays.
     * @param brighteningThresholdsPercentages 0-100 of thresholds
     * @param darkeningThresholdsPercentages 0-100 of thresholds
     * @param brighteningThresholdLevels float array of brightness values in the relevant units
     * @param darkeningThresholdLevels float array of brightness values in the relevant units
     * @param minBrighteningThreshold the minimum value for which the brightening value needs to
     *                                return.
     * @param minDarkeningThreshold the minimum value for which the darkening value needs to return.
     */
    HysteresisLevels(float[] brighteningThresholdsPercentages,
            float[] darkeningThresholdsPercentages,
            float[] brighteningThresholdLevels, float[] darkeningThresholdLevels,
            float minDarkeningThreshold, float minBrighteningThreshold) {
        if (brighteningThresholdsPercentages.length != brighteningThresholdLevels.length
                || darkeningThresholdsPercentages.length != darkeningThresholdLevels.length) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrighteningThresholdsPercentages =
                setArrayFormat(brighteningThresholdsPercentages, 100.0f);
        mDarkeningThresholdsPercentages =
                setArrayFormat(darkeningThresholdsPercentages, 100.0f);
        mBrighteningThresholdLevels = setArrayFormat(brighteningThresholdLevels, 1.0f);
        mDarkeningThresholdLevels = setArrayFormat(darkeningThresholdLevels, 1.0f);
        mMinDarkening = minDarkeningThreshold;
        mMinBrightening = minBrighteningThreshold;
    }

    /**
     * Return the brightening hysteresis threshold for the given value level.
     */
    public float getBrighteningThreshold(float value) {
        final float brightConstant = getReferenceLevel(value,
                mBrighteningThresholdLevels, mBrighteningThresholdsPercentages);

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
        final float darkConstant = getReferenceLevel(value,
                mDarkeningThresholdLevels, mDarkeningThresholdsPercentages);
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
    private float getReferenceLevel(float value, float[] thresholdLevels,
            float[] thresholdPercentages) {
        if (thresholdLevels == null || thresholdLevels.length == 0 || value < thresholdLevels[0]) {
            return 0.0f;
        }
        int index = 0;
        while (index < thresholdLevels.length - 1 && value >= thresholdLevels[index + 1]) {
            index++;
        }
        return thresholdPercentages[index];
    }

    /**
     * Return a float array where each i-th element equals {@code configArray[i]/divideFactor}.
     */
    private float[] setArrayFormat(float[] configArray, float divideFactor) {
        float[] levelArray = new float[configArray.length];
        for (int index = 0; levelArray.length > index; ++index) {
            levelArray[index] = configArray[index] / divideFactor;
        }
        return levelArray;
    }


    void dump(PrintWriter pw) {
        pw.println("HysteresisLevels");
        pw.println("  mBrighteningThresholdLevels=" + Arrays.toString(mBrighteningThresholdLevels));
        pw.println("  mBrighteningThresholdsPercentages="
                + Arrays.toString(mBrighteningThresholdsPercentages));
        pw.println("  mMinBrightening=" + mMinBrightening);
        pw.println("  mDarkeningThresholdLevels=" + Arrays.toString(mDarkeningThresholdLevels));
        pw.println("  mDarkeningThresholdsPercentages="
                + Arrays.toString(mDarkeningThresholdsPercentages));
        pw.println("  mMinDarkening=" + mMinDarkening);
    }
}
