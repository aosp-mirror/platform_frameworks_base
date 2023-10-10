/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * PointingInfo is used to store the position of satellite received from satellite modem.
 * The position of satellite is represented by azimuth and elevation angles
 * with degrees as unit of measurement. Satellite position is based on magnetic north direction.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public final class PointingInfo implements Parcelable {
    /** Satellite azimuth in degrees */
    private float mSatelliteAzimuthDegrees;

    /** Satellite elevation in degrees */
    private float mSatelliteElevationDegrees;

    /**
     * @hide
     */
    public PointingInfo(float satelliteAzimuthDegrees, float satelliteElevationDegrees) {
        mSatelliteAzimuthDegrees = satelliteAzimuthDegrees;
        mSatelliteElevationDegrees = satelliteElevationDegrees;
    }

    private PointingInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public int describeContents() {
        return 0;
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeFloat(mSatelliteAzimuthDegrees);
        out.writeFloat(mSatelliteElevationDegrees);
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final @android.annotation.NonNull Creator<PointingInfo> CREATOR =
            new Creator<PointingInfo>() {
                @Override
                public PointingInfo createFromParcel(Parcel in) {
                    return new PointingInfo(in);
                }

                @Override
                public PointingInfo[] newArray(int size) {
                    return new PointingInfo[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PointingInfo that = (PointingInfo) o;
        return mSatelliteAzimuthDegrees == that.mSatelliteAzimuthDegrees
                && mSatelliteElevationDegrees == that.mSatelliteElevationDegrees;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSatelliteAzimuthDegrees, mSatelliteElevationDegrees);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SatelliteAzimuthDegrees:");
        sb.append(mSatelliteAzimuthDegrees);
        sb.append(",");

        sb.append("SatelliteElevationDegrees:");
        sb.append(mSatelliteElevationDegrees);
        return sb.toString();
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public float getSatelliteAzimuthDegrees() {
        return mSatelliteAzimuthDegrees;
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public float getSatelliteElevationDegrees() {
        return mSatelliteElevationDegrees;
    }

    private void readFromParcel(Parcel in) {
        mSatelliteAzimuthDegrees = in.readFloat();
        mSatelliteElevationDegrees = in.readFloat();
    }
}
