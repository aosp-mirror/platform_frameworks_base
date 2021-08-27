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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Angle measurement
 *
 * <p>The actual angle is interpreted as:
 *   {@link #getRadians()} +/- {@link #getErrorRadians()} ()} at {@link #getConfidenceLevel()}
 *
 * @hide
 */
@SystemApi
public final class AngleMeasurement implements Parcelable {
    private final double mRadians;
    private final double mErrorRadians;
    private final double mConfidenceLevel;

    /**
     * Constructs a new {@link AngleMeasurement} object
     *
     * @param radians the angle in radians
     * @param errorRadians the error of the angle measurement in radians
     * @param confidenceLevel confidence level of the angle measurement
     *
     * @throws IllegalArgumentException if the radians, errorRadians, or confidenceLevel is out of
     *                                  allowed range
     */
    public AngleMeasurement(
            @FloatRange(from = -Math.PI, to = +Math.PI) double radians,
            @FloatRange(from = 0.0, to = +Math.PI) double errorRadians,
            @FloatRange(from = 0.0, to = 1.0) double confidenceLevel) {
        if (radians < -Math.PI || radians > Math.PI) {
            throw new IllegalArgumentException("Invalid radians: " + radians);
        }
        mRadians = radians;

        if (errorRadians < 0.0 || errorRadians > Math.PI) {
            throw new IllegalArgumentException("Invalid error radians: " + errorRadians);
        }
        mErrorRadians = errorRadians;

        if (confidenceLevel < 0.0 || confidenceLevel > 1.0) {
            throw new IllegalArgumentException("Invalid confidence level: " + confidenceLevel);
        }
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
     * @hide
    */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof AngleMeasurement) {
            AngleMeasurement other = (AngleMeasurement) obj;
            return mRadians == other.getRadians()
                    && mErrorRadians == other.getErrorRadians()
                    && mConfidenceLevel == other.getConfidenceLevel();
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mRadians, mErrorRadians, mConfidenceLevel);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mRadians);
        dest.writeDouble(mErrorRadians);
        dest.writeDouble(mConfidenceLevel);
    }

    public static final @android.annotation.NonNull Creator<AngleMeasurement> CREATOR =
            new Creator<AngleMeasurement>() {
                @Override
                public AngleMeasurement createFromParcel(Parcel in) {
                    return new AngleMeasurement(in.readDouble(), in.readDouble(), in.readDouble());
                }

                @Override
                public AngleMeasurement[] newArray(int size) {
                    return new AngleMeasurement[size];
                }
    };
}
