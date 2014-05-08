/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Snapshot of current usage stats data.
 * @hide
 */
public class UsageStats implements Parcelable {
    /** @hide */
    public final ArrayMap<String, PackageStats> mPackages = new ArrayMap<String, PackageStats>();
    /** @hide */
    public final ArrayMap<Configuration, ConfigurationStats> mConfigurations
            = new ArrayMap<Configuration, ConfigurationStats>();

    public static class PackageStats implements Parcelable {
        private final String mPackageName;
        private int mLaunchCount;
        private long mUsageTime;
        private long mResumedTime;

        /** @hide */
        public final ArrayMap<String, Long> componentResumeTimes;

        public static final Parcelable.Creator<PackageStats> CREATOR
                = new Parcelable.Creator<PackageStats>() {
            public PackageStats createFromParcel(Parcel in) {
                return new PackageStats(in);
            }

            public PackageStats[] newArray(int size) {
                return new PackageStats[size];
            }
        };

        public String toString() {
            return "PackageStats{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + mPackageName + "}";
        }

        /** @hide */
        public PackageStats(String pkgName) {
            mPackageName = pkgName;
            componentResumeTimes = new ArrayMap<String, Long>();
        }

        /** @hide */
        public PackageStats(String pkgName, int count, long time, Map<String, Long> lastResumeTimes) {
            mPackageName = pkgName;
            mLaunchCount = count;
            mUsageTime = time;
            componentResumeTimes = new ArrayMap<String, Long>();
            componentResumeTimes.putAll(lastResumeTimes);
        }

        /** @hide */
        public PackageStats(Parcel source) {
            mPackageName = source.readString();
            mLaunchCount = source.readInt();
            mUsageTime = source.readLong();
            final int N = source.readInt();
            componentResumeTimes = new ArrayMap<String, Long>(N);
            for (int i = 0; i < N; i++) {
                String component = source.readString();
                long lastResumeTime = source.readLong();
                componentResumeTimes.put(component, lastResumeTime);
            }
        }

        /** @hide */
        public PackageStats(PackageStats pStats) {
            mPackageName = pStats.mPackageName;
            mLaunchCount = pStats.mLaunchCount;
            mUsageTime = pStats.mUsageTime;
            componentResumeTimes = new ArrayMap<String, Long>(pStats.componentResumeTimes);
        }

        /** @hide */
        public void resume(boolean launched) {
            if (launched) {
                mLaunchCount++;
            }
            mResumedTime = SystemClock.elapsedRealtime();
        }

        /** @hide */
        public void pause() {
            if (mResumedTime > 0) {
                mUsageTime += SystemClock.elapsedRealtime() - mResumedTime;
            }
            mResumedTime = 0;
        }

        public final String getPackageName() {
            return mPackageName;
        }

        public final long getUsageTime(long elapsedRealtime) {
            return mUsageTime + (mResumedTime > 0 ? (elapsedRealtime- mResumedTime) : 0);
        }

        public final int getLaunchCount() {
            return mLaunchCount;
        }

        /** @hide */
        public boolean clearUsageTimes() {
            mLaunchCount = 0;
            mUsageTime = 0;
            return mResumedTime <= 0 && componentResumeTimes.isEmpty();
        }

        public final int describeContents() {
            return 0;
        }

        public final void writeToParcel(Parcel dest, int parcelableFlags) {
            writeToParcel(dest, parcelableFlags, 0);
        }

        final void writeToParcel(Parcel dest, int parcelableFlags, long elapsedRealtime) {
            dest.writeString(mPackageName);
            dest.writeInt(mLaunchCount);
            dest.writeLong(elapsedRealtime > 0 ? getUsageTime(elapsedRealtime) : mUsageTime);
            dest.writeInt(componentResumeTimes.size());
            for (Map.Entry<String, Long> ent : componentResumeTimes.entrySet()) {
                dest.writeString(ent.getKey());
                dest.writeLong(ent.getValue());
            }
        }

        /** @hide */
        public void writeExtendedToParcel(Parcel dest, int parcelableFlags) {
        }
    }

    public static class ConfigurationStats implements Parcelable {
        private final Configuration mConfiguration;
        private long mLastUsedTime;
        private int mUsageCount;
        private long mUsageTime;
        private long mStartedTime;

        public static final Parcelable.Creator<ConfigurationStats> CREATOR
                = new Parcelable.Creator<ConfigurationStats>() {
            public ConfigurationStats createFromParcel(Parcel in) {
                return new ConfigurationStats(in);
            }

            public ConfigurationStats[] newArray(int size) {
                return new ConfigurationStats[size];
            }
        };

        public String toString() {
            return "ConfigurationStats{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + mConfiguration + "}";
        }

        /** @hide */
        public ConfigurationStats(Configuration config) {
            mConfiguration = config;
        }

        /** @hide */
        public ConfigurationStats(Parcel source) {
            mConfiguration = Configuration.CREATOR.createFromParcel(source);
            mLastUsedTime = source.readLong();
            mUsageCount = source.readInt();
            mUsageTime = source.readLong();
        }

        /** @hide */
        public ConfigurationStats(ConfigurationStats pStats) {
            mConfiguration = pStats.mConfiguration;
            mLastUsedTime = pStats.mLastUsedTime;
            mUsageCount = pStats.mUsageCount;
            mUsageTime = pStats.mUsageTime;
        }

        public final Configuration getConfiguration() {
            return mConfiguration;
        }

        public final long getLastUsedTime() {
            return mLastUsedTime;
        }

        public final long getUsageTime(long elapsedRealtime) {
            return mUsageTime + (mStartedTime > 0 ? (elapsedRealtime- mStartedTime) : 0);
        }

        public final int getUsageCount() {
            return mUsageCount;
        }

        /** @hide */
        public void start() {
            mLastUsedTime = System.currentTimeMillis();
            mUsageCount++;
            mStartedTime = SystemClock.elapsedRealtime();
        }

        /** @hide */
        public void stop() {
            if (mStartedTime > 0) {
                mUsageTime += SystemClock.elapsedRealtime() - mStartedTime;
            }
            mStartedTime = 0;
        }

        /** @hide */
        public boolean clearUsageTimes() {
            mUsageCount = 0;
            mUsageTime = 0;
            return mLastUsedTime == 0 && mStartedTime <= 0;
        }

        public final int describeContents() {
            return 0;
        }

        public final void writeToParcel(Parcel dest, int parcelableFlags) {
            writeToParcel(dest, parcelableFlags, 0);
        }

        final void writeToParcel(Parcel dest, int parcelableFlags, long elapsedRealtime) {
            mConfiguration.writeToParcel(dest, parcelableFlags);
            dest.writeLong(mLastUsedTime);
            dest.writeInt(mUsageCount);
            dest.writeLong(elapsedRealtime > 0 ? getUsageTime(elapsedRealtime) : mUsageTime);
        }

        /** @hide */
        public void writeExtendedToParcel(Parcel dest, int parcelableFlags) {
        }
    }

    /** @hide */
    public UsageStats() {
    }

    /** @hide */
    public UsageStats(Parcel source, boolean extended) {
        int N = source.readInt();
        for (int i=0; i<N; i++) {
            PackageStats pkg = extended ? onNewPackageStats(source) : new PackageStats(source);
            mPackages.put(pkg.getPackageName(), pkg);
        }
        N = source.readInt();
        for (int i=0; i<N; i++) {
            ConfigurationStats config = extended ? onNewConfigurationStats(source)
                    : new ConfigurationStats(source);
            mConfigurations.put(config.getConfiguration(), config);
        }
    }

    public int getPackageStatsCount() {
        return mPackages.size();
    }

    public PackageStats getPackageStatsAt(int index) {
        return mPackages.valueAt(index);
    }

    public PackageStats getPackageStats(String pkgName) {
        return mPackages.get(pkgName);
    }

    /** @hide */
    public PackageStats getOrCreatePackageStats(String pkgName) {
        PackageStats ps = mPackages.get(pkgName);
        if (ps == null) {
            ps = onNewPackageStats(pkgName);
            mPackages.put(pkgName, ps);
        }
        return ps;
    }

    public int getConfigurationStatsCount() {
        return mConfigurations.size();
    }

    public ConfigurationStats getConfigurationStatsAt(int index) {
        return mConfigurations.valueAt(index);
    }

    public ConfigurationStats getConfigurationStats(Configuration config) {
        return mConfigurations.get(config);
    }

    /** @hide */
    public ConfigurationStats getOrCreateConfigurationStats(Configuration config) {
        ConfigurationStats cs = mConfigurations.get(config);
        if (cs == null) {
            cs = onNewConfigurationStats(config);
            mConfigurations.put(config, cs);
        }
        return cs;
    }

    /** @hide */
    public void clearUsageTimes() {
        for (int i=mPackages.size()-1; i>=0; i--) {
            if (mPackages.valueAt(i).clearUsageTimes()) {
                mPackages.removeAt(i);
            }
        }
        for (int i=mConfigurations.size()-1; i>=0; i--) {
            if (mConfigurations.valueAt(i).clearUsageTimes()) {
                mConfigurations.removeAt(i);
            }
        }
    }

    /** @hide */
    public PackageStats onNewPackageStats(String pkgName) {
        return new PackageStats(pkgName);
    }

    /** @hide */
    public PackageStats onNewPackageStats(Parcel source) {
        return new PackageStats(source);
    }

    /** @hide */
    public ConfigurationStats onNewConfigurationStats(Configuration config) {
        return new ConfigurationStats(config);
    }

    /** @hide */
    public ConfigurationStats onNewConfigurationStats(Parcel source) {
        return new ConfigurationStats(source);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        writeToParcelInner(dest, parcelableFlags, false);
    }

    /** @hide */
    public void writeExtendedToParcel(Parcel dest, int parcelableFlags) {
        writeToParcelInner(dest, parcelableFlags, true);
    }

    private void writeToParcelInner(Parcel dest, int parcelableFlags, boolean extended) {
        final long elapsedRealtime = SystemClock.elapsedRealtime();

        int N = mPackages.size();
        dest.writeInt(N);
        for (int i=0; i<N; i++) {
            PackageStats ps = mPackages.valueAt(i);
            ps.writeToParcel(dest, parcelableFlags, elapsedRealtime);
            if (extended) {
                ps.writeExtendedToParcel(dest, parcelableFlags);
            }
        }
        N = mConfigurations.size();
        dest.writeInt(N);
        for (int i=0; i<N; i++) {
            ConfigurationStats cs = mConfigurations.valueAt(i);
            cs.writeToParcel(dest, parcelableFlags, elapsedRealtime);
            if (extended) {
                cs.writeExtendedToParcel(dest, parcelableFlags);
            }
        }
    }

    public static final Parcelable.Creator<UsageStats> CREATOR
            = new Parcelable.Creator<UsageStats>() {
        public UsageStats createFromParcel(Parcel in) {
            return new UsageStats(in, false);
        }

        public UsageStats[] newArray(int size) {
            return new UsageStats[size];
        }
    };
}
