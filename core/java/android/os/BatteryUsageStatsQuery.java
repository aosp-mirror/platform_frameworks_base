/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.IntArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Query parameters for the {@link BatteryStatsManager#getBatteryUsageStats()} call.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BatteryUsageStatsQuery implements Parcelable {

    @NonNull
    public static final BatteryUsageStatsQuery DEFAULT =
            new BatteryUsageStatsQuery.Builder().build();

    /**
     * Flags for the {@link BatteryStatsManager#getBatteryUsageStats()} method.
     * @hide
     */
    @IntDef(flag = true, prefix = { "FLAG_BATTERY_USAGE_STATS_" }, value = {
            FLAG_BATTERY_USAGE_STATS_POWER_PROFILE_MODEL,
            FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY,
            FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA,
            FLAG_BATTERY_USAGE_STATS_INCLUDE_VIRTUAL_UIDS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BatteryUsageStatsFlags {}

    /**
     * Indicates that power estimations should be based on the usage time and
     * average power constants provided in the PowerProfile, even if on-device power monitoring
     * is available.
     *
     * @hide
     */
    public static final int FLAG_BATTERY_USAGE_STATS_POWER_PROFILE_MODEL = 0x0001;

    /**
     * Indicates that battery history should be included in the BatteryUsageStats.
     * @hide
     */
    public static final int FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY = 0x0002;

    /**
     * Indicates that identifiers of power models used for computations of power
     * consumption should be included in the BatteryUsageStats.
     */
    public static final int FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS = 0x0004;

    public static final int FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA = 0x0008;

    public static final int FLAG_BATTERY_USAGE_STATS_INCLUDE_VIRTUAL_UIDS = 0x0010;

    private static final long DEFAULT_MAX_STATS_AGE_MS = 5 * 60 * 1000;

    private final int mFlags;
    @NonNull
    private final int[] mUserIds;
    private final long mMaxStatsAgeMs;
    private final long mFromTimestamp;
    private final long mToTimestamp;
    private final double mMinConsumedPowerThreshold;
    private final @BatteryConsumer.PowerComponent int[] mPowerComponents;

    private BatteryUsageStatsQuery(@NonNull Builder builder) {
        mFlags = builder.mFlags;
        mUserIds = builder.mUserIds != null ? builder.mUserIds.toArray()
                : new int[]{UserHandle.USER_ALL};
        mMaxStatsAgeMs = builder.mMaxStatsAgeMs;
        mMinConsumedPowerThreshold = builder.mMinConsumedPowerThreshold;
        mFromTimestamp = builder.mFromTimestamp;
        mToTimestamp = builder.mToTimestamp;
        mPowerComponents = builder.mPowerComponents;
    }

    @BatteryUsageStatsFlags
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns an array of users for which the attribution is requested.  It may
     * contain {@link UserHandle#USER_ALL} to indicate that the attribution
     * should be performed for all users. Battery consumed by users <b>not</b> included
     * in this array will be returned in the aggregated form as {@link UserBatteryConsumer}'s.
     */
    @NonNull
    public int[] getUserIds() {
        return mUserIds;
    }

    /**
     * Returns true if the power calculations must be based on the PowerProfile constants,
     * even if measured energy data is available.
     */
    public boolean shouldForceUsePowerProfileModel() {
        return (mFlags & FLAG_BATTERY_USAGE_STATS_POWER_PROFILE_MODEL) != 0;
    }

    public boolean isProcessStateDataNeeded() {
        return (mFlags & FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0;
    }

    /**
     * Returns the power components that should be estimated or null if all power components
     * are being requested.
     */
    public int[] getPowerComponents() {
        return mPowerComponents;
    }

    /**
     * Returns the client's tolerance for stale battery stats. The data is allowed to be up to
     * this many milliseconds out-of-date.
     */
    public long getMaxStatsAge() {
        return mMaxStatsAgeMs;
    }

    /**
     * Returns the minimal power component consumed power threshold. The small power consuming
     * components will be reported as zero.
     */
    public double getMinConsumedPowerThreshold() {
        return mMinConsumedPowerThreshold;
    }

    /**
     * Returns the exclusive lower bound of the stored snapshot timestamps that should be included
     * in the aggregation.  Ignored if {@link #getToTimestamp()} is zero.
     */
    public long getFromTimestamp() {
        return mFromTimestamp;
    }

    /**
     * Returns the inclusive upper bound of the stored snapshot timestamps that should
     * be included in the aggregation.  The default is to include only the current stats
     * accumulated since the latest battery reset.
     */
    public long getToTimestamp() {
        return mToTimestamp;
    }

    private BatteryUsageStatsQuery(Parcel in) {
        mFlags = in.readInt();
        mUserIds = new int[in.readInt()];
        in.readIntArray(mUserIds);
        mMaxStatsAgeMs = in.readLong();
        mMinConsumedPowerThreshold = in.readDouble();
        mFromTimestamp = in.readLong();
        mToTimestamp = in.readLong();
        mPowerComponents = in.createIntArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlags);
        dest.writeInt(mUserIds.length);
        dest.writeIntArray(mUserIds);
        dest.writeLong(mMaxStatsAgeMs);
        dest.writeDouble(mMinConsumedPowerThreshold);
        dest.writeLong(mFromTimestamp);
        dest.writeLong(mToTimestamp);
        dest.writeIntArray(mPowerComponents);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<BatteryUsageStatsQuery> CREATOR =
            new Creator<BatteryUsageStatsQuery>() {
                @Override
                public BatteryUsageStatsQuery createFromParcel(Parcel in) {
                    return new BatteryUsageStatsQuery(in);
                }

                @Override
                public BatteryUsageStatsQuery[] newArray(int size) {
                    return new BatteryUsageStatsQuery[size];
                }
            };

    /**
     * Builder for BatteryUsageStatsQuery.
     */
    public static final class Builder {
        private int mFlags;
        private IntArray mUserIds;
        private long mMaxStatsAgeMs = DEFAULT_MAX_STATS_AGE_MS;
        private long mFromTimestamp;
        private long mToTimestamp;
        private double mMinConsumedPowerThreshold = 0;
        private @BatteryConsumer.PowerComponent int[] mPowerComponents;

        /**
         * Builds a read-only BatteryUsageStatsQuery object.
         */
        public BatteryUsageStatsQuery build() {
            return new BatteryUsageStatsQuery(this);
        }

        /**
         * Add a user whose battery stats should be included in the battery usage stats.
         * {@link UserHandle#USER_ALL} will be used by default if no users are added explicitly.
         */
        public Builder addUser(@NonNull UserHandle userHandle) {
            if (mUserIds == null) {
                mUserIds = new IntArray(1);
            }
            mUserIds.add(userHandle.getIdentifier());
            return this;
        }

        /**
         * Requests that battery history be included in the BatteryUsageStats.
         */
        public Builder includeBatteryHistory() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY;
            return this;
        }

        /**
         * Requests that per-process state data be included in the BatteryUsageStats, if
         * available. Check {@link BatteryUsageStats#isProcessStateDataIncluded()} on the result
         * to see if the data is available.
         */
        public Builder includeProcessStateData() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA;
            return this;
        }

        /**
         * Requests to return modeled battery usage stats only, even if on-device
         * power monitoring data is available.
         *
         * Should only be used for testing and debugging.
         */
        public Builder powerProfileModeledOnly() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_POWER_PROFILE_MODEL;
            return this;
        }

        /**
         * Requests to return identifiers of models that were used for estimation
         * of power consumption.
         *
         * Should only be used for testing and debugging.
         */
        public Builder includePowerModels() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS;
            return this;
        }

        /**
         * Requests to return only statistics for the specified power components.  The default
         * is all power components.
         */
        public Builder includePowerComponents(
                @BatteryConsumer.PowerComponent int[] powerComponents) {
            mPowerComponents = powerComponents;
            return this;
        }

        /**
         * Requests to return attribution data for virtual UIDs such as
         * {@link Process#SDK_SANDBOX_VIRTUAL_UID}.
         */
        public Builder includeVirtualUids() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_VIRTUAL_UIDS;
            return this;
        }

        /**
         * Requests to aggregate stored snapshots between the two supplied timestamps
         * @param fromTimestamp Exclusive starting timestamp, as per System.currentTimeMillis()
         * @param toTimestamp Inclusive ending timestamp, as per System.currentTimeMillis()
         */
        // TODO(b/298459065): switch to monotonic clock
        public Builder aggregateSnapshots(long fromTimestamp, long toTimestamp) {
            mFromTimestamp = fromTimestamp;
            mToTimestamp = toTimestamp;
            return this;
        }

        /**
         * Set the client's tolerance for stale battery stats. The data may be up to
         * this many milliseconds out-of-date.
         */
        public Builder setMaxStatsAgeMs(long maxStatsAgeMs) {
            mMaxStatsAgeMs = maxStatsAgeMs;
            return this;
        }

        /**
         * Set the minimal power component consumed power threshold. The small power consuming
         * components will be reported as zero.
         */
        public Builder setMinConsumedPowerThreshold(double minConsumedPowerThreshold) {
            mMinConsumedPowerThreshold = minConsumedPowerThreshold;
            return this;
        }
    }
}
