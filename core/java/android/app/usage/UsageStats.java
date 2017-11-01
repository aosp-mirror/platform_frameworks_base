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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

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
    public ArrayMap<String, ArrayMap<String, Integer>> mChooserCounts;

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
        mChooserCounts = stats.mChooserCounts;
    }

    /**
     * {@hide}
     */
    public UsageStats getObfuscatedForInstantApp() {
        final UsageStats ret = new UsageStats(this);

        ret.mPackageName = UsageEvents.INSTANT_APP_PACKAGE_NAME;

        return ret;
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

        // We use the mBeginTimeStamp due to a bug where UsageStats files can overlap with
        // regards to their mEndTimeStamp.
        if (right.mBeginTimeStamp > mBeginTimeStamp) {
            // Even though incoming UsageStat begins after this one, its last time used fields
            // may somehow be empty or chronologically preceding the older UsageStat.
            mLastEvent = Math.max(mLastEvent, right.mLastEvent);
            mLastTimeUsed = Math.max(mLastTimeUsed, right.mLastTimeUsed);
        }
        mBeginTimeStamp = Math.min(mBeginTimeStamp, right.mBeginTimeStamp);
        mEndTimeStamp = Math.max(mEndTimeStamp, right.mEndTimeStamp);
        mTotalTimeInForeground += right.mTotalTimeInForeground;
        mLaunchCount += right.mLaunchCount;
        if (mChooserCounts == null) {
            mChooserCounts = right.mChooserCounts;
        } else if (right.mChooserCounts != null) {
            final int chooserCountsSize = right.mChooserCounts.size();
            for (int i = 0; i < chooserCountsSize; i++) {
                String action = right.mChooserCounts.keyAt(i);
                ArrayMap<String, Integer> counts = right.mChooserCounts.valueAt(i);
                if (!mChooserCounts.containsKey(action) || mChooserCounts.get(action) == null) {
                    mChooserCounts.put(action, counts);
                    continue;
                }
                final int annotationSize = counts.size();
                for (int j = 0; j < annotationSize; j++) {
                    String key = counts.keyAt(j);
                    int rightValue = counts.valueAt(j);
                    int leftValue = mChooserCounts.get(action).getOrDefault(key, 0);
                    mChooserCounts.get(action).put(key, leftValue + rightValue);
                }
            }
        }
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
        Bundle allCounts = new Bundle();
        if (mChooserCounts != null) {
            final int chooserCountSize = mChooserCounts.size();
            for (int i = 0; i < chooserCountSize; i++) {
                String action = mChooserCounts.keyAt(i);
                ArrayMap<String, Integer> counts = mChooserCounts.valueAt(i);
                Bundle currentCounts = new Bundle();
                final int annotationSize = counts.size();
                for (int j = 0; j < annotationSize; j++) {
                    currentCounts.putInt(counts.keyAt(j), counts.valueAt(j));
                }
                allCounts.putBundle(action, currentCounts);
            }
        }
        dest.writeBundle(allCounts);
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
            Bundle allCounts = in.readBundle();
            if (allCounts != null) {
                stats.mChooserCounts = new ArrayMap<>();
                for (String action : allCounts.keySet()) {
                    if (!stats.mChooserCounts.containsKey(action)) {
                        ArrayMap<String, Integer> newCounts = new ArrayMap<>();
                        stats.mChooserCounts.put(action, newCounts);
                    }
                    Bundle currentCounts = allCounts.getBundle(action);
                    if (currentCounts != null) {
                        for (String key : currentCounts.keySet()) {
                            int value = currentCounts.getInt(key);
                            if (value > 0) {
                                stats.mChooserCounts.get(action).put(key, value);
                            }
                        }
                    }
                }
            }
            return stats;
        }

        @Override
        public UsageStats[] newArray(int size) {
            return new UsageStats[size];
        }
    };
}
