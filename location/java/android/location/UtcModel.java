/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A class contains parameters to convert from current GNSS time to UTC time.
 *
 * <p>This is defined in RINEX 3.05 "TIME SYSTEM CORR" in table A5.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class UtcModel implements Parcelable {
    /** Bias coefficient of GNSS time scale relative to UTC time scale in seconds. */
    private final double mA0;

    /** Drift coefficient of GNSS time scale relative to UTC time scale in seconds per second. */
    private final double mA1;

    /** Reference GNSS time of week in seconds. */
    private final int mTimeOfWeek;

    /** Reference GNSS week number. */
    private final int mWeekNumber;

    private UtcModel(Builder builder) {
        Preconditions.checkArgumentInRange(builder.mA0, -2.0f, 2.0f, "A0");
        Preconditions.checkArgumentInRange(builder.mA1, -7.45e-9f, 7.45e-9f, "A1");
        Preconditions.checkArgumentInRange(builder.mTimeOfWeek, 0, 604800, "TimeOfWeek");
        Preconditions.checkArgument(builder.mWeekNumber >= 0);
        mA0 = builder.mA0;
        mA1 = builder.mA1;
        mTimeOfWeek = builder.mTimeOfWeek;
        mWeekNumber = builder.mWeekNumber;
    }

    /** Returns the bias coefficient of GNSS time scale relative to UTC time scale in seconds. */
    @FloatRange(from = -2.0f, to = 2.0f)
    public double getA0() {
        return mA0;
    }

    /**
     * Returns the drift coefficient of GNSS time scale relative to UTC time scale in seconds per
     * second.
     */
    @FloatRange(from = -7.45e-9f, to = 7.45e-9f)
    public double getA1() {
        return mA1;
    }

    /** Returns the reference GNSS time of week in seconds. */
    @IntRange(from = 0, to = 604800)
    public int getTimeOfWeek() {
        return mTimeOfWeek;
    }

    /** Returns the reference GNSS week number. */
    @IntRange(from = 0)
    public int getWeekNumber() {
        return mWeekNumber;
    }

    @Override
    public int describeContents() {
        return 0;
    }
    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("UtcModel[");
        builder.append("a0 = ").append(mA0);
        builder.append(", a1 = ").append(mA1);
        builder.append(", timeOfWeek = ").append(mTimeOfWeek);
        builder.append(", weekNumber = ").append(mWeekNumber);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mA0);
        dest.writeDouble(mA1);
        dest.writeInt(mTimeOfWeek);
        dest.writeInt(mWeekNumber);
    }

    public static final @NonNull Creator<UtcModel> CREATOR =
            new Creator<UtcModel>() {
                @Override
                public UtcModel createFromParcel(@NonNull Parcel source) {
                    return new UtcModel.Builder()
                            .setA0(source.readDouble())
                            .setA1(source.readDouble())
                            .setTimeOfWeek(source.readInt())
                            .setWeekNumber(source.readInt())
                            .build();
                }

                @Override
                public UtcModel[] newArray(int size) {
                    return new UtcModel[size];
                }
            };

    /** Builder for {@link UtcModel}. */
    public static final class Builder {
        private double mA0;
        private double mA1;
        private int mTimeOfWeek;
        private int mWeekNumber;

        /** Sets the bias coefficient of GNSS time scale relative to UTC time scale in seconds. */
        @NonNull
        public Builder setA0(@FloatRange(from = -2.0f, to = 2.0f) double a0) {
            mA0 = a0;
            return this;
        }

        /**
         * Sets the drift coefficient of GNSS time scale relative to UTC time scale in seconds per
         * second.
         */
        @NonNull
        public Builder setA1(@FloatRange(from = -7.45e-9f, to = 7.45e-9f) double a1) {
            mA1 = a1;
            return this;
        }

        /** Sets the reference GNSS time of week in seconds. */
        @NonNull
        public Builder setTimeOfWeek(@IntRange(from = 0, to = 604800) int timeOfWeek) {
            mTimeOfWeek = timeOfWeek;
            return this;
        }

        /** Sets the reference GNSS week number. */
        @NonNull
        public Builder setWeekNumber(@IntRange(from = 0) int weekNumber) {
            mWeekNumber = weekNumber;
            return this;
        }

        /** Builds a {@link UtcModel} instance as specified by this builder. */
        @NonNull
        public UtcModel build() {
            return new UtcModel(this);
        }
    }
}
