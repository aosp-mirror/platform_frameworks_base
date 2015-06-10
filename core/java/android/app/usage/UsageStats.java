/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Contains usage statistics for an app package for a specific
 * time range.
 */
public final class UsageStats implements Parcelable {

    /**
     * {@hide}
     */
    public String mPackageName;

    /**
     * {@hide}
     */
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    public long mEndTimeStamp;

    /**
     * Last time used by the user with an explicit action (notification, activity launch).
     * {@hide}
     */
    public long mLastTimeUsed;

    /**
     * The last time the package was used via implicit, non-user initiated actions (service
     * was bound, etc).
     * {@hide}
     */
    public long mLastTimeSystemUsed;

    /**
     * Last time the package was used and the beginning of the idle countdown.
     * This uses a different timebase that is about how much the device has been in use in general.
     * {@hide}
     */
    public long mBeginIdleTime;

    /**
     * {@hide}
     */
    public long mTotalTimeInForeground;

    /**
     * {@hide}
     */
    public int mLaunchCount;

    /**
     * {@hide}
     */
    public int mLastEvent;

    /**
     * {@hide}
     */
    public UsageStats() {
    }

    public UsageStats(UsageStats stats) {
        mPackageName = stats.mPackageName;
        mBeginTimeStamp = stats.mBeginTimeStamp;
        mEndTimeStamp = stats.mEndTimeStamp;
        mLastTimeUsed = stats.mLastTimeUsed;
        mTotalTimeInForeground = stats.mTotalTimeInForeground;
        mLaunchCount = stats.mLaunchCount;
        mLastEvent = stats.mLastEvent;
        mBeginIdleTime = stats.mBeginIdleTime;
        mLastTimeSystemUsed = stats.mLastTimeSystemUsed;
    }

    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the beginning of the time range this {@link android.app.usage.UsageStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getFirstTimeStamp() {
        return mBeginTimeStamp;
    }

    /**
     * Get the end of the time range this {@link android.app.usage.UsageStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeStamp() {
        return mEndTimeStamp;
    }

    /**
     * Get the last time this package was used, measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeUsed() {
        return mLastTimeUsed;
    }

    /**
     * @hide
     * Get the last time this package was used by the system (not the user). This can be different
     * from {@link #getLastTimeUsed()} when the system binds to one of this package's services.
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeSystemUsed() {
        return mLastTimeSystemUsed;
    }

    /**
     * @hide
     * Get the last time this package was active, measured in milliseconds. This timestamp
     * uses a timebase that represents how much the device was used and not wallclock time.
     */
    public long getBeginIdleTime() {
        return mBeginIdleTime;
    }

    /**
     * Get the total time this package spent in the foreground, measured in milliseconds.
     */
    public long getTotalTimeInForeground() {
        return mTotalTimeInForeground;
    }

    /**
     * Add the statistics from the right {@link UsageStats} to the left. The package name for
     * both {@link UsageStats} objects must be the same.
     * @param right The {@link UsageStats} object to merge into this one.
     * @throws java.lang.IllegalArgumentException if the package names of the two
     *         {@link UsageStats} objects are different.
     */
    public void add(UsageStats right) {
        if (!mPackageName.equals(right.mPackageName)) {
            throw new IllegalArgumentException("Can't merge UsageStats for package '" +
                    mPackageName + "' with UsageStats for package '" + right.mPackageName + "'.");
        }

        if (right.mEndTimeStamp > mEndTimeStamp) {
            mLastEvent = right.mLastEvent;
            mEndTimeStamp = right.mEndTimeStamp;
            mLastTimeUsed = right.mLastTimeUsed;
            mBeginIdleTime = right.mBeginIdleTime;
            mLastTimeSystemUsed = right.mLastTimeSystemUsed;
        }
        mBeginTimeStamp = Math.min(mBeginTimeStamp, right.mBeginTimeStamp);
        mTotalTimeInForeground += right.mTotalTimeInForeground;
        mLaunchCount += right.mLaunchCount;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeLong(mBeginTimeStamp);
        dest.writeLong(mEndTimeStamp);
        dest.writeLong(mLastTimeUsed);
        dest.writeLong(mTotalTimeInForeground);
        dest.writeInt(mLaunchCount);
        dest.writeInt(mLastEvent);
        dest.writeLong(mBeginIdleTime);
        dest.writeLong(mLastTimeSystemUsed);
    }

    public static final Creator<UsageStats> CREATOR = new Creator<UsageStats>() {
        @Override
        public UsageStats createFromParcel(Parcel in) {
            UsageStats stats = new UsageStats();
            stats.mPackageName = in.readString();
            stats.mBeginTimeStamp = in.readLong();
            stats.mEndTimeStamp = in.readLong();
            stats.mLastTimeUsed = in.readLong();
            stats.mTotalTimeInForeground = in.readLong();
            stats.mLaunchCount = in.readInt();
            stats.mLastEvent = in.readInt();
            stats.mBeginIdleTime = in.readLong();
            stats.mLastTimeSystemUsed = in.readLong();
            return stats;
        }

        @Override
        public UsageStats[] newArray(int size) {
            return new UsageStats[size];
        }
    };
}
