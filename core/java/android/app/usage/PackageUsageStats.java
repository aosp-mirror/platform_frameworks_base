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

public final class PackageUsageStats implements Parcelable {

    /**
     * {@hide}
     */
    public String mPackageName;

    /**
     * {@hide}
     */
    public long mTotalTimeSpent;

    /**
     * {@hide}
     */
    public long mLastTimeUsed;

    /**
     * {@hide}
     */
    public int mLastEvent;

    PackageUsageStats() {
    }

    PackageUsageStats(PackageUsageStats stats) {
        mPackageName = stats.mPackageName;
        mTotalTimeSpent = stats.mTotalTimeSpent;
        mLastTimeUsed = stats.mLastTimeUsed;
        mLastEvent = stats.mLastEvent;
    }

    public long getTotalTimeSpent() {
        return mTotalTimeSpent;
    }

    public long getLastTimeUsed() {
        return mLastTimeUsed;
    }

    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeLong(mTotalTimeSpent);
        dest.writeLong(mLastTimeUsed);
        dest.writeInt(mLastEvent);
    }

    public static final Creator<PackageUsageStats> CREATOR = new Creator<PackageUsageStats>() {
        @Override
        public PackageUsageStats createFromParcel(Parcel in) {
            PackageUsageStats stats = new PackageUsageStats();
            stats.mPackageName = in.readString();
            stats.mTotalTimeSpent = in.readLong();
            stats.mLastTimeUsed = in.readLong();
            stats.mLastEvent = in.readInt();
            return stats;
        }

        @Override
        public PackageUsageStats[] newArray(int size) {
            return new PackageUsageStats[size];
        }
    };
}
