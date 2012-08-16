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

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.TimeUtils;

public final class LocationRequest implements Parcelable {
    // QOS control
    public static final int ACCURACY_FINE = 100;  // ~1 meter
    public static final int ACCURACY_BLOCK = 102; // ~100 meters
    public static final int ACCURACY_CITY = 104;  // ~10 km
    public static final int POWER_NONE = 200;
    public static final int POWER_LOW = 201;
    public static final int POWER_HIGH = 203;

    private int mQuality = POWER_LOW;
    private long mFastestInterval = 6 * 1000;  // 6 seconds
    private long mInterval = 60 * 1000;        // 1 minute
    private long mExpireAt = Long.MAX_VALUE;  // no expiry
    private int mNumUpdates = Integer.MAX_VALUE;  // no expiry
    private float mSmallestDisplacement = 0.0f;    // meters

    private String mProvider = null;  // for deprecated API's that explicitly request a provider

    public static LocationRequest create() {
        LocationRequest request = new LocationRequest();
        return request;
    }

    /** @hide */
    public static LocationRequest createFromDeprecatedProvider(String provider, long minTime,
            float minDistance, boolean singleShot) {
        if (minTime < 0) minTime = 0;
        if (minDistance < 0) minDistance = 0;

        int quality;
        if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            quality = POWER_NONE;
        } else if (LocationManager.GPS_PROVIDER.equals(provider)) {
            quality = ACCURACY_FINE;
        } else {
            quality = POWER_LOW;
        }

        LocationRequest request = new LocationRequest()
            .setProvider(provider)
            .setQuality(quality)
            .setInterval(minTime)
            .setFastestInterval(minTime)
            .setSmallestDisplacement(minDistance);
        if (singleShot) request.setNumUpdates(1);
        return request;
    }

    /** @hide */
    public static LocationRequest createFromDeprecatedCriteria(Criteria criteria, long minTime,
            float minDistance, boolean singleShot) {
        if (minTime < 0) minTime = 0;
        if (minDistance < 0) minDistance = 0;

        int quality;
        switch (criteria.getAccuracy()) {
            case Criteria.ACCURACY_COARSE:
                quality = ACCURACY_BLOCK;
                break;
            case Criteria.ACCURACY_FINE:
                quality = ACCURACY_FINE;
                break;
            default: {
                switch (criteria.getPowerRequirement()) {
                    case Criteria.POWER_HIGH:
                        quality = POWER_HIGH;
                    default:
                        quality = POWER_LOW;
                }
            }
        }

        LocationRequest request = new LocationRequest()
            .setQuality(quality)
            .setInterval(minTime)
            .setFastestInterval(minTime)
            .setSmallestDisplacement(minDistance);
        if (singleShot) request.setNumUpdates(1);
        return request;
    }

    /** @hide */
    public LocationRequest() { }

    public LocationRequest setQuality(int quality) {
        checkQuality(quality);
        mQuality = quality;
        return this;
    }

    public int getQuality() {
        return mQuality;
    }

    public LocationRequest setInterval(long millis) {
        checkInterval(millis);
        mInterval = millis;
        return this;
    }

    public long getInterval() {
        return mInterval;
    }

    public LocationRequest setFastestInterval(long millis) {
        checkInterval(millis);
        mFastestInterval = millis;
        return this;
    }

    public long getFastestInterval() {
        return mFastestInterval;
    }

    public LocationRequest setExpireIn(long millis) {
        mExpireAt = millis + SystemClock.elapsedRealtime();
        if (mExpireAt < 0) mExpireAt = 0;
        return this;
    }

    public LocationRequest setExpireAt(long millis) {
        mExpireAt = millis;
        if (mExpireAt < 0) mExpireAt = 0;
        return this;
    }

    public long getExpireAt() {
        return mExpireAt;
    }

    public int getNumUpdates() {
        return mNumUpdates;
    }

    /** @hide */
    public void decrementNumUpdates() {
        if (mNumUpdates != Integer.MAX_VALUE) {
            mNumUpdates--;
        }
        if (mNumUpdates < 0) {
            mNumUpdates = 0;
        }
    }

    public LocationRequest setNumUpdates(int numUpdates) {
        if (numUpdates < 0) throw new IllegalArgumentException("invalid numUpdates: " + numUpdates);
        mNumUpdates = numUpdates;
        return this;
    }

    /** @hide */
    public LocationRequest setProvider(String provider) {
        checkProvider(provider);
        mProvider = provider;
        return this;
    }

    /** @hide */
    public String getProvider() {
        return mProvider;
    }

    /** @hide */
    public LocationRequest setSmallestDisplacement(float meters) {
        checkDisplacement(meters);
        mSmallestDisplacement = meters;
        return this;
    }

    /** @hide */
    public float getSmallestDisplacement() {
        return mSmallestDisplacement;
    }

    private static void checkInterval(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("invalid interval: " + millis);
        }
    }

    private static void checkQuality(int quality) {
        switch (quality) {
            case ACCURACY_FINE:
            case ACCURACY_BLOCK:
            case ACCURACY_CITY:
            case POWER_NONE:
            case POWER_LOW:
            case POWER_HIGH:
                break;
            default:
                throw new IllegalArgumentException("invalid quality: " + quality);
        }
    }

    private static void checkDisplacement(float meters) {
        if (meters < 0.0f) {
            throw new IllegalArgumentException("invalid displacement: " + meters);
        }
    }

    private static void checkProvider(String name) {
        if (name == null) {
            throw new IllegalArgumentException("invalid provider: " + name);
        }
    }

    public static final Parcelable.Creator<LocationRequest> CREATOR =
            new Parcelable.Creator<LocationRequest>() {
        @Override
        public LocationRequest createFromParcel(Parcel in) {
            LocationRequest request = new LocationRequest();
            request.setQuality(in.readInt());
            request.setFastestInterval(in.readLong());
            request.setInterval(in.readLong());
            request.setExpireAt(in.readLong());
            request.setNumUpdates(in.readInt());
            request.setSmallestDisplacement(in.readFloat());
            String provider = in.readString();
            if (provider != null) request.setProvider(provider);
            return request;
        }
        @Override
        public LocationRequest[] newArray(int size) {
            return new LocationRequest[size];
        }
    };
    @Override
    public int describeContents() {
        return 0;
    }
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mQuality);
        parcel.writeLong(mFastestInterval);
        parcel.writeLong(mInterval);
        parcel.writeLong(mExpireAt);
        parcel.writeInt(mNumUpdates);
        parcel.writeFloat(mSmallestDisplacement);
        parcel.writeString(mProvider);
    }

    /** @hide */
    public static String qualityToString(int quality) {
        switch (quality) {
            case ACCURACY_FINE:
                return "ACCURACY_FINE";
            case ACCURACY_BLOCK:
                return "ACCURACY_BLOCK";
            case ACCURACY_CITY:
                return "ACCURACY_CITY";
            case POWER_NONE:
                return "POWER_NONE";
            case POWER_LOW:
                return "POWER_LOW";
            case POWER_HIGH:
                return "POWER_HIGH";
            default:
                return "???";
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Request[").append(qualityToString(mQuality));
        if (mProvider != null) s.append(' ').append(mProvider);
        if (mQuality != POWER_NONE) {
            s.append(" requested=");
            TimeUtils.formatDuration(mInterval, s);
        }
        s.append(" fastest=");
        TimeUtils.formatDuration(mFastestInterval, s);
        if (mExpireAt != Long.MAX_VALUE) {
            long expireIn = mExpireAt - SystemClock.elapsedRealtime();
            s.append(" expireIn=");
            TimeUtils.formatDuration(expireIn, s);
        }
        if (mNumUpdates != Integer.MAX_VALUE){
            s.append(" num=").append(mNumUpdates);
        }
        s.append(']');
        return s.toString();
    }
}
