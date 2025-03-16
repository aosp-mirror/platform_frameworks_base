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
 * A class contains time of ephemeris for GPS, Galileo, and QZSS.
 *
 * <p>For GPS, this is defined in IS-GPS-200, section 20.3.3.4.1.
 * <p>For Galileo, this is defined in Galileo-OS-SIS-ICD, section 5.1.2, 5.1.9.2.
 * <p>For QZSS, this is defined in IS-QZSS-200, section 4.1.2.4.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class SatelliteEphemerisTime implements Parcelable {
    /** The issue of ephemeris data. */
    private final int mIode;

    /** The satellite week number without rollover. */
    private final int mWeekNumber;

    /** The broadcast time of ephemeris in GNSS time of week in seconds. */
    private final int mToeSeconds;

    private SatelliteEphemerisTime(Builder builder) {
        Preconditions.checkArgumentInRange(builder.mIode, 0, 1023, "Iode");
        Preconditions.checkArgument(builder.mWeekNumber >= 0);
        Preconditions.checkArgumentInRange(builder.mToeSeconds, 0, 604799, "ToeSeconds");
        mIode = builder.mIode;
        mWeekNumber = builder.mWeekNumber;
        mToeSeconds = builder.mToeSeconds;
    }

    /** Returns the issue of ephemeris data. */
    @IntRange(from = 0, to = 1023)
    public int getIode() {
        return mIode;
    }

    /** Returns the satellite week number without rollover. */
    @IntRange(from = 0)
    public int getWeekNumber() {
        return mWeekNumber;
    }

    /** Returns the broadcast time of ephemeris in GNSS time of week in seconds. */
    @IntRange(from = 0, to = 604799)
    public int getToeSeconds() {
        return mToeSeconds;
    }

    public static final @NonNull Creator<SatelliteEphemerisTime> CREATOR =
            new Creator<SatelliteEphemerisTime>() {
                @Override
                public SatelliteEphemerisTime createFromParcel(Parcel in) {
                    final SatelliteEphemerisTime.Builder satelliteEphemerisTime =
                            new Builder()
                                    .setIode(in.readInt())
                                    .setWeekNumber(in.readInt())
                                    .setToeSeconds(in.readInt());
                    return satelliteEphemerisTime.build();
                }

                @Override
                public SatelliteEphemerisTime[] newArray(int size) {
                    return new SatelliteEphemerisTime[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mIode);
        parcel.writeInt(mWeekNumber);
        parcel.writeInt(mToeSeconds);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("SatelliteEphemerisTime[");
        builder.append("iode = ").append(mIode);
        builder.append(", weekNumber = ").append(mWeekNumber);
        builder.append(", toeSeconds = ").append(mToeSeconds);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link SatelliteEphemerisTime}. */
    public static final class Builder {
        private int mIode;
        private int mWeekNumber;
        private int mToeSeconds;

        /** Sets the issue of ephemeris data. */
        @NonNull
        public Builder setIode(@IntRange(from = 0, to = 1023) int iode) {
            mIode = iode;
            return this;
        }

        /** Sets the satellite week number without rollover. */
        @NonNull
        public Builder setWeekNumber(@IntRange(from = 0) int weekNumber) {
            mWeekNumber = weekNumber;
            return this;
        }

        /** Sets the broadcast time of ephemeris in GNSS time of week in seconds. */
        @NonNull
        public Builder setToeSeconds(@IntRange(from = 0, to = 604799) int toeSeconds) {
            mToeSeconds = toeSeconds;
            return this;
        }

        /** Builds a {@link SatelliteEphemerisTime} instance as specified by this builder. */
        @NonNull
        public SatelliteEphemerisTime build() {
            return new SatelliteEphemerisTime(this);
        }
    }
}
