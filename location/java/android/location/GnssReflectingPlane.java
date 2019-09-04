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

import android.annotation.FloatRange;
import android.annotation.NonNull;
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
    @FloatRange(from = -90.0f, to = 90.0f)
    private final double mLatitudeDegrees;
    /** Represents longitude in degrees of the reflecting plane. */
    @FloatRange(from = -180.0f, to = 180.0f)
    private final double mLongitudeDegrees;
    /**
     * Represents altitude in meters above the WGS 84 reference ellipsoid of the reflection point in
     * the plane
     */
    @FloatRange(from = -1000.0f, to = 10000.0f)
    private final double mAltitudeMeters;

    /** Represents azimuth clockwise from north of the reflecting plane in degrees. */
    @FloatRange(from = 0.0f, to = 360.0f)
    private final double mAzimuthDegrees;

    private GnssReflectingPlane(Builder builder) {
        mLatitudeDegrees = builder.mLatitudeDegrees;
        mLongitudeDegrees = builder.mLongitudeDegrees;
        mAltitudeMeters = builder.mAltitudeMeters;
        mAzimuthDegrees = builder.mAzimuthDegrees;
    }

    /** Gets the latitude in degrees of the reflecting plane. */
    @FloatRange(from = -90.0f, to = 90.0f)
    public double getLatitudeDegrees() {
        return mLatitudeDegrees;
    }

    /** Gets the longitude in degrees of the reflecting plane. */
    @FloatRange(from = -180.0f, to = 180.0f)
    public double getLongitudeDegrees() {
        return mLongitudeDegrees;
    }

    /**
     * Gets the altitude in meters above the WGS 84 reference ellipsoid of the reflecting point
     * within the plane
     */
    @FloatRange(from = -1000.0f, to = 10000.0f)
    public double getAltitudeMeters() {
        return mAltitudeMeters;
    }

    /** Gets the azimuth clockwise from north of the reflecting plane in degrees. */
    @FloatRange(from = 0.0f, to = 360.0f)
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
                @NonNull
                public GnssReflectingPlane createFromParcel(@NonNull Parcel parcel) {
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

    @NonNull
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
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeDouble(mLatitudeDegrees);
        parcel.writeDouble(mLongitudeDegrees);
        parcel.writeDouble(mAltitudeMeters);
        parcel.writeDouble(mAzimuthDegrees);
    }

    /** Builder for {@link GnssReflectingPlane} */
    public static final class Builder {
        /** For documentation, see corresponding fields in {@link GnssReflectingPlane}. */
        private double mLatitudeDegrees;
        private double mLongitudeDegrees;
        private double mAltitudeMeters;
        private double mAzimuthDegrees;

        /** Sets the latitude in degrees of the reflecting plane. */
        @NonNull public Builder setLatitudeDegrees(
                @FloatRange(from = -90.0f, to = 90.0f) double latitudeDegrees) {
            mLatitudeDegrees = latitudeDegrees;
            return this;
        }

        /** Sets the longitude in degrees of the reflecting plane. */
        @NonNull public Builder setLongitudeDegrees(
                @FloatRange(from = -180.0f, to = 180.0f) double longitudeDegrees) {
            mLongitudeDegrees = longitudeDegrees;
            return this;
        }

        /**
         * Sets the altitude in meters above the WGS 84 reference ellipsoid of the reflecting point
         * within the plane
         */
        @NonNull public Builder setAltitudeMeters(
                @FloatRange(from = -1000.0f, to = 10000.0f) double altitudeMeters) {
            mAltitudeMeters = altitudeMeters;
            return this;
        }

        /** Sets the azimuth clockwise from north of the reflecting plane in degrees. */
        @NonNull public Builder setAzimuthDegrees(
                @FloatRange(from = 0.0f, to = 360.0f) double azimuthDegrees) {
            mAzimuthDegrees = azimuthDegrees;
            return this;
        }

        /** Builds a {@link GnssReflectingPlane} object as specified by this builder. */
        @NonNull public GnssReflectingPlane build() {
            return new GnssReflectingPlane(this);
        }
    }
}
