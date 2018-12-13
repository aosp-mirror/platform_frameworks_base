/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Holds the characteristics of the reflecting plane that a satellite signal has bounced from.
 *
 * @hide
 */
@SystemApi
public final class GnssReflectingPlane implements Parcelable {

    /** Represents latitude in degrees of the reflecting plane */
    private double mLatitudeDegrees;
    /** Represents longitude in degrees of the reflecting plane. */
    private double mLongitudeDegrees;
    /**
     * Represents altitude in meters above the WGS 84 reference ellipsoid of the reflection point in
     * the plane
     */
    private double mAltitudeMeters;

    /** Represents azimuth clockwise from north of the reflecting plane in degrees. */
    private double mAzimuthDegrees;

    private GnssReflectingPlane(Builder builder) {
        mLatitudeDegrees = builder.mLatitudeDegrees;
        mLongitudeDegrees = builder.mLongitudeDegrees;
        mAltitudeMeters = builder.mAltitudeMeters;
        mAzimuthDegrees = builder.mAzimuthDegrees;
    }

    /** Gets the latitude in degrees of the reflecting plane. */
    public double getLatitudeDegrees() {
        return mLatitudeDegrees;
    }

    /** Gets the longitude in degrees of the reflecting plane. */
    public double getLongitudeDegrees() {
        return mLongitudeDegrees;
    }

    /**
     * Gets the altitude in meters above the WGS 84 reference ellipsoid of the reflecting point
     * within the plane
     */
    public double getAltitudeMeters() {
        return mAltitudeMeters;
    }

    /** Gets the azimuth clockwise from north of the reflecting plane in degrees. */
    public double getAzimuthDegrees() {
        return mAzimuthDegrees;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<GnssReflectingPlane> CREATOR =
            new Creator<GnssReflectingPlane>() {
                @Override
                public GnssReflectingPlane createFromParcel(Parcel parcel) {
                    GnssReflectingPlane reflectingPlane =
                            new Builder()
                                    .setLatitudeDegrees(parcel.readDouble())
                                    .setLongitudeDegrees(parcel.readDouble())
                                    .setAltitudeMeters(parcel.readDouble())
                                    .setAzimuthDegrees(parcel.readDouble())
                                    .build();
                    return reflectingPlane;
                }

                @Override
                public GnssReflectingPlane[] newArray(int i) {
                    return new GnssReflectingPlane[i];
                }
            };

    @Override
    public String toString() {
        final String format = "   %-29s = %s\n";
        StringBuilder builder = new StringBuilder("ReflectingPlane:\n");
        builder.append(String.format(format, "LatitudeDegrees = ", mLatitudeDegrees));
        builder.append(String.format(format, "LongitudeDegrees = ", mLongitudeDegrees));
        builder.append(String.format(format, "AltitudeMeters = ", mAltitudeMeters));
        builder.append(String.format(format, "AzimuthDegrees = ", mAzimuthDegrees));
        return builder.toString();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeDouble(mLatitudeDegrees);
        parcel.writeDouble(mLongitudeDegrees);
        parcel.writeDouble(mAltitudeMeters);
        parcel.writeDouble(mAzimuthDegrees);
    }

    /** Builder for {@link GnssReflectingPlane} */
    public static class Builder {
        /** For documentation, see corresponding fields in {@link GnssReflectingPlane}. */
        private double mLatitudeDegrees;

        private double mLongitudeDegrees;
        private double mAltitudeMeters;
        private double mAzimuthDegrees;

        /** Sets the latitude in degrees of the reflecting plane. */
        public Builder setLatitudeDegrees(double latitudeDegrees) {
            mLatitudeDegrees = latitudeDegrees;
            return this;
        }

        /** Sets the longitude in degrees of the reflecting plane. */
        public Builder setLongitudeDegrees(double longitudeDegrees) {
            mLongitudeDegrees = longitudeDegrees;
            return this;
        }

        /**
         * Sets the altitude in meters above the WGS 84 reference ellipsoid of the reflecting point
         * within the plane
         */
        public Builder setAltitudeMeters(double altitudeMeters) {
            mAltitudeMeters = altitudeMeters;
            return this;
        }

        /** Sets the azimuth clockwise from north of the reflecting plane in degrees. */
        public Builder setAzimuthDegrees(double azimuthDegrees) {
            mAzimuthDegrees = azimuthDegrees;
            return this;
        }

        /** Builds a {@link GnssReflectingPlane} object as specified by this builder. */
        public GnssReflectingPlane build() {
            return new GnssReflectingPlane(this);
        }
    }
}
