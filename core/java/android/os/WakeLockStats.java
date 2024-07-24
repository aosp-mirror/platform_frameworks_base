/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of wake lock stats.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class WakeLockStats implements Parcelable {

    public static class WakeLockData {

        public static final WakeLockData EMPTY = new WakeLockData(
                /* timesAcquired= */ 0, /* totalTimeHeldMs= */ 0, /* timeHeldMs= */ 0);

        /** How many times this wakelock has been acquired. */
        public final int timesAcquired;

        /** Time in milliseconds that the lock has been held in total. */
        public final long totalTimeHeldMs;

        /**
         * Time in milliseconds that the lock has been held or 0 if not currently holding the lock
         */
        public final long timeHeldMs;

        public WakeLockData(int timesAcquired, long totalTimeHeldMs, long timeHeldMs) {
            this.timesAcquired = timesAcquired;
            this.totalTimeHeldMs = totalTimeHeldMs;
            this.timeHeldMs = timeHeldMs;
        }

        /**
         * Whether the fields are able to construct a valid wakelock.
         */
        public boolean isDataValid() {
            final boolean isDataReasonable = timesAcquired > 0
                    && totalTimeHeldMs > 0
                    && timeHeldMs >= 0
                    && totalTimeHeldMs >= timeHeldMs;
            return isEmpty() || isDataReasonable;
        }

        private boolean isEmpty() {
            return timesAcquired == 0 && totalTimeHeldMs == 0 && timeHeldMs == 0;
        }

        private WakeLockData(Parcel in) {
            timesAcquired = in.readInt();
            totalTimeHeldMs = in.readLong();
            timeHeldMs = in.readLong();
        }

        private void writeToParcel(Parcel out) {
            out.writeInt(timesAcquired);
            out.writeLong(totalTimeHeldMs);
            out.writeLong(timeHeldMs);
        }

        @Override
        public String toString() {
            return "WakeLockData{"
                + "timesAcquired="
                + timesAcquired
                + ", totalTimeHeldMs="
                + totalTimeHeldMs
                + ", timeHeldMs="
                + timeHeldMs
                + "}";
        }
    }

    /** @hide */
    public static class WakeLock {

        public static final String NAME_AGGREGATED = "wakelockstats_aggregated";

        public final int uid;
        @NonNull public final String name;
        public final boolean isAggregated;

        /** Wakelock data on both foreground and background. */
        @NonNull public final WakeLockData totalWakeLockData;

        /** Wakelock data on background. */
        @NonNull public final WakeLockData backgroundWakeLockData;

        public WakeLock(
                int uid,
                @NonNull String name,
                boolean isAggregated,
                @NonNull WakeLockData totalWakeLockData,
                @NonNull WakeLockData backgroundWakeLockData) {
            this.uid = uid;
            this.name = name;
            this.isAggregated = isAggregated;
            this.totalWakeLockData = totalWakeLockData;
            this.backgroundWakeLockData = backgroundWakeLockData;
        }

        /** Whether the combination of total and background wakelock data is invalid. */
        public static boolean isDataValid(
                WakeLockData totalWakeLockData, WakeLockData backgroundWakeLockData) {
            return totalWakeLockData.totalTimeHeldMs > 0
                && totalWakeLockData.isDataValid()
                && backgroundWakeLockData.isDataValid()
                && totalWakeLockData.timesAcquired >= backgroundWakeLockData.timesAcquired
                && totalWakeLockData.totalTimeHeldMs >= backgroundWakeLockData.totalTimeHeldMs
                && totalWakeLockData.timeHeldMs >= backgroundWakeLockData.timeHeldMs;
        }

        private WakeLock(Parcel in) {
            uid = in.readInt();
            name = in.readString();
            isAggregated = in.readBoolean();
            totalWakeLockData = new WakeLockData(in);
            backgroundWakeLockData = new WakeLockData(in);
        }

        private void writeToParcel(Parcel out) {
            out.writeInt(uid);
            out.writeString(name);
            out.writeBoolean(isAggregated);
            totalWakeLockData.writeToParcel(out);
            backgroundWakeLockData.writeToParcel(out);
        }

        @Override
        public String toString() {
            return "WakeLock{"
                + "uid="
                + uid
                + ", name='"
                + name
                + '\''
                + ", isAggregated="
                + isAggregated
                + ", totalWakeLockData="
                + totalWakeLockData
                + ", backgroundWakeLockData="
                + backgroundWakeLockData
                + '}';
        }
    }

    private final List<WakeLock> mWakeLocks;
    private final List<WakeLock> mAggregatedWakeLocks;

    /** @hide */
    public WakeLockStats(
            @NonNull List<WakeLock> wakeLocks, @NonNull List<WakeLock> aggregatedWakeLocks) {
        mWakeLocks = wakeLocks;
        mAggregatedWakeLocks = aggregatedWakeLocks;
    }

    @NonNull
    public List<WakeLock> getWakeLocks() {
        return mWakeLocks;
    }

    @NonNull
    public List<WakeLock> getAggregatedWakeLocks() {
        return mAggregatedWakeLocks;
    }

    private WakeLockStats(Parcel in) {
        final int wakelockSize = in.readInt();
        mWakeLocks = new ArrayList<>(wakelockSize);
        for (int i = 0; i < wakelockSize; i++) {
            mWakeLocks.add(new WakeLock(in));
        }
        final int aggregatedWakelockSize = in.readInt();
        mAggregatedWakeLocks = new ArrayList<>(aggregatedWakelockSize);
        for (int i = 0; i < aggregatedWakelockSize; i++) {
            mAggregatedWakeLocks.add(new WakeLock(in));
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        final int wakelockSize = mWakeLocks.size();
        out.writeInt(wakelockSize);
        for (int i = 0; i < wakelockSize; i++) {
            WakeLock stats = mWakeLocks.get(i);
            stats.writeToParcel(out);
        }
        final int aggregatedWakelockSize = mAggregatedWakeLocks.size();
        out.writeInt(aggregatedWakelockSize);
        for (int i = 0; i < aggregatedWakelockSize; i++) {
            WakeLock stats = mAggregatedWakeLocks.get(i);
            stats.writeToParcel(out);
        }
    }

    @NonNull
    public static final Creator<WakeLockStats> CREATOR =
            new Creator<WakeLockStats>() {
                public WakeLockStats createFromParcel(Parcel in) {
                    return new WakeLockStats(in);
                }

                public WakeLockStats[] newArray(int size) {
                    return new WakeLockStats[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "WakeLockStats{"
            + "mWakeLocks: ["
            + mWakeLocks
            + "]"
            + ", mAggregatedWakeLocks: ["
            + mAggregatedWakeLocks
            + "]"
            + '}';
    }
}
