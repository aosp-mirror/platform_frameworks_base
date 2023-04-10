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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public final class PointingInfo implements Parcelable {
    /** Satellite azimuth in degrees */
    private float mSatelliteAzimuthDegrees;

    /** Satellite elevation in degrees */
    private float mSatelliteElevationDegrees;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public PointingInfo(float satelliteAzimuthDegrees, float satelliteElevationDegrees) {
        mSatelliteAzimuthDegrees = satelliteAzimuthDegrees;
        mSatelliteElevationDegrees = satelliteElevationDegrees;
    }

    private PointingInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeFloat(mSatelliteAzimuthDegrees);
        out.writeFloat(mSatelliteElevationDegrees);
    }

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

    public float getSatelliteAzimuthDegrees() {
        return mSatelliteAzimuthDegrees;
    }

    public float getSatelliteElevationDegrees() {
        return mSatelliteElevationDegrees;
    }

    private void readFromParcel(Parcel in) {
        mSatelliteAzimuthDegrees = in.readFloat();
        mSatelliteElevationDegrees = in.readFloat();
    }
}
