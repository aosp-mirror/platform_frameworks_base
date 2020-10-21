/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.FloatRange;

/**
 * Angle measurement
 *
 * <p>The actual angle is interpreted as:
 *   {@link #getRadians()} +/- {@link #getErrorRadians()} ()} at {@link #getConfidenceLevel()}
 *
 * @hide
 */
public final class AngleMeasurement {
    private final double mRadians;
    private final double mErrorRadians;
    private final double mConfidenceLevel;

    private AngleMeasurement(double radians, double errorRadians, double confidenceLevel) {
        mRadians = radians;
        mErrorRadians = errorRadians;
        mConfidenceLevel = confidenceLevel;
    }

    /**
     * Angle measurement in radians
    *
     * @return angle in radians
     */
    @FloatRange(from = -Math.PI, to = +Math.PI)
    public double getRadians() {
        return mRadians;
    }

    /**
     * Error of angle measurement in radians
     *
     * <p>Must be a positive value
     *
     * @return angle measurement error in radians
     */
    @FloatRange(from = 0.0, to = +Math.PI)
    public double getErrorRadians() {
        return mErrorRadians;
    }

    /**
     * Angle measurement confidence level expressed as a value between
     * 0.0 to 1.0.
     *
     * <p>A value of 0.0 indicates there is no confidence in the measurement. A value of 1.0
     * indicates there is maximum confidence in the measurement.
     *
     * @return the confidence level of the angle measurement
     */
    @FloatRange(from = 0.0, to = 1.0)
    public double getConfidenceLevel() {
        return mConfidenceLevel;
    }

    /**
     * Builder class for {@link AngleMeasurement}.
     */
    public static final class Builder {
        private double mRadians = Double.NaN;
        private double mErrorRadians = Double.NaN;
        private double mConfidenceLevel = Double.NaN;

        /**
         * Set the angle in radians
         *
         * @param radians angle in radians
         * @throws IllegalArgumentException if angle exceeds allowed limits of [-Math.PI, +Math.PI]
         */
        public Builder setRadians(double radians) {
            if (radians < -Math.PI || radians > Math.PI) {
                throw new IllegalArgumentException("Invalid radians: " + radians);
            }
            mRadians = radians;
            return this;
        }

        /**
         * Set the angle error in radians
         *
         * @param errorRadians error of the angle in radians
         * @throws IllegalArgumentException if the error exceeds the allowed limits of [0, +Math.PI]
         */
        public Builder setErrorRadians(double errorRadians) {
            if (errorRadians < 0.0 || errorRadians > Math.PI) {
                throw new IllegalArgumentException(
                        "Invalid error radians: " + errorRadians);
            }
            mErrorRadians = errorRadians;
            return this;
        }

        /**
         * Set the angle confidence level
         *
         * @param confidenceLevel level of confidence of the angle measurement
         * @throws IllegalArgumentException if the error exceeds the allowed limits of [0.0, 1.0]
         */
        public Builder setConfidenceLevel(double confidenceLevel) {
            if (confidenceLevel < 0.0 || confidenceLevel > 1.0) {
                throw new IllegalArgumentException(
                        "Invalid confidence level: " + confidenceLevel);
            }
            mConfidenceLevel = confidenceLevel;
            return this;
        }

        /**
         * Build the {@link AngleMeasurement} object
         *
         * @throws IllegalStateException if angle, error, or confidence values are missing
         */
        public AngleMeasurement build() {
            if (Double.isNaN(mRadians)) {
                throw new IllegalStateException("Angle is not set");
            }

            if (Double.isNaN(mErrorRadians)) {
                throw new IllegalStateException("Angle error is not set");
            }

            if (Double.isNaN(mConfidenceLevel)) {
                throw new IllegalStateException("Angle confidence level is not set");
            }

            return new AngleMeasurement(mRadians, mErrorRadians, mConfidenceLevel);
        }
    }
}
