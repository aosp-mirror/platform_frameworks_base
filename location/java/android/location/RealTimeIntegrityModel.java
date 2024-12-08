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
 * A class contains the real time integrity status of a GNSS satellite based on notice advisory.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class RealTimeIntegrityModel implements Parcelable {
    /**
     * Pseudo-random or satellite ID number for the satellite,
     * a.k.a. Space Vehicle (SV), or OSN number for Glonass.
     *
     * <p>The distinction is made by looking at the constellation field. Values
     * must be in the range of:
     *
     * <p> - GPS: 1-32
     * <p> - GLONASS: 1-25
     * <p> - QZSS: 183-206
     * <p> - Galileo: 1-36
     * <p> - Beidou: 1-63
     */
    private final int mSvid;

    /** Indicates whether the satellite is currently usable for navigation. */
    private final boolean mUsable;

    /** UTC timestamp (in seconds) when the advisory was published. */
    private final long mPublishDateSeconds;

    /** UTC timestamp (in seconds) for the start of the event. */
    private final long mStartDateSeconds;

    /** UTC timestamp (in seconds) for the end of the event. */
    private final long mEndDateSeconds;

    /**
     * Abbreviated type of the advisory, providing a concise summary of the event.
     *
     * <p>This field follows different definitions depending on the GNSS constellation:
     * <p> - GPS: See NANU type definitions(https://www.navcen.uscg.gov/nanu-abbreviations-and-descriptions)
     * <p> - Galileo: See NAGU type definitions(https://www.gsc-europa.eu/system-service-status/nagu-information)
     * <p> - QZSS: See NAQU type definitions](https://sys.qzss.go.jp/dod/en/naqu/type.html)
     * <p> - BeiDou: Not used; set to an empty string.
     */
    @NonNull private final String mAdvisoryType;

    /**
     *  Unique identifier for the advisory within its constellation's system.
     *
     *  <p>For BeiDou, this is not used and should be an empty string.
     */
    @NonNull private final String mAdvisoryNumber;

    private RealTimeIntegrityModel(Builder builder) {
        // Allow SV ID beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mSvid >= 1);
        Preconditions.checkArgument(builder.mPublishDateSeconds > 0);
        Preconditions.checkArgument(builder.mStartDateSeconds > 0);
        Preconditions.checkArgument(builder.mEndDateSeconds > 0);
        Preconditions.checkNotNull(builder.mAdvisoryType, "AdvisoryType cannot be null");
        Preconditions.checkNotNull(builder.mAdvisoryNumber, "AdvisoryNumber cannot be null");
        mSvid = builder.mSvid;
        mUsable = builder.mUsable;
        mPublishDateSeconds = builder.mPublishDateSeconds;
        mStartDateSeconds = builder.mStartDateSeconds;
        mEndDateSeconds = builder.mEndDateSeconds;
        mAdvisoryType = builder.mAdvisoryType;
        mAdvisoryNumber = builder.mAdvisoryNumber;
    }

    /**
     * Returns the Pseudo-random or satellite ID number for the satellite,
     * a.k.a. Space Vehicle (SV), or OSN number for Glonass.
     *
     * <p>The distinction is made by looking at the constellation field. Values
     * must be in the range of:
     *
     * <p> - GPS: 1-32
     * <p> - GLONASS: 1-25
     * <p> - QZSS: 183-206
     * <p> - Galileo: 1-36
     * <p> - Beidou: 1-63
     */
    @IntRange(from = 1, to = 206)
    public int getSvid() {
        return mSvid;
    }

    /** Returns whether the satellite is usable or not. */
    public boolean isUsable() {
        return mUsable;
    }

    /** Returns the UTC timestamp (in seconds) when the advisory was published */
    @IntRange(from = 0)
    public long getPublishDateSeconds() {
        return mPublishDateSeconds;
    }

    /** Returns UTC timestamp (in seconds) for the start of the event. */
    @IntRange(from = 0)
    public long getStartDateSeconds() {
        return mStartDateSeconds;
    }

    /** Returns UTC timestamp (in seconds) for the end of the event. */
    @IntRange(from = 0)
    public long getEndDateSeconds() {
        return mEndDateSeconds;
    }

    /** Returns the abbreviated type of notice advisory. */
    @NonNull
    public String getAdvisoryType() {
        return mAdvisoryType;
    }

    /** Returns the unique identifier for the advisory. */
    @NonNull
    public String getAdvisoryNumber() {
        return mAdvisoryNumber;
    }

    public static final @NonNull Creator<RealTimeIntegrityModel> CREATOR =
            new Creator<RealTimeIntegrityModel>() {
                @Override
                @NonNull
                public RealTimeIntegrityModel createFromParcel(Parcel in) {
                    RealTimeIntegrityModel realTimeIntegrityModel =
                            new RealTimeIntegrityModel.Builder()
                                    .setSvid(in.readInt())
                                    .setUsable(in.readBoolean())
                                    .setPublishDateSeconds(in.readLong())
                                    .setStartDateSeconds(in.readLong())
                                    .setEndDateSeconds(in.readLong())
                                    .setAdvisoryType(in.readString8())
                                    .setAdvisoryNumber(in.readString8())
                                    .build();
                    return realTimeIntegrityModel;
                }

                @Override
                public RealTimeIntegrityModel[] newArray(int size) {
                    return new RealTimeIntegrityModel[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mSvid);
        parcel.writeBoolean(mUsable);
        parcel.writeLong(mPublishDateSeconds);
        parcel.writeLong(mStartDateSeconds);
        parcel.writeLong(mEndDateSeconds);
        parcel.writeString8(mAdvisoryType);
        parcel.writeString8(mAdvisoryNumber);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("RealTimeIntegrityModel[");
        builder.append("svid = ").append(mSvid);
        builder.append(", usable = ").append(mUsable);
        builder.append(", publishDateSeconds = ").append(mPublishDateSeconds);
        builder.append(", startDateSeconds = ").append(mStartDateSeconds);
        builder.append(", endDateSeconds = ").append(mEndDateSeconds);
        builder.append(", advisoryType = ").append(mAdvisoryType);
        builder.append(", advisoryNumber = ").append(mAdvisoryNumber);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link RealTimeIntegrityModel} */
    public static final class Builder {
        private int mSvid;
        private boolean mUsable;
        private long mPublishDateSeconds;
        private long mStartDateSeconds;
        private long mEndDateSeconds;
        private String mAdvisoryType;
        private String mAdvisoryNumber;

        /**
         * Sets the Pseudo-random or satellite ID number for the satellite,
         * a.k.a. Space Vehicle (SV), or OSN number for Glonass.
         *
         * <p>The distinction is made by looking at the constellation field. Values
         * must be in the range of:
         *
         * <p> - GPS: 1-32
         * <p> - GLONASS: 1-25
         * <p> - QZSS: 183-206
         * <p> - Galileo: 1-36
         * <p> - Beidou: 1-63
         */
        @NonNull
        public Builder setSvid(@IntRange(from = 1, to = 206) int svid) {
            mSvid = svid;
            return this;
        }

        /** Sets whether the satellite is usable or not. */
        @NonNull
        public Builder setUsable(boolean usable) {
            mUsable = usable;
            return this;
        }

        /** Sets the UTC timestamp (in seconds) when the advisory was published. */
        @NonNull
        public Builder setPublishDateSeconds(@IntRange(from = 0) long publishDateSeconds) {
            mPublishDateSeconds = publishDateSeconds;
            return this;
        }

        /** Sets the UTC timestamp (in seconds) for the start of the event. */
        @NonNull
        public Builder setStartDateSeconds(@IntRange(from = 0) long startDateSeconds) {
            mStartDateSeconds = startDateSeconds;
            return this;
        }

        /** Sets the UTC timestamp (in seconds) for the end of the event. */
        @NonNull
        public Builder setEndDateSeconds(@IntRange(from = 0) long endDateSeconds) {
            mEndDateSeconds = endDateSeconds;
            return this;
        }

        /** Sets the abbreviated type of notice advisory. */
        @NonNull
        public Builder setAdvisoryType(@NonNull String advisoryType) {
            mAdvisoryType = advisoryType;
            return this;
        }

        /** Sets the unique identifier for the advisory. */
        @NonNull
        public Builder setAdvisoryNumber(@NonNull String advisoryNumber) {
            mAdvisoryNumber = advisoryNumber;
            return this;
        }

        /** Builds a {@link RealTimeIntegrityModel} instance as specified by this builder. */
        @NonNull
        public RealTimeIntegrityModel build() {
            return new RealTimeIntegrityModel(this);
        }
    }
}
