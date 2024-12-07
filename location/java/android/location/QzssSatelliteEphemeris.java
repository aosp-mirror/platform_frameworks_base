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
import android.location.GpsSatelliteEphemeris.GpsL2Params;
import android.location.GpsSatelliteEphemeris.GpsSatelliteClockModel;
import android.location.GpsSatelliteEphemeris.GpsSatelliteHealth;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A class contains ephemeris parameters specific to QZSS satellites.
 *
 * <p>This is defined in IS-QZSS-PNT section 4.1.2.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class QzssSatelliteEphemeris implements Parcelable {
    /** Satellite PRN. */
    private final int mPrn;

    /** L2 parameters. */
    @NonNull private final GpsL2Params mGpsL2Params;

    /** Clock model. */
    @NonNull private final GpsSatelliteClockModel mSatelliteClockModel;

    /** Orbit model. */
    @NonNull private final KeplerianOrbitModel mSatelliteOrbitModel;

    /** Satellite health. */
    @NonNull private final GpsSatelliteHealth mSatelliteHealth;

    /** Ephemeris time. */
    @NonNull private final SatelliteEphemerisTime mSatelliteEphemerisTime;

    /** Returns the PRN of the satellite. */
    @IntRange(from = 183, to = 206)
    public int getPrn() {
        return mPrn;
    }

    /** Returns the L2 parameters of the satellite. */
    @NonNull
    public GpsL2Params getGpsL2Params() {
        return mGpsL2Params;
    }

    /** Returns the clock model of the satellite. */
    @NonNull
    public GpsSatelliteClockModel getSatelliteClockModel() {
        return mSatelliteClockModel;
    }

    /** Returns the orbit model of the satellite. */
    @NonNull
    public KeplerianOrbitModel getSatelliteOrbitModel() {
        return mSatelliteOrbitModel;
    }

    /** Returns the satellite health. */
    @NonNull
    public GpsSatelliteHealth getSatelliteHealth() {
        return mSatelliteHealth;
    }

    /** Returns the ephemeris time. */
    @NonNull
    public SatelliteEphemerisTime getSatelliteEphemerisTime() {
        return mSatelliteEphemerisTime;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mPrn);
        parcel.writeTypedObject(mGpsL2Params, flags);
        parcel.writeTypedObject(mSatelliteClockModel, flags);
        parcel.writeTypedObject(mSatelliteOrbitModel, flags);
        parcel.writeTypedObject(mSatelliteHealth, flags);
        parcel.writeTypedObject(mSatelliteEphemerisTime, flags);
    }

    private QzssSatelliteEphemeris(Builder builder) {
        // Allow PRN beyond the range to support potential future extensibility.
        Preconditions.checkArgument(builder.mPrn >= 1);
        Preconditions.checkNotNull(builder.mGpsL2Params, "GpsL2Params cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteClockModel,
                "SatelliteClockModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteOrbitModel,
                "SatelliteOrbitModel cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteHealth,
                "SatelliteHealth cannot be null");
        Preconditions.checkNotNull(builder.mSatelliteEphemerisTime,
                "SatelliteEphemerisTime cannot be null");
        mPrn = builder.mPrn;
        mGpsL2Params = builder.mGpsL2Params;
        mSatelliteClockModel = builder.mSatelliteClockModel;
        mSatelliteOrbitModel = builder.mSatelliteOrbitModel;
        mSatelliteHealth = builder.mSatelliteHealth;
        mSatelliteEphemerisTime = builder.mSatelliteEphemerisTime;
    }

    public static final @NonNull Creator<QzssSatelliteEphemeris> CREATOR =
            new Creator<QzssSatelliteEphemeris>() {
                @Override
                @NonNull
                public QzssSatelliteEphemeris createFromParcel(Parcel in) {
                    final QzssSatelliteEphemeris.Builder qzssSatelliteEphemeris =
                            new Builder()
                                    .setPrn(in.readInt())
                                    .setGpsL2Params(in.readTypedObject(GpsL2Params.CREATOR))
                                    .setSatelliteClockModel(
                                            in.readTypedObject(GpsSatelliteClockModel.CREATOR))
                                    .setSatelliteOrbitModel(
                                            in.readTypedObject(KeplerianOrbitModel.CREATOR))
                                    .setSatelliteHealth(
                                            in.readTypedObject(GpsSatelliteHealth.CREATOR))
                                    .setSatelliteEphemerisTime(
                                            in.readTypedObject(SatelliteEphemerisTime.CREATOR));
                    return qzssSatelliteEphemeris.build();
                }

                @Override
                public QzssSatelliteEphemeris[] newArray(int size) {
                    return new QzssSatelliteEphemeris[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("QzssSatelliteEphemeris[");
        builder.append("prn=").append(mPrn);
        builder.append(", gpsL2Params=").append(mGpsL2Params);
        builder.append(", satelliteClockModel=").append(mSatelliteClockModel);
        builder.append(", satelliteOrbitModel=").append(mSatelliteOrbitModel);
        builder.append(", satelliteHealth=").append(mSatelliteHealth);
        builder.append(", satelliteEphemerisTime=").append(mSatelliteEphemerisTime);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link QzssSatelliteEphemeris}. */
    public static final class Builder {
        private int mPrn;
        private GpsL2Params mGpsL2Params;
        private GpsSatelliteClockModel mSatelliteClockModel;
        private KeplerianOrbitModel mSatelliteOrbitModel;
        private GpsSatelliteHealth mSatelliteHealth;
        private SatelliteEphemerisTime mSatelliteEphemerisTime;

        /** Sets the PRN of the satellite. */
        @NonNull
        public Builder setPrn(@IntRange(from = 183, to = 206) int prn) {
            mPrn = prn;
            return this;
        }

        /** Sets the L2 parameters of the satellite. */
        @NonNull
        public Builder setGpsL2Params(@NonNull GpsL2Params gpsL2Params) {
            mGpsL2Params = gpsL2Params;
            return this;
        }

        /** Sets the clock model of the satellite. */
        @NonNull
        public Builder setSatelliteClockModel(@NonNull GpsSatelliteClockModel satelliteClockModel) {
            mSatelliteClockModel = satelliteClockModel;
            return this;
        }

        /** Sets the orbit model of the satellite. */
        @NonNull
        public Builder setSatelliteOrbitModel(@NonNull KeplerianOrbitModel satelliteOrbitModel) {
            mSatelliteOrbitModel = satelliteOrbitModel;
            return this;
        }

        /** Sets the satellite health. */
        @NonNull
        public Builder setSatelliteHealth(@NonNull GpsSatelliteHealth satelliteHealth) {
            mSatelliteHealth = satelliteHealth;
            return this;
        }

        /** Sets the ephemeris time. */
        @NonNull
        public Builder setSatelliteEphemerisTime(
                @NonNull SatelliteEphemerisTime satelliteEphemerisTime) {
            mSatelliteEphemerisTime = satelliteEphemerisTime;
            return this;
        }

        /** Builds a {@link QzssSatelliteEphemeris} instance as specified by this builder. */
        @NonNull
        public QzssSatelliteEphemeris build() {
            return new QzssSatelliteEphemeris(this);
        }
    }
}
