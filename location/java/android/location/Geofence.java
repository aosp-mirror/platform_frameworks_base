/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a geographical boundary, also known as a geofence.
 *
 * <p>Currently only circular geofences are supported and they do not support altitude changes.
 *
 * @hide
 */
public final class Geofence implements Parcelable {
    /** @hide */
    public static final int TYPE_HORIZONTAL_CIRCLE = 1;

    private final int mType;
    private final double mLatitude;
    private final double mLongitude;
    private final float mRadius;

    /**
     * Create a circular geofence (on a flat, horizontal plane).
     *
     * @param latitude latitude in degrees, between -90 and +90 inclusive
     * @param longitude longitude in degrees, between -180 and +180 inclusive
     * @param radius radius in meters
     * @return a new geofence
     * @throws IllegalArgumentException if any parameters are out of range
     */
    public static Geofence createCircle(double latitude, double longitude, float radius) {
        return new Geofence(latitude, longitude, radius);
    }

    private Geofence(double latitude, double longitude, float radius) {
        checkRadius(radius);
        checkLatLong(latitude, longitude);
        mType = TYPE_HORIZONTAL_CIRCLE;
        mLatitude = latitude;
        mLongitude = longitude;
        mRadius = radius;
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    /** @hide */
    public double getLatitude() {
        return mLatitude;
    }

    /** @hide */
    public double getLongitude() {
        return mLongitude;
    }

    /** @hide */
    public float getRadius() {
        return mRadius;
    }

    private static void checkRadius(float radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("invalid radius: " + radius);
        }
    }

    private static void checkLatLong(double latitude, double longitude) {
        if (latitude > 90.0 || latitude < -90.0) {
            throw new IllegalArgumentException("invalid latitude: " + latitude);
        }
        if (longitude > 180.0 || longitude < -180.0) {
            throw new IllegalArgumentException("invalid longitude: " + longitude);
        }
    }

    private static void checkType(int type) {
        if (type != TYPE_HORIZONTAL_CIRCLE) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<Geofence> CREATOR = new Parcelable.Creator<Geofence>() {
        @Override
        public Geofence createFromParcel(Parcel in) {
            int type = in.readInt();
            double latitude = in.readDouble();
            double longitude = in.readDouble();
            float radius = in.readFloat();
            checkType(type);
            return Geofence.createCircle(latitude, longitude, radius);
        }
        @Override
        public Geofence[] newArray(int size) {
            return new Geofence[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeDouble(mLatitude);
        parcel.writeDouble(mLongitude);
        parcel.writeFloat(mRadius);
    }

    private static String typeToString(int type) {
        switch (type) {
            case TYPE_HORIZONTAL_CIRCLE:
                return "CIRCLE";
            default:
                checkType(type);
                return null;
        }
    }

    @Override
    public String toString() {
        return String.format("Geofence[%s %.6f, %.6f %.0fm]",
                typeToString(mType), mLatitude, mLongitude, mRadius);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        long temp;
        temp = Double.doubleToLongBits(mLatitude);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(mLongitude);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        result = prime * result + Float.floatToIntBits(mRadius);
        result = prime * result + mType;
        return result;
    }

    /**
     * Two geofences are equal if they have identical properties.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof Geofence))
            return false;
        Geofence other = (Geofence) obj;
        if (mRadius != other.mRadius)
            return false;
        if (mLatitude != other.mLatitude)
            return false;
        if (mLongitude != other.mLongitude)
            return false;
        if (mType != other.mType)
            return false;
        return true;
    }
}
