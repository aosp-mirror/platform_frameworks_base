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
 * A helper class for handling access to illuminance hysteresis level values.
 */
final class HysteresisLevels {
    private static final String TAG = "HysteresisLevels";

    // Default hysteresis constraints for brightening or darkening.
    // The recent lux must have changed by at least this fraction relative to the
    // current ambient lux before a change will be considered.
    private static final float DEFAULT_BRIGHTENING_HYSTERESIS = 0.10f;
    private static final float DEFAULT_DARKENING_HYSTERESIS = 0.20f;

    private static final boolean DEBUG = false;

    private final float[] mBrightLevels;
    private final float[] mDarkLevels;
    private final float[] mLuxLevels;

  /**
   * Creates a {@code HysteresisLevels} object with the given equal-length
   * integer arrays.
   * @param brightLevels an array of brightening hysteresis constraint constants
   * @param darkLevels an array of darkening hysteresis constraint constants
   * @param luxLevels a monotonically increasing array of illuminance
   *                  thresholds in units of lux
   */
    public HysteresisLevels(int[] brightLevels, int[] darkLevels, int[] luxLevels) {
        if (brightLevels.length != darkLevels.length || darkLevels.length != luxLevels.length + 1) {
            throw new IllegalArgumentException("Mismatch between hysteresis array lengths.");
        }
        mBrightLevels = setArrayFormat(brightLevels, 1000.0f);
        mDarkLevels = setArrayFormat(darkLevels, 1000.0f);
        mLuxLevels = setArrayFormat(luxLevels, 1.0f);
    }

    /**
     * Return the brightening hysteresis threshold for the given lux level.
     */
    public float getBrighteningThreshold(float lux) {
        float brightConstant = getReferenceLevel(lux, mBrightLevels);
        float brightThreshold = lux * (1.0f + brightConstant);
        if (DEBUG) {
            Slog.d(TAG, "bright hysteresis constant=: " + brightConstant + ", threshold="
                + brightThreshold + ", lux=" + lux);
        }
        return brightThreshold;
    }

    /**
     * Return the darkening hysteresis threshold for the given lux level.
     */
    public float getDarkeningThreshold(float lux) {
        float darkConstant = getReferenceLevel(lux, mDarkLevels);
        float darkThreshold = lux * (1.0f - darkConstant);
        if (DEBUG) {
            Slog.d(TAG, "dark hysteresis constant=: " + darkConstant + ", threshold="
                + darkThreshold + ", lux=" + lux);
        }
        return darkThreshold;
    }

    /**
     * Return the hysteresis constant for the closest lux threshold value to the
     * current illuminance from the given array.
     */
    private float getReferenceLevel(float lux, float[] referenceLevels) {
        int index = 0;
        while (mLuxLevels.length > index && lux >= mLuxLevels[index]) {
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
}
