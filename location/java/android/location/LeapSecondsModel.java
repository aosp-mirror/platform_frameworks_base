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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Contains the leap seconds set of parameters needed for GNSS time.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class LeapSecondsModel implements Parcelable {
    /** Time difference due to leap seconds before the event in seconds. (UTC) */
    private final int mLeapSeconds;

    /** Time difference due to leap seconds after the event in seconds. (UTC) */
    private final int mLeapSecondsFuture;

    /** GNSS week number in which the leap second event will occur. (UTC) */
    private final int mWeekNumberLeapSecondsFuture;

    /** Day number when the next leap second will occur. */
    private final int mDayNumberLeapSecondsFuture;

    private LeapSecondsModel(Builder builder) {
        Preconditions.checkArgument(builder.mLeapSeconds >= 0);
        Preconditions.checkArgument(builder.mLeapSecondsFuture >= 0);
        Preconditions.checkArgument(builder.mWeekNumberLeapSecondsFuture >= 0);
        Preconditions.checkArgument(builder.mDayNumberLeapSecondsFuture >= 0);
        mLeapSeconds = builder.mLeapSeconds;
        mLeapSecondsFuture = builder.mLeapSecondsFuture;
        mWeekNumberLeapSecondsFuture = builder.mWeekNumberLeapSecondsFuture;
        mDayNumberLeapSecondsFuture = builder.mDayNumberLeapSecondsFuture;
    }

    /** Returns the time difference due to leap seconds before the event in seconds. (UTC) */
    @IntRange(from = 0)
    public int getLeapSeconds() {
        return mLeapSeconds;
    }

    /** Returns the time difference due to leap seconds after the event in seconds. (UTC) */
    @IntRange(from = 0)
    public int getLeapSecondsFuture() {
        return mLeapSecondsFuture;
    }

    /** Returns the GNSS week number in which the leap second event will occur. (UTC) */
    @IntRange(from = 0)
    public int getWeekNumberLeapSecondsFuture() {
        return mWeekNumberLeapSecondsFuture;
    }

    /** Returns the day number when the next leap second will occur. */
    @IntRange(from = 0)
    public int getDayNumberLeapSecondsFuture() {
        return mDayNumberLeapSecondsFuture;
    }

    public static final @NonNull Creator<LeapSecondsModel> CREATOR =
            new Creator<LeapSecondsModel>() {
                @Override
                @NonNull
                public LeapSecondsModel createFromParcel(Parcel in) {
                    final LeapSecondsModel.Builder leapSecondsModel = new Builder();
                    leapSecondsModel.setLeapSeconds(in.readInt());
                    leapSecondsModel.setLeapSecondsFuture(in.readInt());
                    leapSecondsModel.setWeekNumberLeapSecondsFuture(in.readInt());
                    leapSecondsModel.setDayNumberLeapSecondsFuture(in.readInt());
                    return leapSecondsModel.build();
                }

                @Override
                public LeapSecondsModel[] newArray(int size) {
                    return new LeapSecondsModel[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mLeapSeconds);
        parcel.writeInt(mLeapSecondsFuture);
        parcel.writeInt(mWeekNumberLeapSecondsFuture);
        parcel.writeInt(mDayNumberLeapSecondsFuture);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("LeapSecondsModel[");
        builder.append("leapSeconds = ").append(mLeapSeconds);
        builder.append(", leapSecondsFuture = ").append(mLeapSecondsFuture);
        builder.append(", weekNumberLeapSecondsFuture = ").append(mWeekNumberLeapSecondsFuture);
        builder.append(", dayNumberLeapSecondsFuture = ").append(mDayNumberLeapSecondsFuture);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link LeapSecondsModel} */
    public static final class Builder {
        private int mLeapSeconds;
        private int mLeapSecondsFuture;
        private int mWeekNumberLeapSecondsFuture;
        private int mDayNumberLeapSecondsFuture;

        /** Sets the time difference due to leap seconds before the event in seconds. (UTC) */
        @NonNull
        public Builder setLeapSeconds(@IntRange(from = 0) int leapSeconds) {
            mLeapSeconds = leapSeconds;
            return this;
        }

        /** Sets the time difference due to leap seconds after the event in seconds. (UTC) */
        @NonNull
        public Builder setLeapSecondsFuture(@IntRange(from = 0) int leapSecondsFuture) {
            mLeapSecondsFuture = leapSecondsFuture;
            return this;
        }

        /** Sets the GNSS week number in which the leap second event will occur. (UTC) */
        @NonNull
        public Builder setWeekNumberLeapSecondsFuture(
                @IntRange(from = 0) int weekNumberLeapSecondsFuture) {
            mWeekNumberLeapSecondsFuture = weekNumberLeapSecondsFuture;
            return this;
        }

        /** Sets the day number when the next leap second will occur. */
        @NonNull
        public Builder setDayNumberLeapSecondsFuture(
                @IntRange(from = 0) int dayNumberLeapSecondsFuture) {
            mDayNumberLeapSecondsFuture = dayNumberLeapSecondsFuture;
            return this;
        }

        /** Builds a {@link LeapSecondsModel} instance as specified by this builder. */
        @NonNull
        public LeapSecondsModel build() {
            return new LeapSecondsModel(this);
        }
    }
}
