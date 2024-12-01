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
package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * The position of a satellite in Earth orbit.
 *
 * Longitude is the angular distance, measured in degrees, east or west of the prime longitude line
 * ranging from -180 to 180 degrees
 * Altitude is the distance from the center of the Earth to the satellite, measured in kilometers
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SatellitePosition implements Parcelable {

    /**
     * The longitude of the satellite in degrees, ranging from -180 to 180 degrees
     */
    private double mLongitudeDegree;

    /**
     * The distance from the center of the earth to the satellite, measured in kilometers
     */
    private double mAltitudeKm;

    /**
     * Constructor for {@link SatellitePosition} used to create an instance from a {@link Parcel}.
     *
     * @param in The {@link Parcel} to read the satellite position data from.
     * @hide
     */
    public SatellitePosition(Parcel in) {
        mLongitudeDegree = in.readDouble();
        mAltitudeKm = in.readDouble();
    }

    /**
     * Constructor for {@link SatellitePosition}.
     *
     * @param longitudeDegree The longitude of the satellite in degrees.
     * @param altitudeKm  The altitude of the satellite in kilometers.
     * @hide
     */
    public SatellitePosition(double longitudeDegree, double altitudeKm) {
        mLongitudeDegree = longitudeDegree;
        mAltitudeKm = altitudeKm;
    }

    @NonNull
    public static final Creator<SatellitePosition> CREATOR = new Creator<SatellitePosition>() {
        @Override
        public SatellitePosition createFromParcel(Parcel in) {
            return new SatellitePosition(in);
        }

        @Override
        public SatellitePosition[] newArray(int size) {
            return new SatellitePosition[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mLongitudeDegree);
        dest.writeDouble(mAltitudeKm);
    }

    /**
     * Returns the longitude of the satellite in degrees, ranging from -180 to 180 degrees.
     *
     * @return The longitude of the satellite.
     */
    public double getLongitudeDegrees() {
        return mLongitudeDegree;
    }

    /**
     * Returns the altitude of the satellite in kilometers
     *
     * @return The altitude of the satellite.
     */
    public double getAltitudeKm() {
        return mAltitudeKm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatellitePosition that)) return false;

        return Double.compare(that.mLongitudeDegree, mLongitudeDegree) == 0
                && Double.compare(that.mAltitudeKm, mAltitudeKm) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLongitudeDegree, mAltitudeKm);
    }

    @Override
    @NonNull
    public String toString() {
        return "mLongitudeDegree: " + mLongitudeDegree + ", " + "mAltitudeKm: " + mAltitudeKm;
    }
}
