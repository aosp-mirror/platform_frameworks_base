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
import android.location.GnssStatus.ConstellationType;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A class contains the GNSS-GNSS system time offset between the GNSS system time.
 *
 * <p>This is defined in IS-GPS-200 section 30.3.3.8.2.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class TimeModel implements Parcelable {
    /*
     * Model represents parameters to convert from current GNSS to GNSS system
     * time indicated by toGnss.
     */
    private final @ConstellationType int mToGnss;

    /** Bias coefficient of GNSS time scale relative to GNSS time scale in seconds. */
    private final double mA0;

    /** Drift coefficient of GNSS time scale relative to GNSS time scale in seconds per second. */
    private final double mA1;

    /** GNSS time of week in seconds. */
    private final int mTimeOfWeek;

    /** Week number of the GNSS time. */
    private final int mWeekNumber;

    private TimeModel(Builder builder) {
        Preconditions.checkArgumentInRange(
                builder.mToGnss,
                GnssStatus.CONSTELLATION_UNKNOWN,
                GnssStatus.CONSTELLATION_COUNT,
                "ToGnss");
        Preconditions.checkArgumentInRange(builder.mA0, -1.0f, 1.0f, "A0");
        Preconditions.checkArgumentInRange(builder.mA1, -3.28e-6f, 3.28e-6f, "A1");
        Preconditions.checkArgumentInRange(builder.mTimeOfWeek, 0, 604800, "TimeOfWeek");
        Preconditions.checkArgument(builder.mWeekNumber >= 0);
        mToGnss = builder.mToGnss;
        mA0 = builder.mA0;
        mA1 = builder.mA1;
        mTimeOfWeek = builder.mTimeOfWeek;
        mWeekNumber = builder.mWeekNumber;
    }

    /** Returns the constellation type to convert from current GNSS system time. */
    @ConstellationType
    public int getToGnss() {
        return mToGnss;
    }

    /** Returns the bias coefficient of GNSS time scale relative to GNSS time scale in seconds. */
    @FloatRange(from = -1.0f, to = 1.0f)
    public double getA0() {
        return mA0;
    }

    /**
     * Returns the drift coefficient of GNSS time scale relative to GNSS time scale in seconds per
     * second.
     */
    @FloatRange(from = -3.28e-6f, to = 3.28e-6f)
    public double getA1() {
        return mA1;
    }

    /** Returns the GNSS time of week in seconds. */
    @IntRange(from = 0, to = 604800)
    public int getTimeOfWeek() {
        return mTimeOfWeek;
    }

    /** Returns the week number of the GNSS time. */
    @IntRange(from = 0)
    public int getWeekNumber() {
        return mWeekNumber;
    }

    public static final @NonNull Creator<TimeModel> CREATOR =
            new Creator<TimeModel>() {
                @Override
                public TimeModel createFromParcel(@NonNull Parcel source) {
                    return new TimeModel.Builder()
                            .setToGnss(source.readInt())
                            .setA0(source.readDouble())
                            .setA1(source.readDouble())
                            .setTimeOfWeek(source.readInt())
                            .setWeekNumber(source.readInt())
                            .build();
                }

                @Override
                public TimeModel[] newArray(int size) {
                    return new TimeModel[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("TimeModel[");
        builder.append("toGnss = ").append(mToGnss);
        builder.append(", a0 = ").append(mA0);
        builder.append(", a1 = ").append(mA1);
        builder.append(", timeOfWeek = ").append(mTimeOfWeek);
        builder.append(", weekNumber = ").append(mWeekNumber);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mToGnss);
        dest.writeDouble(mA0);
        dest.writeDouble(mA1);
        dest.writeInt(mTimeOfWeek);
        dest.writeInt(mWeekNumber);
    }

    /** Builder for {@link TimeModel} */
    public static final class Builder {

        private @ConstellationType int mToGnss;
        private double mA0;
        private double mA1;
        private int mTimeOfWeek;
        private int mWeekNumber;

        /** Sets the constellation type to convert from current GNSS system time. */
        @NonNull
        public Builder setToGnss(@ConstellationType int toGnss) {
            mToGnss = toGnss;
            return this;
        }

        /** Sets the bias coefficient of GNSS time scale relative to GNSS time scale in seconds. */
        @NonNull
        public Builder setA0(@FloatRange(from = -1.0f, to = 1.0f) double a0) {
            mA0 = a0;
            return this;
        }

        /**
         * Sets the drift coefficient of GNSS time scale relative to GNSS time scale in seconds per
         * second.
         */
        @NonNull
        public Builder setA1(@FloatRange(from = -3.28e-6f, to = 3.28e-6f) double a1) {
            mA1 = a1;
            return this;
        }

        /** Sets the GNSS time of week in seconds. */
        @NonNull
        public Builder setTimeOfWeek(@IntRange(from = 0, to = 604800) int timeOfWeek) {
            mTimeOfWeek = timeOfWeek;
            return this;
        }

        /** Sets the week number of the GNSS time. */
        @NonNull
        public Builder setWeekNumber(@IntRange(from = 0) int weekNumber) {
            mWeekNumber = weekNumber;
            return this;
        }

        /** Builds the {@link TimeModel} object. */
        @NonNull
        public TimeModel build() {
            return new TimeModel(this);
        }
    }
}
