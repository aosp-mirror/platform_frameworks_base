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
import android.annotation.SuppressLint;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains a snapshot of battery attribution data, on a per-subsystem and per-UID basis.
 *
 * @hide
 */
public final class BatteryUsageStats implements Parcelable {
    private final double mConsumedPower;
    private final int mDischargePercentage;
    private final ArrayList<UidBatteryConsumer> mUidBatteryConsumers;
    private final ArrayList<SystemBatteryConsumer> mSystemBatteryConsumers;
    private final ArrayList<UserBatteryConsumer> mUserBatteryConsumers;

    private BatteryUsageStats(@NonNull Builder builder) {
        mConsumedPower = builder.mConsumedPower;
        mDischargePercentage = builder.mDischargePercentage;

        int uidBatteryConsumerCount = builder.mUidBatteryConsumerBuilders.size();
        mUidBatteryConsumers = new ArrayList<>(uidBatteryConsumerCount);
        for (int i = 0; i < uidBatteryConsumerCount; i++) {
            UidBatteryConsumer.Builder uidBatteryConsumerBuilder =
                    builder.mUidBatteryConsumerBuilders.valueAt(i);
            if (!uidBatteryConsumerBuilder.isExcludedFromBatteryUsageStats()) {
                mUidBatteryConsumers.add(uidBatteryConsumerBuilder.build());
            }
        }

        int systemBatteryConsumerCount = builder.mSystemBatteryConsumerBuilders.size();
        mSystemBatteryConsumers = new ArrayList<>(systemBatteryConsumerCount);
        for (int i = 0; i < systemBatteryConsumerCount; i++) {
            mSystemBatteryConsumers.add(builder.mSystemBatteryConsumerBuilders.valueAt(i).build());
        }

        int userBatteryConsumerCount = builder.mUserBatteryConsumerBuilders.size();
        mUserBatteryConsumers = new ArrayList<>(userBatteryConsumerCount);
        for (int i = 0; i < userBatteryConsumerCount; i++) {
            mUserBatteryConsumers.add(builder.mUserBatteryConsumerBuilders.valueAt(i).build());
        }
    }

    /**
     * Portion of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), as percentage of the full charge in the range [0:100]
     */
    public int getDischargePercentage() {
        return mDischargePercentage;
    }

    /**
     * Total amount of battery charge drained since BatteryStats reset (e.g. due to being fully
     * charged), in mAh
     */
    public double getConsumedPower() {
        return mConsumedPower;
    }

    @NonNull
    public List<UidBatteryConsumer> getUidBatteryConsumers() {
        return mUidBatteryConsumers;
    }

    @NonNull
    public List<SystemBatteryConsumer> getSystemBatteryConsumers() {
        return mSystemBatteryConsumers;
    }

    @NonNull
    public List<UserBatteryConsumer> getUserBatteryConsumers() {
        return mUserBatteryConsumers;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private BatteryUsageStats(@NonNull Parcel source) {
        mUidBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mUidBatteryConsumers, getClass().getClassLoader());
        mSystemBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mSystemBatteryConsumers, getClass().getClassLoader());
        mUserBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mUserBatteryConsumers, getClass().getClassLoader());
        mConsumedPower = source.readDouble();
        mDischargePercentage = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelableList(mUidBatteryConsumers, flags);
        dest.writeParcelableList(mSystemBatteryConsumers, flags);
        dest.writeParcelableList(mUserBatteryConsumers, flags);
        dest.writeDouble(mConsumedPower);
        dest.writeInt(mDischargePercentage);
    }

    @NonNull
    public static final Creator<BatteryUsageStats> CREATOR = new Creator<BatteryUsageStats>() {
        public BatteryUsageStats createFromParcel(@NonNull Parcel source) {
            return new BatteryUsageStats(source);
        }

        public BatteryUsageStats[] newArray(int size) {
            return new BatteryUsageStats[size];
        }
    };

    /**
     * Builder for BatteryUsageStats.
     */
    public static final class Builder {
        private final int mCustomPowerComponentCount;
        private final int mCustomTimeComponentCount;
        private double mConsumedPower;
        private int mDischargePercentage;
        private final SparseArray<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<SystemBatteryConsumer.Builder> mSystemBatteryConsumerBuilders =
                new SparseArray<>();
        private final SparseArray<UserBatteryConsumer.Builder> mUserBatteryConsumerBuilders =
                new SparseArray<>();

        public Builder(int customPowerComponentCount, int customTimeComponentCount) {
            mCustomPowerComponentCount = customPowerComponentCount;
            mCustomTimeComponentCount = customTimeComponentCount;
        }

        /**
         * Constructs a read-only object using the Builder values.
         */
        @NonNull
        public BatteryUsageStats build() {
            return new BatteryUsageStats(this);
        }

        /**
         * Sets the battery discharge amount since BatteryStats reset as percentage of the full
         * charge.
         */
        @SuppressLint("PercentageInt") // See b/174188159
        @NonNull
        public Builder setDischargePercentage(int dischargePercentage) {
            mDischargePercentage = dischargePercentage;
            return this;
        }

        /**
         * Sets the battery discharge amount since BatteryStats reset, in mAh.
         */
        @NonNull
        public Builder setConsumedPower(double consumedPower) {
            mConsumedPower = consumedPower;
            return this;
        }

        /**
         * Creates or returns a exiting UidBatteryConsumer, which represents battery attribution
         * data for an individual UID.
         */
        @NonNull
        public UidBatteryConsumer.Builder getOrCreateUidBatteryConsumerBuilder(
                @NonNull BatteryStats.Uid batteryStatsUid) {
            int uid = batteryStatsUid.getUid();
            UidBatteryConsumer.Builder builder = mUidBatteryConsumerBuilders.get(uid);
            if (builder == null) {
                builder = new UidBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, batteryStatsUid);
                mUidBatteryConsumerBuilders.put(uid, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a exiting SystemBatteryConsumer, which represents battery attribution
         * data for a specific drain type.
         */
        @NonNull
        public SystemBatteryConsumer.Builder getOrCreateSystemBatteryConsumerBuilder(
                @SystemBatteryConsumer.DrainType int drainType) {
            SystemBatteryConsumer.Builder builder = mSystemBatteryConsumerBuilders.get(drainType);
            if (builder == null) {
                builder = new SystemBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, drainType);
                mSystemBatteryConsumerBuilders.put(drainType, builder);
            }
            return builder;
        }

        /**
         * Creates or returns a exiting UserBatteryConsumer, which represents battery attribution
         * data for an individual {@link UserHandle}.
         */
        @NonNull
        public UserBatteryConsumer.Builder getOrCreateUserBatteryConsumerBuilder(int userId) {
            UserBatteryConsumer.Builder builder = mUserBatteryConsumerBuilders.get(userId);
            if (builder == null) {
                builder = new UserBatteryConsumer.Builder(mCustomPowerComponentCount,
                        mCustomTimeComponentCount, userId);
                mUserBatteryConsumerBuilders.put(userId, builder);
            }
            return builder;
        }

        @NonNull
        public SparseArray<UidBatteryConsumer.Builder> getUidBatteryConsumerBuilders() {
            return mUidBatteryConsumerBuilders;
        }
    }
}
