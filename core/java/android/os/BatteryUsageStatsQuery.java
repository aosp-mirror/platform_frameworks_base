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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Query parameters for the {@link BatteryStatsManager#getBatteryUsageStats()} call.
 *
 * @hide
 */
public final class BatteryUsageStatsQuery implements Parcelable {

    @NonNull
    public static final BatteryUsageStatsQuery DEFAULT =
            new BatteryUsageStatsQuery.Builder().build();

    /**
     * Flags for the {@link BatteryStatsManager#getBatteryUsageStats()} method.
     * @hide
     */
    @IntDef(flag = true, prefix = { "FLAG_BATTERY_USAGE_STATS_" }, value = {
            FLAG_BATTERY_USAGE_STATS_INCLUDE_MODELED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BatteryUsageStatsFlags {}

    /**
     * Indicates that modeled battery usage stats should be returned along with
     * measured ones.
     *
     * @hide
     */
    public static final int FLAG_BATTERY_USAGE_STATS_INCLUDE_MODELED = 1;

    private final int mFlags;

    private BatteryUsageStatsQuery(@NonNull Builder builder) {
        mFlags = builder.mFlags;
    }

    @BatteryUsageStatsFlags
    public int getFlags() {
        return mFlags;
    }

    private BatteryUsageStatsQuery(Parcel in) {
        mFlags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlags);
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

        /**
         * Builds a read-only BatteryUsageStatsQuery object.
         */
        public BatteryUsageStatsQuery build() {
            return new BatteryUsageStatsQuery(this);
        }

        /**
         * Sets flags to modify the behavior of {@link BatteryStatsManager#getBatteryUsageStats}.
         */
        public Builder setFlags(@BatteryUsageStatsFlags int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Requests to include modeled battery usage stats along with measured ones.
         * Should only be used for testing and debugging.
         */
        public Builder includeModeled() {
            mFlags |= BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_MODELED;
            return this;
        }
    }
}
