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

/**
 * A helper class for handling access to illuminance level values.
 */
final class LuxLevels {
    private static final String TAG = "LuxLevels";

    private static final boolean DEBUG = true;

    private final float[] mBrightLevels;
    private final float[] mDarkLevels;
    private final float[] mLuxHysteresisLevels;
    private final float[] mDozeBrightnessBacklightValues;
    private final float[] mDozeSensorLuxLevels;

  /**
   * Creates a {@code LuxLevels} object with the given integer arrays. The following arrays
   * are either empty or have the following relations:
   * {@code brightLevels} and {@code darkLevels} have the same length n.
   * {@code luxLevels} has length n+1.
   *
   * {@code dozeSensorLuxLevels} has length r.
   * {@code dozeBrightnessBacklightValues} has length r+1.
   *
   * @param brightLevels an array of brightening hysteresis constraint constants
   * @param darkLevels an array of darkening hysteresis constraint constants
   * @param luxHysteresisLevels a monotonically increasing array of illuminance thresholds in lux
   * @param dozeSensorLuxLevels a monotonically increasing array of ALS thresholds in lux
   * @param dozeBrightnessBacklightValues an array of screen brightness values for doze mode in lux
   */
    public LuxLevels(int[] brightLevels, int[] darkLevels, int[] luxHysteresisLevels,
            int[] dozeSensorLuxLevels, int[] dozeBrightnessBacklightValues) {
        if (brightLevels.length != darkLevels.length ||
            darkLevels.length !=luxHysteresisLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        if (dozeBrightnessBacklightValues.length > 0 && dozeSensorLuxLevels.length > 0
            && dozeBrightnessBacklightValues.length != dozeSensorLuxLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between doze lux array lengths.");
        }
        mBrightLevels = setArrayFormat(brightLevels, 1000.0f);
        mDarkLevels = setArrayFormat(darkLevels, 1000.0f);
        mLuxHysteresisLevels = setArrayFormat(luxHysteresisLevels, 1.0f);
        mDozeSensorLuxLevels = setArrayFormat(dozeSensorLuxLevels, 1.0f);
        mDozeBrightnessBacklightValues = setArrayFormat(dozeBrightnessBacklightValues, 1.0f);
    }

    /**
     * Return the brightening hysteresis threshold for the given lux level.
     */
    public float getBrighteningThreshold(float lux) {
        float brightConstant = getReferenceLevel(lux, mBrightLevels, mLuxHysteresisLevels);
        float brightThreshold = lux * (1.0f + brightConstant);
        if (DEBUG) {
            Slog.d(TAG, "bright hysteresis constant= " + brightConstant + ", threshold="
                + brightThreshold + ", lux=" + lux);
        }
        return brightThreshold;
    }

    /**
     * Return the darkening hysteresis threshold for the given lux level.
     */
    public float getDarkeningThreshold(float lux) {
        float darkConstant = getReferenceLevel(lux, mDarkLevels, mLuxHysteresisLevels);
        float darkThreshold = lux * (1.0f - darkConstant);
        if (DEBUG) {
            Slog.d(TAG, "dark hysteresis constant= " + darkConstant + ", threshold="
                + darkThreshold + ", lux=" + lux);
        }
        return darkThreshold;
    }

    /**
     * Return the doze backlight brightness level for the given ambient sensor lux level.
     */
    public int getDozeBrightness(float lux) {
        int dozeBrightness = (int) getReferenceLevel(lux, mDozeBrightnessBacklightValues,
            mDozeSensorLuxLevels);
        if (DEBUG) {
            Slog.d(TAG, "doze brightness: " + dozeBrightness + ", lux=" + lux);
        }
        return dozeBrightness;
    }

    /**
     * Find the index of the closest value in {@code thresholdLevels} to {@code lux} and return
     * the {@code referenceLevels} entry with that index.
     */
    private float getReferenceLevel(float lux, float[] referenceLevels, float[] thresholdLevels) {
        int index = 0;
        while (thresholdLevels.length > index && lux >= thresholdLevels[index]) {
            ++index;
        }
        return referenceLevels[index];
    }

    /**
     * Return if the doze backlight brightness level is specified dynamically.
     */
    public boolean hasDynamicDozeBrightness() {
        return mDozeSensorLuxLevels.length > 0;
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
}
