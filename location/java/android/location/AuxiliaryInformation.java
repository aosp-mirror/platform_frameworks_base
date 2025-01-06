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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class contains parameters to provide additional assistance information dependent on the GNSS
 * constellation.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class AuxiliaryInformation implements Parcelable {

    /**
     * BDS B1C Satellite orbit type.
     *
     * <p>This is defined in BDS-SIS-ICD-B1I-3.0, section 3.1.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        BDS_B1C_ORBIT_TYPE_UNDEFINED,
        BDS_B1C_ORBIT_TYPE_GEO,
        BDS_B1C_ORBIT_TYPE_IGSO,
        BDS_B1C_ORBIT_TYPE_MEO
    })
    public @interface BeidouB1CSatelliteOrbitType {}

    /**
     * The following enumerations must be in sync with the values declared in
     * AuxiliaryInformation.aidl.
     */

    /** The orbit type is undefined. */
    public static final int BDS_B1C_ORBIT_TYPE_UNDEFINED = 0;

    /** The orbit type is GEO. */
    public static final int BDS_B1C_ORBIT_TYPE_GEO = 1;

    /** The orbit type is IGSO. */
    public static final int BDS_B1C_ORBIT_TYPE_IGSO = 2;

    /** The orbit type is MEO. */
    public static final int BDS_B1C_ORBIT_TYPE_MEO = 3;

    /**
     * Pseudo-random or satellite ID number for the satellite, a.k.a. Space Vehicle (SV), or OSN
     * number for Glonass.
     *
     * <p>The distinction is made by looking at the constellation field. Values must be in the range
     * of:
     *
     * <p>- GPS: 1-32
     *
     * <p>- GLONASS: 1-25
     *
     * <p>- QZSS: 183-206
     *
     * <p>- Galileo: 1-36
     *
     * <p>- Beidou: 1-63
     */
    private final int mSvid;

    /** The list of available signal types for the satellite. */
    @NonNull private final List<GnssSignalType> mAvailableSignalTypes;

    /**
     * Glonass carrier frequency number of the satellite. This is required for Glonass.
     *
     * <p>This is defined in Glonass ICD v5.1 section 3.3.1.1.
     */
    private final int mFrequencyChannelNumber;

    /** BDS B1C satellite orbit type. This is required for Beidou. */
    private final @BeidouB1CSatelliteOrbitType int mSatType;

    private AuxiliaryInformation(Builder builder) {
        // Allow Svid beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mSvid >= 1);
        Preconditions.checkNotNull(
                builder.mAvailableSignalTypes, "AvailableSignalTypes cannot be null");
        Preconditions.checkArgument(builder.mAvailableSignalTypes.size() > 0);
        Preconditions.checkArgumentInRange(
                builder.mFrequencyChannelNumber, -7, 6, "FrequencyChannelNumber");
        Preconditions.checkArgumentInRange(
                builder.mSatType, BDS_B1C_ORBIT_TYPE_UNDEFINED, BDS_B1C_ORBIT_TYPE_MEO, "SatType");
        mSvid = builder.mSvid;
        mAvailableSignalTypes =
                Collections.unmodifiableList(new ArrayList<>(builder.mAvailableSignalTypes));
        mFrequencyChannelNumber = builder.mFrequencyChannelNumber;
        mSatType = builder.mSatType;
    }

    /**
     * Returns the Pseudo-random or satellite ID number for the satellite, a.k.a. Space Vehicle
     * (SV), or OSN number for Glonass.
     *
     * <p>The distinction is made by looking at the constellation field. Values must be in the range
     * of:
     *
     * <p>- GPS: 1-32
     *
     * <p>- GLONASS: 1-25
     *
     * <p>- QZSS: 183-206
     *
     * <p>- Galileo: 1-36
     *
     * <p>- Beidou: 1-63
     */
    @IntRange(from = 1)
    public int getSvid() {
        return mSvid;
    }

    /** Returns the list of available signal types for the satellite. */
    @NonNull
    public List<GnssSignalType> getAvailableSignalTypes() {
        return mAvailableSignalTypes;
    }

    /** Returns the Glonass carrier frequency number of the satellite. */
    @IntRange(from = -7, to = 6)
    public int getFrequencyChannelNumber() {
        return mFrequencyChannelNumber;
    }

    /** Returns the BDS B1C satellite orbit type. */
    @BeidouB1CSatelliteOrbitType
    public int getSatType() {
        return mSatType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSvid);
        dest.writeTypedList(mAvailableSignalTypes);
        dest.writeInt(mFrequencyChannelNumber);
        dest.writeInt(mSatType);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("AuxiliaryInformation[");
        builder.append("svid = ").append(mSvid);
        builder.append(", availableSignalTypes = ").append(mAvailableSignalTypes);
        builder.append(", frequencyChannelNumber = ").append(mFrequencyChannelNumber);
        builder.append(", satType = ").append(mSatType);
        builder.append("]");
        return builder.toString();
    }

    public static final @NonNull Parcelable.Creator<AuxiliaryInformation> CREATOR =
            new Parcelable.Creator<AuxiliaryInformation>() {
                @Override
                public AuxiliaryInformation createFromParcel(@NonNull Parcel in) {
                    return new AuxiliaryInformation.Builder()
                            .setSvid(in.readInt())
                            .setAvailableSignalTypes(
                                    in.createTypedArrayList(GnssSignalType.CREATOR))
                            .setFrequencyChannelNumber(in.readInt())
                            .setSatType(in.readInt())
                            .build();
                }

                @Override
                public AuxiliaryInformation[] newArray(int size) {
                    return new AuxiliaryInformation[size];
                }
            };

    /** A builder class for {@link AuxiliaryInformation}. */
    public static final class Builder {
        private int mSvid;
        private List<GnssSignalType> mAvailableSignalTypes;
        private int mFrequencyChannelNumber;
        private @BeidouB1CSatelliteOrbitType int mSatType;

        /**
         * Sets the Pseudo-random or satellite ID number for the satellite, a.k.a. Space Vehicle
         * (SV), or OSN number for Glonass.
         *
         * <p>The distinction is made by looking at the constellation field. Values must be in the
         * range of:
         *
         * <p>- GPS: 1-32
         *
         * <p>- GLONASS: 1-25
         *
         * <p>- QZSS: 183-206
         *
         * <p>- Galileo: 1-36
         *
         * <p>- Beidou: 1-63
         */
        @NonNull
        public Builder setSvid(@IntRange(from = 1) int svid) {
            mSvid = svid;
            return this;
        }

        /**
         * Sets the list of available signal types for the satellite.
         *
         * <p>The list must be set and cannot be an empty list.
         */
        @NonNull
        public Builder setAvailableSignalTypes(@NonNull List<GnssSignalType> availableSignalTypes) {
            mAvailableSignalTypes = availableSignalTypes;
            return this;
        }

        /** Sets the Glonass carrier frequency number of the satellite. */
        @NonNull
        public Builder setFrequencyChannelNumber(
                @IntRange(from = -7, to = 6) int frequencyChannelNumber) {
            mFrequencyChannelNumber = frequencyChannelNumber;
            return this;
        }

        /** Sets the BDS B1C satellite orbit type. */
        @NonNull
        public Builder setSatType(@BeidouB1CSatelliteOrbitType int satType) {
            mSatType = satType;
            return this;
        }

        /** Builds a {@link AuxiliaryInformation} instance as specified by this builder. */
        @NonNull
        public AuxiliaryInformation build() {
            return new AuxiliaryInformation(this);
        }
    }
}
