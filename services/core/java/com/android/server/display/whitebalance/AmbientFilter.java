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

import com.android.server.display.utils.RollingBuffer;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * The DisplayWhiteBalanceController uses the AmbientFilter to average ambient changes over time,
 * filter out the noise, and arrive at an estimate of the actual value.
 *
 * When the DisplayWhiteBalanceController detects a change in ambient brightness or color
 * temperature, it passes it to the AmbientFilter, and when it needs the actual ambient value, it
 * asks it for an estimate.
 *
 * Implementations:
 * - {@link WeightedMovingAverageAmbientFilter}
 *   A weighted average prioritising recent changes.
 */
abstract class AmbientFilter {

    protected static final boolean DEBUG = false; // Enable for verbose logs.

    protected final String mTag;
    protected boolean mLoggingEnabled;

    // How long ambient value changes are kept and taken into consideration.
    private final int mHorizon; // Milliseconds

    private final RollingBuffer mBuffer;

    /**
     * @param tag
     *      The tag used for dumping and logging.
     * @param horizon
     *      How long ambient value changes are kept and taken into consideration.
     *
     * @throws IllegalArgumentException
     *      - horizon is not positive.
     */
    AmbientFilter(String tag, int horizon) {
        validateArguments(horizon);
        mTag = tag;
        mLoggingEnabled = false;
        mHorizon = horizon;
        mBuffer = new RollingBuffer();
    }

    /**
     * Add an ambient value change.
     *
     * @param time
     *      The time.
     * @param value
     *      The ambient value.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean addValue(long time, float value) {
        if (value < 0.0f) {
            return false;
        }
        truncateOldValues(time);
        if (mLoggingEnabled) {
            Slog.d(mTag, "add value: " + value + " @ " + time);
        }
        mBuffer.add(time, value);
        return true;
    }

    /**
     * Get an estimate of the actual ambient color temperature.
     *
     * @param time
     *      The time.
     *
     * @return An estimate of the actual ambient color temperature.
     */
    public float getEstimate(long time) {
        truncateOldValues(time);
        final float value = filter(time, mBuffer);
        if (mLoggingEnabled) {
            Slog.d(mTag, "get estimate: " + value + " @ " + time);
        }
        return value;
    }

    /**
     * Clears the filter state.
     */
    public void clear() {
        mBuffer.clear();
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
        writer.println("  " + mTag);
        writer.println("    mLoggingEnabled=" + mLoggingEnabled);
        writer.println("    mHorizon=" + mHorizon);
        writer.println("    mBuffer=" + mBuffer);
    }

    private void validateArguments(int horizon) {
        if (horizon <= 0) {
            throw new IllegalArgumentException("horizon must be positive");
        }
    }

    private void truncateOldValues(long time) {
        final long minTime = time - mHorizon;
        mBuffer.truncate(minTime);
    }

    protected abstract float filter(long time, RollingBuffer buffer);

    /**
     * A weighted average prioritising recent changes.
     */
    static class WeightedMovingAverageAmbientFilter extends AmbientFilter {

        // How long the latest ambient value change is predicted to last.
        private static final int PREDICTION_TIME = 100; // Milliseconds

        // Recent changes are prioritised by integrating their duration over y = x + mIntercept
        // (the higher it is, the less prioritised recent changes are).
        private final float mIntercept;

        /**
         * @param tag
         *      The tag used for dumping and logging.
         * @param horizon
         *      How long ambient value changes are kept and taken into consideration.
         * @param intercept
         *      Recent changes are prioritised by integrating their duration over y = x + intercept
         *      (the higher it is, the less prioritised recent changes are).
         *
         * @throws IllegalArgumentException
         *      - horizon is not positive.
         *      - intercept is NaN or negative.
         */
        WeightedMovingAverageAmbientFilter(String tag, int horizon, float intercept) {
            super(tag, horizon);
            validateArguments(intercept);
            mIntercept = intercept;
        }

        /**
         * See {@link AmbientFilter#dump base class}.
         */
        @Override
        public void dump(PrintWriter writer) {
            super.dump(writer);
            writer.println("    mIntercept=" + mIntercept);
        }

        // Normalise the times to [t1=0, t2, ..., tN, now + PREDICTION_TIME], so the first change
        // starts at 0 and the last change is predicted to last a bit, and divide them by 1000 as
        // milliseconds are high enough to overflow.
        // The weight of the value from t[i] to t[i+1] is the area under (A.K.A. the integral of)
        // y = x + mIntercept from t[i] to t[i+1].
        @Override
        protected float filter(long time, RollingBuffer buffer) {
            if (buffer.isEmpty()) {
                return -1.0f;
            }
            float total = 0.0f;
            float totalWeight = 0.0f;
            final float[] weights = getWeights(time, buffer);
            if (DEBUG && mLoggingEnabled) {
                Slog.v(mTag, "filter: " + buffer + " => " + Arrays.toString(weights));
            }
            for (int i = 0; i < weights.length; i++) {
                final float value = buffer.getValue(i);
                final float weight = weights[i];
                total += weight * value;
                totalWeight += weight;
            }
            if (totalWeight == 0.0f) {
                return buffer.getValue(buffer.size() - 1);
            }
            return total / totalWeight;
        }

        private void validateArguments(float intercept) {
            if (Float.isNaN(intercept) || intercept < 0.0f) {
                throw new IllegalArgumentException("intercept must be a non-negative number");
            }
        }

        private float[] getWeights(long time, RollingBuffer buffer) {
            float[] weights = new float[buffer.size()];
            final long startTime = buffer.getTime(0);
            float previousTime = 0.0f;
            for (int i = 1; i < weights.length; i++) {
                final float currentTime = (buffer.getTime(i) - startTime) / 1000.0f;
                final float weight = calculateIntegral(previousTime, currentTime);
                weights[i - 1] = weight;
                previousTime = currentTime;
            }
            final float lastTime = (time + PREDICTION_TIME - startTime) / 1000.0f;
            final float lastWeight = calculateIntegral(previousTime, lastTime);
            weights[weights.length - 1] = lastWeight;
            return weights;
        }

        private float calculateIntegral(float from, float to) {
            return antiderivative(to) - antiderivative(from);
        }

        private float antiderivative(float x) {
            // f(x) = x + c => F(x) = 1/2 * x^2 + c * x
            return 0.5f * x * x + mIntercept * x;
        }

    }

}
