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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Represents a geographical boundary, also known as a geofence.
 *
 * <p>Currently only circular geofences are supported and they do not support altitude changes.
 *
 * @hide
 */
public final class Geofence implements Parcelable {

    private final double mLatitude;
    private final double mLongitude;
    private final float mRadius;
    private long mExpirationRealtimeMs;

    /**
     * Create a circular geofence (on a flat, horizontal plane).
     *
     * @param latitude latitude in degrees, between -90 and +90 inclusive
     * @param longitude longitude in degrees, between -180 and +180 inclusive
     * @param radius radius in meters
     * @return a new geofence
     * @throws IllegalArgumentException if any parameters are out of range
     */
    public static Geofence createCircle(double latitude, double longitude, float radius,
            long expirationRealtimeMs) {
        return new Geofence(latitude, longitude, radius, expirationRealtimeMs);
    }

    Geofence(double latitude, double longitude, float radius, long expirationRealtimeMs) {
        Preconditions.checkArgumentInRange(latitude, -90.0, 90.0, "latitude");
        Preconditions.checkArgumentInRange(longitude, -180.0, 180.0, "latitude");
        Preconditions.checkArgument(radius > 0, "invalid radius: " + radius);

        mLatitude = latitude;
        mLongitude = longitude;
        mRadius = radius;
        mExpirationRealtimeMs = expirationRealtimeMs;
    }

    public double getLatitude() {
        return mLatitude;
    }

    public double getLongitude() {
        return mLongitude;
    }

    public float getRadius() {
        return mRadius;
    }

    public boolean isExpired() {
        return isExpired(SystemClock.elapsedRealtime());
    }

    /**
     * Returns true if this geofence is expired with reference to the given realtime.
     */
    public boolean isExpired(long referenceRealtimeMs) {
        return referenceRealtimeMs >= mExpirationRealtimeMs;
    }

    @UnsupportedAppUsage
    public static final @NonNull Parcelable.Creator<Geofence> CREATOR =
            new Parcelable.Creator<Geofence>() {
                @Override
                public Geofence createFromParcel(Parcel in) {
                    return new Geofence(
                            in.readDouble(),
                            in.readDouble(),
                            in.readFloat(),
                            in.readLong());
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
        parcel.writeDouble(mLatitude);
        parcel.writeDouble(mLongitude);
        parcel.writeFloat(mRadius);
        parcel.writeLong(mExpirationRealtimeMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Geofence)) {
            return false;
        }
        Geofence geofence = (Geofence) o;
        return Double.compare(geofence.mLatitude, mLatitude) == 0
                && Double.compare(geofence.mLongitude, mLongitude) == 0
                && Float.compare(geofence.mRadius, mRadius) == 0
                && mExpirationRealtimeMs == geofence.mExpirationRealtimeMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLatitude, mLongitude, mRadius);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Geofence[(").append(mLatitude).append(", ").append(mLongitude).append(")");
        builder.append(" ").append(mRadius).append("m");
        if (mExpirationRealtimeMs < Long.MAX_VALUE) {
            if (isExpired()) {
                builder.append(" expired");
            } else {
                builder.append(" expires=");
                TimeUtils.formatDuration(mExpirationRealtimeMs, builder);
            }
        }
        builder.append("]");
        return builder.toString();
    }
}
