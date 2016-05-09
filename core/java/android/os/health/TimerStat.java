/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A TimerStat object stores a count and a time.
 *
 * @more
 * When possible, the other APIs in this package avoid requiring a TimerStat
 * object to be constructed, even internally, but the getTimers method on
 * {@link android.os.health.HealthStats} does require TimerStat objects.
 */
public final class TimerStat implements Parcelable {
    private int mCount;
    private long mTime;

    /**
     * The CREATOR instance for use by aidl Binder interfaces.
     */
    public static final Parcelable.Creator<TimerStat> CREATOR
            = new Parcelable.Creator<TimerStat>() {
        public TimerStat createFromParcel(Parcel in) {
            return new TimerStat(in);
        }

        public TimerStat[] newArray(int size) {
            return new TimerStat[size];
        }
    };

    /**
     * Construct an empty TimerStat object with the count and time set to 0.
     */
    public TimerStat() {
    }

    /**
     * Construct a TimerStat object with the supplied count and time fields.
     *
     * @param count The count
     * @param time The time
     */
    public TimerStat(int count, long time) {
        mCount = count;
        mTime = time;
    }

    /**
     * Construct a TimerStat object reading the values from a {@link android.os.Parcel Parcel}
     * object.
     */
    public TimerStat(Parcel in) {
        mCount = in.readInt();
        mTime = in.readLong();
    }

    /**
     * @inheritDoc
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Write this TimerStat object to a parcel.
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCount);
        out.writeLong(mTime);
    }

    /**
     * Set the count for this timer.
     */
    public void setCount(int count) {
        mCount = count;
    }

    /**
     * Get the count for this timer.
     */
    public int getCount() {
        return mCount;
    }

    /**
     * Set the time for this timer in milliseconds.
     */
    public void setTime(long time) {
        mTime = time;
    }

    /**
     * Get the time for this timer in milliseconds.
     */
    public long getTime() {
        return mTime;
    }
}
