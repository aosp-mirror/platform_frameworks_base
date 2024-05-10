/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

import java.time.Duration;
import java.time.Instant;

/**
 * Data class for passing GNSS-derived time.
 * @hide
 */
public final class LocationTime implements Parcelable {

    private final long mUnixEpochTimeMillis;
    private final long mElapsedRealtimeNanos;

    public LocationTime(long unixEpochTimeMillis, long elapsedRealtimeNanos) {
        mUnixEpochTimeMillis = unixEpochTimeMillis;
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
    }

    /**
     * The Unix epoch time in millis, according to the Gnss location provider.
     */
    public long getUnixEpochTimeMillis() {
        return mUnixEpochTimeMillis;
    }

    /**
     * The elapsed nanos since boot when {@link #getUnixEpochTimeMillis} was the current time.
     */
    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mUnixEpochTimeMillis);
        out.writeLong(mElapsedRealtimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "LocationTime{"
                + "mUnixEpochTimeMillis=" + Instant.ofEpochMilli(mUnixEpochTimeMillis)
                + "(" + mUnixEpochTimeMillis + ")"
                + ", mElapsedRealtimeNanos=" + Duration.ofNanos(mElapsedRealtimeNanos)
                + "(" + mElapsedRealtimeNanos + ")"
                + '}';
    }

    public static final @NonNull Parcelable.Creator<LocationTime> CREATOR =
            new Parcelable.Creator<>() {
                public LocationTime createFromParcel(Parcel in) {
                    long time = in.readLong();
                    long elapsedRealtimeNanos = in.readLong();
                    return new LocationTime(time, elapsedRealtimeNanos);
                }

                public LocationTime[] newArray(int size) {
                    return new LocationTime[size];
                }
            };
}
