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

package android.app.timedetector;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data class for passing a Unix epoch time anchored to the elapsed realtime clock.
 *
 * @hide
 */
public final class TimePoint implements Parcelable {

    private final long mUnixEpochTimeMillis;
    private final long mElapsedRealtimeMillis;

    public TimePoint(long unixEpochTimeMillis, long elapsedRealtimeMillis) {
        mUnixEpochTimeMillis = unixEpochTimeMillis;
        mElapsedRealtimeMillis = elapsedRealtimeMillis;
    }

    /**
     * The current Unix epoch time, according to the external source.
     */
    public long getUnixEpochTimeMillis() {
        return mUnixEpochTimeMillis;
    }

    /**
     * The elapsed millis since boot when {@link #getUnixEpochTimeMillis} was computed.
     */
    public long getElapsedRealtimeMillis() {
        return mElapsedRealtimeMillis;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mUnixEpochTimeMillis);
        out.writeLong(mElapsedRealtimeMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimePoint)) {
            return false;
        }
        TimePoint timePoint = (TimePoint) o;
        return mUnixEpochTimeMillis == timePoint.mUnixEpochTimeMillis
                && mElapsedRealtimeMillis == timePoint.mElapsedRealtimeMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUnixEpochTimeMillis, mElapsedRealtimeMillis);
    }

    @Override
    public String toString() {
        return "TimePoint{"
                + "mUnixEpochTimeMillis=" + mUnixEpochTimeMillis
                + ", mElapsedRealtimeMillis=" + mElapsedRealtimeMillis
                + '}';
    }

    public static final @NonNull Creator<TimePoint> CREATOR =
            new Creator<TimePoint>() {
                public TimePoint createFromParcel(Parcel in) {
                    long unixEpochTime = in.readLong();
                    long elapsedRealtimeMillis = in.readLong();
                    return new TimePoint(unixEpochTime, elapsedRealtimeMillis);
                }

                public TimePoint[] newArray(int size) {
                    return new TimePoint[size];
                }
            };
}
