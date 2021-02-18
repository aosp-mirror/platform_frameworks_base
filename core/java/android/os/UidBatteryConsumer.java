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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Contains power consumption data attributed to a specific UID.
 *
 * @hide
 */
public final class UidBatteryConsumer extends BatteryConsumer implements Parcelable {

    private final int mUid;
    @Nullable
    private final String mPackageWithHighestDrain;

    public int getUid() {
        return mUid;
    }

    @Nullable
    public String getPackageWithHighestDrain() {
        return mPackageWithHighestDrain;
    }

    private UidBatteryConsumer(@NonNull Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mUid = builder.mUid;
        mPackageWithHighestDrain = builder.mPackageWithHighestDrain;
    }

    private UidBatteryConsumer(@NonNull Parcel source) {
        super(new PowerComponents(source));
        mUid = source.readInt();
        mPackageWithHighestDrain = source.readString();
    }

    /**
     * Writes the contents into a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mUid);
        dest.writeString(mPackageWithHighestDrain);
    }

    @NonNull
    public static final Creator<UidBatteryConsumer> CREATOR = new Creator<UidBatteryConsumer>() {
        public UidBatteryConsumer createFromParcel(@NonNull Parcel source) {
            return new UidBatteryConsumer(source);
        }

        public UidBatteryConsumer[] newArray(int size) {
            return new UidBatteryConsumer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder for UidBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        private final BatteryStats.Uid mBatteryStatsUid;
        private final int mUid;
        private String mPackageWithHighestDrain;
        private boolean mExcludeFromBatteryUsageStats;

        public Builder(int customPowerComponentCount, int customTimeComponentCount,
                @NonNull BatteryStats.Uid batteryStatsUid) {
            super(customPowerComponentCount, customTimeComponentCount);
            mBatteryStatsUid = batteryStatsUid;
            mUid = batteryStatsUid.getUid();
        }

        @NonNull
        public BatteryStats.Uid getBatteryStatsUid() {
            return mBatteryStatsUid;
        }

        public int getUid() {
            return mUid;
        }

        /**
         * Sets the name of the package owned by this UID that consumed the highest amount
         * of power since BatteryStats reset.
         */
        @NonNull
        public Builder setPackageWithHighestDrain(@Nullable String packageName) {
            mPackageWithHighestDrain = packageName;
            return this;
        }

        /**
         * Marks the UidBatteryConsumer for exclusion from the result set.
         */
        public Builder excludeFromBatteryUsageStats() {
            mExcludeFromBatteryUsageStats = true;
            return this;
        }

        /**
         * Returns true if this UidBatteryConsumer must be excluded from the
         * BatteryUsageStats.
         */
        public boolean isExcludedFromBatteryUsageStats() {
            return mExcludeFromBatteryUsageStats;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public UidBatteryConsumer build() {
            return new UidBatteryConsumer(this);
        }
    }
}
