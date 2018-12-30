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

import android.annotation.UnsupportedAppUsage;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the usage statistics of a device {@link android.content.res.Configuration} for a
 * specific time range.
 */
public final class ConfigurationStats implements Parcelable {

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public Configuration mConfiguration;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mEndTimeStamp;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mLastTimeActive;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long mTotalTimeActive;

    /**
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int mActivationCount;

    /**
     * {@hide}
     */
    public ConfigurationStats() {
    }

    public ConfigurationStats(ConfigurationStats stats) {
        mConfiguration = stats.mConfiguration;
        mBeginTimeStamp = stats.mBeginTimeStamp;
        mEndTimeStamp = stats.mEndTimeStamp;
        mLastTimeActive = stats.mLastTimeActive;
        mTotalTimeActive = stats.mTotalTimeActive;
        mActivationCount = stats.mActivationCount;
    }

    public Configuration getConfiguration() {
        return mConfiguration;
    }

    /**
     * Get the beginning of the time range this {@link ConfigurationStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getFirstTimeStamp() {
        return mBeginTimeStamp;
    }

    /**
     * Get the end of the time range this {@link ConfigurationStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeStamp() {
        return mEndTimeStamp;
    }

    /**
     * Get the last time this configuration was active, measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeActive() {
        return mLastTimeActive;
    }

    /**
     * Get the total time this configuration was active, measured in milliseconds.
     */
    public long getTotalTimeActive() {
        return mTotalTimeActive;
    }

    /**
     * Get the number of times this configuration was active.
     */
    public int getActivationCount() {
        return mActivationCount;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mConfiguration != null) {
            dest.writeInt(1);
            mConfiguration.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }

        dest.writeLong(mBeginTimeStamp);
        dest.writeLong(mEndTimeStamp);
        dest.writeLong(mLastTimeActive);
        dest.writeLong(mTotalTimeActive);
        dest.writeInt(mActivationCount);
    }

    public static final Creator<ConfigurationStats> CREATOR = new Creator<ConfigurationStats>() {
        @Override
        public ConfigurationStats createFromParcel(Parcel source) {
            ConfigurationStats stats = new ConfigurationStats();
            if (source.readInt() != 0) {
                stats.mConfiguration = Configuration.CREATOR.createFromParcel(source);
            }
            stats.mBeginTimeStamp = source.readLong();
            stats.mEndTimeStamp = source.readLong();
            stats.mLastTimeActive = source.readLong();
            stats.mTotalTimeActive = source.readLong();
            stats.mActivationCount = source.readInt();
            return stats;
        }

        @Override
        public ConfigurationStats[] newArray(int size) {
            return new ConfigurationStats[size];
        }
    };
}
