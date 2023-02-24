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

    /** Antenna azimuth in degrees */
    private float mAntennaAzimuthDegrees;

    /**
     * Angle of rotation about the x axis. This value represents the angle between a plane
     * parallel to the device's screen and a plane parallel to the ground.
     */
    private float mAntennaPitchDegrees;

    /**
     * Angle of rotation about the y axis. This value represents the angle between a plane
     * perpendicular to the device's screen and a plane parallel to the ground.
     */
    private float mAntennaRollDegrees;

    /**
     * @hide
     */
    public PointingInfo(float satelliteAzimuthDegrees, float satelliteElevationDegrees,
            float antennaAzimuthDegrees, float antennaPitchDegrees, float antennaRollDegrees) {
        mSatelliteAzimuthDegrees = satelliteAzimuthDegrees;
        mSatelliteElevationDegrees = satelliteElevationDegrees;
        mAntennaAzimuthDegrees = antennaAzimuthDegrees;
        mAntennaPitchDegrees = antennaPitchDegrees;
        mAntennaRollDegrees = antennaRollDegrees;
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
        out.writeFloat(mAntennaAzimuthDegrees);
        out.writeFloat(mAntennaPitchDegrees);
        out.writeFloat(mAntennaRollDegrees);
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
        sb.append(",");

        sb.append("AntennaAzimuthDegrees:");
        sb.append(mAntennaAzimuthDegrees);
        sb.append(",");

        sb.append("AntennaPitchDegrees:");
        sb.append(mAntennaPitchDegrees);
        sb.append(",");

        sb.append("AntennaRollDegrees:");
        sb.append(mAntennaRollDegrees);
        return sb.toString();
    }

    public float getSatelliteAzimuthDegrees() {
        return mSatelliteAzimuthDegrees;
    }

    public float getSatelliteElevationDegrees() {
        return mSatelliteElevationDegrees;
    }

    public float getAntennaAzimuthDegrees() {
        return mAntennaAzimuthDegrees;
    }

    public float getAntennaPitchDegrees() {
        return mAntennaPitchDegrees;
    }

    public float getAntennaRollDegrees() {
        return mAntennaRollDegrees;
    }

    private void readFromParcel(Parcel in) {
        mSatelliteAzimuthDegrees = in.readFloat();
        mSatelliteElevationDegrees = in.readFloat();
        mAntennaAzimuthDegrees = in.readFloat();
        mAntennaPitchDegrees = in.readFloat();
        mAntennaRollDegrees = in.readFloat();
    }
}
