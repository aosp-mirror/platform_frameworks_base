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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A data point for the distance measurement
 *
 * <p>The actual distance is interpreted as:
 *   {@link #getMeters()} +/- {@link #getErrorMeters()} at {@link #getConfidenceLevel()}
 *
 * @hide
 */
public final class DistanceMeasurement implements Parcelable {
    private final double mMeters;
    private final double mErrorMeters;
    private final double mConfidenceLevel;

    private DistanceMeasurement(double meters, double errorMeters, double confidenceLevel) {
        mMeters = meters;
        mErrorMeters = errorMeters;
        mConfidenceLevel = confidenceLevel;
    }

    /**
     * Distance measurement in meters
     *
     * @return distance in meters
     */
    public double getMeters() {
        return mMeters;
    }

    /**
     * Error of distance measurement in meters
     * <p>Must be positive
     *
     * @return error of distance measurement in meters
     */
    public double getErrorMeters() {
        return mErrorMeters;
    }

    /**
     * Distance measurement confidence level expressed as a value between 0.0 to 1.0.
     *
     * <p>A value of 0.0 indicates no confidence in the measurement. A value of 1.0 represents
     * maximum confidence in the measurement
     *
     * @return confidence level
     */
    @FloatRange(from = 0.0, to = 1.0)
    public double getConfidenceLevel() {
        return mConfidenceLevel;
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof DistanceMeasurement) {
            DistanceMeasurement other = (DistanceMeasurement) obj;
            return mMeters == other.getMeters()
                    && mErrorMeters == other.getErrorMeters()
                    && mConfidenceLevel == other.getConfidenceLevel();
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mMeters, mErrorMeters, mConfidenceLevel);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mMeters);
        dest.writeDouble(mErrorMeters);
        dest.writeDouble(mConfidenceLevel);
    }

    public static final @android.annotation.NonNull Creator<DistanceMeasurement> CREATOR =
            new Creator<DistanceMeasurement>() {
                @Override
                public DistanceMeasurement createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    builder.setMeters(in.readDouble());
                    builder.setErrorMeters(in.readDouble());
                    builder.setConfidenceLevel(in.readDouble());
                    return builder.build();
                }

                @Override
                public DistanceMeasurement[] newArray(int size) {
                    return new DistanceMeasurement[size];
                }
    };

    /**
     * Builder to get a {@link DistanceMeasurement} object.
     */
    public static final class Builder {
        private double mMeters = Double.NaN;
        private double mErrorMeters = Double.NaN;
        private double mConfidenceLevel = Double.NaN;

        /**
         * Set the distance measurement in meters
         *
         * @param meters distance in meters
         * @throws IllegalArgumentException if meters is NaN
         */
        @NonNull
        public Builder setMeters(double meters) {
            if (Double.isNaN(meters)) {
                throw new IllegalArgumentException("meters cannot be NaN");
            }
            mMeters = meters;
            return this;
        }

        /**
         * Set the distance error in meters
         *
         * @param errorMeters distance error in meters
         * @throws IllegalArgumentException if error is negative or NaN
         */
        @NonNull
        public Builder setErrorMeters(double errorMeters) {
            if (Double.isNaN(errorMeters) || errorMeters < 0.0) {
                throw new IllegalArgumentException(
                        "errorMeters must be >= 0.0 and not NaN: " + errorMeters);
            }
            mErrorMeters = errorMeters;
            return this;
        }

        /**
         * Set the confidence level
         *
         * @param confidenceLevel the confidence level in the distance measurement
         * @throws IllegalArgumentException if confidence level is not in the range of [0.0, 1.0]
         */
        @NonNull
        public Builder setConfidenceLevel(double confidenceLevel) {
            if (confidenceLevel < 0.0 || confidenceLevel > 1.0) {
                throw new IllegalArgumentException(
                        "confidenceLevel must be in the range [0.0, 1.0]: " + confidenceLevel);
            }
            mConfidenceLevel = confidenceLevel;
            return this;
        }

        /**
         * Builds the {@link DistanceMeasurement} object
         *
         * @throws IllegalStateException if meters, error, or confidence are not set
         */
        @NonNull
        public DistanceMeasurement build() {
            if (Double.isNaN(mMeters)) {
                throw new IllegalStateException("Meters cannot be NaN");
            }

            if (Double.isNaN(mErrorMeters)) {
                throw new IllegalStateException("Error meters cannot be NaN");
            }

            if (Double.isNaN(mConfidenceLevel)) {
                throw new IllegalStateException("Confidence level cannot be NaN");
            }

            return new DistanceMeasurement(mMeters, mErrorMeters, mConfidenceLevel);
        }
    }
}
