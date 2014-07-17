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
import android.util.ArrayMap;

public final class UsageStats implements Parcelable {
    public static class Event implements Parcelable {
        /**
         * {@hide}
         */
        public static final Event[] EMPTY_EVENTS = new Event[0];

        public static final int NONE = 0;
        public static final int MOVE_TO_FOREGROUND = 1;
        public static final int MOVE_TO_BACKGROUND = 2;

        /**
         * {@hide}
         */
        public static final int END_OF_DAY = 3;

        /**
         * {@hide}
         */
        public static final int CONTINUE_PREVIOUS_DAY = 4;

        public Event() {}

        /**
         * {@hide}
         */
        public Event(String packageName, long timeStamp, int eventType) {
            this.packageName = packageName;
            this.timeStamp = timeStamp;
            this.eventType = eventType;
        }

        public String packageName;
        public long timeStamp;
        public int eventType;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(timeStamp);
            dest.writeInt(eventType);
            dest.writeString(packageName);
        }

        public static final Creator<Event> CREATOR = new Creator<Event>() {
            @Override
            public Event createFromParcel(Parcel source) {
                final long time = source.readLong();
                final int type = source.readInt();
                final String name = source.readString();
                return new Event(name, time, type);
            }

            @Override
            public Event[] newArray(int size) {
                return new Event[size];
            }
        };
    }

    /**
     * {@hide}
     */
    public static final UsageStats[] EMPTY_STATS = new UsageStats[0];

    /**
     * {@hide}
     */
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    public long mEndTimeStamp;

    /**
     * {@hide}
     */
    public long mLastTimeSaved;

    private ArrayMap<String, PackageUsageStats> mPackageStats = new ArrayMap<>();

    /**
     * Can be null
     * {@hide}
     */
    public TimeSparseArray<Event> mEvents;

    /**
     * {@hide}
     */
    public static UsageStats create(long beginTimeStamp, long endTimeStamp) {
        UsageStats stats = new UsageStats();
        stats.mBeginTimeStamp = beginTimeStamp;
        stats.mEndTimeStamp = endTimeStamp;
        return stats;
    }

    /**
     * {@hide}
     */
    public UsageStats() {
    }

    public UsageStats(UsageStats stats) {
        mBeginTimeStamp = stats.mBeginTimeStamp;
        mEndTimeStamp = stats.mEndTimeStamp;
        mLastTimeSaved = stats.mLastTimeSaved;

        final int pkgCount = stats.mPackageStats.size();
        for (int i = 0; i < pkgCount; i++) {
            PackageUsageStats pkgStats = stats.mPackageStats.valueAt(i);
            mPackageStats.append(stats.mPackageStats.keyAt(i), new PackageUsageStats(pkgStats));
        }

        final int eventCount = stats.mEvents == null ? 0 : stats.mEvents.size();
        if (eventCount > 0) {
            mEvents = new TimeSparseArray<>();
            for (int i = 0; i < eventCount; i++) {
                mEvents.append(stats.mEvents.keyAt(i), stats.mEvents.valueAt(i));
            }
        }
    }

    public long getFirstTimeStamp() {
        return mBeginTimeStamp;
    }

    public long getLastTimeStamp() {
        return mEndTimeStamp;
    }

    public int getPackageCount() {
        return mPackageStats.size();
    }

    public PackageUsageStats getPackage(int index) {
        return mPackageStats.valueAt(index);
    }

    public PackageUsageStats getPackage(String packageName) {
        return mPackageStats.get(packageName);
    }

    /**
     * {@hide}
     */
    public PackageUsageStats getOrCreatePackageUsageStats(String packageName) {
        PackageUsageStats pkgStats = mPackageStats.get(packageName);
        if (pkgStats == null) {
            pkgStats = new PackageUsageStats();
            pkgStats.mPackageName = packageName;
            mPackageStats.put(packageName, pkgStats);
        }
        return pkgStats;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mBeginTimeStamp);
        dest.writeLong(mEndTimeStamp);
        dest.writeLong(mLastTimeSaved);

        int size = mPackageStats.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            mPackageStats.valueAt(i).writeToParcel(dest, flags);
        }

        size = mEvents == null ? 0 : mEvents.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            mEvents.valueAt(i).writeToParcel(dest, flags);
        }
    }

    public static final Creator<UsageStats> CREATOR = new Creator<UsageStats>() {
        @Override
        public UsageStats createFromParcel(Parcel in) {
            UsageStats stats = new UsageStats();
            stats.mBeginTimeStamp = in.readLong();
            stats.mEndTimeStamp = in.readLong();
            stats.mLastTimeSaved = in.readLong();

            int size = in.readInt();
            stats.mPackageStats.ensureCapacity(size);
            for (int i = 0; i < size; i++) {
                final PackageUsageStats pkgStats = PackageUsageStats.CREATOR.createFromParcel(in);
                stats.mPackageStats.put(pkgStats.mPackageName, pkgStats);
            }

            size = in.readInt();
            if (size > 0) {
                stats.mEvents = new TimeSparseArray<>(size);
                for (int i = 0; i < size; i++) {
                    final Event event = Event.CREATOR.createFromParcel(in);
                    stats.mEvents.put(event.timeStamp, event);
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
