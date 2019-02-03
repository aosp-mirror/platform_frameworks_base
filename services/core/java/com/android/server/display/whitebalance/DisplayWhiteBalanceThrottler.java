/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.whitebalance;

import android.util.Slog;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * The DisplayWhiteBalanceController uses the DisplayWhiteBalanceThrottler to decide whether the
 * screen color temperature should be updated, suppressing changes that are too frequent or too
 * minor.
 */
class DisplayWhiteBalanceThrottler {

    protected static final String TAG = "DisplayWhiteBalanceThrottler";
    protected boolean mLoggingEnabled;

    private int mIncreaseDebounce;  // Milliseconds
    private int mDecreaseDebounce;  // Milliseconds
    private long mLastTime;         // Milliseconds

    private float[] mBaseThresholds;
    private float[] mIncreaseThresholds;
    private float[] mDecreaseThresholds;
    private float mIncreaseThreshold;
    private float mDecreaseThreshold;
    private float mLastValue;

    /**
     * @param increaseDebounce
     *      The debounce time for increasing (throttled if {@code time < lastTime + debounce}).
     * @param decreaseDebounce
     *      The debounce time for decreasing (throttled if {@code time < lastTime + debounce}).
     * @param baseThresholds
     *      The ambient color temperature values used to determine the threshold as the
     *      corresponding value in increaseThresholds/decreaseThresholds.
     * @param increaseThresholds
     *      The increase threshold values (throttled if {@code value < value * (1 + threshold)}).
     * @param decreaseThresholds
     *      The decrease threshold values (throttled if {@code value > value * (1 - threshold)}).
     *
     * @throws IllegalArgumentException
     *      - increaseDebounce is negative;
     *      - decreaseDebounce is negative;
     *      - baseThresholds to increaseThresholds is not a valid mapping*;
     *      - baseThresholds to decreaseThresholds is not a valid mapping*;
     *
     *      (*) The x to y mapping is valid if:
     *          - x and y are not null;
     *          - x and y are not empty;
     *          - x and y contain only non-negative numbers;
     *          - x is strictly increasing.
     */
    DisplayWhiteBalanceThrottler(int increaseDebounce, int decreaseDebounce,
            float[] baseThresholds, float[] increaseThresholds, float[] decreaseThresholds) {
        validateArguments(increaseDebounce, decreaseDebounce, baseThresholds, increaseThresholds,
                decreaseThresholds);
        mLoggingEnabled = false;
        mIncreaseDebounce = increaseDebounce;
        mDecreaseDebounce = decreaseDebounce;
        mBaseThresholds = baseThresholds;
        mIncreaseThresholds = increaseThresholds;
        mDecreaseThresholds = decreaseThresholds;
        clear();
    }

    /**
     * Check whether the ambient color temperature should be throttled.
     *
     * @param value
     *      The ambient color temperature value.
     *
     * @return Whether the ambient color temperature should be throttled.
     */
    public boolean throttle(float value) {
        if (mLastTime != -1 && (tooSoon(value) || tooClose(value))) {
            return true;
        }
        computeThresholds(value);
        mLastTime = System.currentTimeMillis();
        mLastValue = value;
        return false;
    }

    /**
     * Clears the throttler state.
     */
    public void clear() {
        mLastTime = -1;
        mIncreaseThreshold = -1.0f;
        mDecreaseThreshold = -1.0f;
        mLastValue = -1.0f;
    }

    /**
     * Enable/disable logging.
     *
     * @param loggingEnabled
     *      Whether logging is on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return false;
        }
        mLoggingEnabled = loggingEnabled;
        return true;
    }

    /**
     * Dump the state.
     *
     * @param writer
     *      The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println("  DisplayWhiteBalanceThrottler");
        writer.println("    mLoggingEnabled=" + mLoggingEnabled);
        writer.println("    mIncreaseDebounce=" + mIncreaseDebounce);
        writer.println("    mDecreaseDebounce=" + mDecreaseDebounce);
        writer.println("    mLastTime=" + mLastTime);
        writer.println("    mBaseThresholds=" + Arrays.toString(mBaseThresholds));
        writer.println("    mIncreaseThresholds=" + Arrays.toString(mIncreaseThresholds));
        writer.println("    mDecreaseThresholds=" + Arrays.toString(mDecreaseThresholds));
        writer.println("    mIncreaseThreshold=" + mIncreaseThreshold);
        writer.println("    mDecreaseThreshold=" + mDecreaseThreshold);
        writer.println("    mLastValue=" + mLastValue);
    }

    private void validateArguments(float increaseDebounce, float decreaseDebounce,
            float[] baseThresholds, float[] increaseThresholds, float[] decreaseThresholds) {
        if (Float.isNaN(increaseDebounce) || increaseDebounce < 0.0f) {
            throw new IllegalArgumentException("increaseDebounce must be a non-negative number.");
        }
        if (Float.isNaN(decreaseDebounce) || decreaseDebounce < 0.0f) {
            throw new IllegalArgumentException("decreaseDebounce must be a non-negative number.");
        }
        if (!isValidMapping(baseThresholds, increaseThresholds)) {
            throw new IllegalArgumentException(
                    "baseThresholds to increaseThresholds is not a valid mapping.");
        }
        if (!isValidMapping(baseThresholds, decreaseThresholds)) {
            throw new IllegalArgumentException(
                    "baseThresholds to decreaseThresholds is not a valid mapping.");
        }
    }

    private static boolean isValidMapping(float[] x, float[] y) {
        if (x == null || y == null || x.length == 0 || y.length == 0 || x.length != y.length) {
            return false;
        }
        float prevX = -1.0f;
        for (int i = 0; i < x.length; i++) {
            if (Float.isNaN(x[i]) || Float.isNaN(y[i]) || x[i] < 0 || prevX >= x[i]) {
                return false;
            }
            prevX = x[i];
        }
        return true;
    }

    private boolean tooSoon(float value) {
        final long time = System.currentTimeMillis();
        final long earliestTime;
        if (value > mLastValue) {
            earliestTime = mLastTime + mIncreaseDebounce;
        } else { // value <= mLastValue
            earliestTime = mLastTime + mDecreaseDebounce;
        }
        final boolean tooSoon = time < earliestTime;
        if (mLoggingEnabled) {
            Slog.d(TAG, (tooSoon ? "too soon: " : "late enough: ") + time
                    + (tooSoon ? " < " : " > ") + earliestTime);
        }
        return tooSoon;
    }

    private boolean tooClose(float value) {
        final float threshold;
        final boolean tooClose;
        if (value > mLastValue) {
            threshold = mIncreaseThreshold;
            tooClose = value < threshold;
        } else { // value <= mLastValue
            threshold = mDecreaseThreshold;
            tooClose = value > threshold;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, (tooClose ? "too close: " : "far enough: ") + value
                    + (value > threshold ? " > " : " < ") + threshold);
        }
        return tooClose;
    }

    private void computeThresholds(float value) {
        final int index = getHighestIndexBefore(value, mBaseThresholds);
        mIncreaseThreshold = value * (1.0f + mIncreaseThresholds[index]);
        mDecreaseThreshold = value * (1.0f - mDecreaseThresholds[index]);
    }

    private int getHighestIndexBefore(float value, float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= value) {
                return i;
            }
        }
        return values.length - 1;
    }

}
