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

    private BatteryUsageStats(@NonNull Builder builder) {
        mConsumedPower = builder.mConsumedPower;
        mDischargePercentage = builder.mDischargePercentage;
        final int uidBatteryConsumerCount = builder.mUidBatteryConsumerBuilders.size();
        mUidBatteryConsumers = new ArrayList<>(uidBatteryConsumerCount);
        for (int i = 0; i < uidBatteryConsumerCount; i++) {
            mUidBatteryConsumers.add(builder.mUidBatteryConsumerBuilders.get(i).build());
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

    @Override
    public int describeContents() {
        return 0;
    }

    private BatteryUsageStats(@NonNull Parcel source) {
        mUidBatteryConsumers = new ArrayList<>();
        source.readParcelableList(mUidBatteryConsumers, getClass().getClassLoader());
        mConsumedPower = source.readDouble();
        mDischargePercentage = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelableList(mUidBatteryConsumers, flags);
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
        private double mConsumedPower;
        private int mDischargePercentage;
        private final ArrayList<UidBatteryConsumer.Builder> mUidBatteryConsumerBuilders =
                new ArrayList<>();

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
         * Adds a UidBatteryConsumer, which represents battery attribution data for an
         * individual UID.
         */
        @NonNull
        public Builder addUidBatteryConsumerBuilder(
                @NonNull UidBatteryConsumer.Builder uidBatteryConsumer) {
            mUidBatteryConsumerBuilders.add(uidBatteryConsumer);
            return this;
        }

        @NonNull
        public List<UidBatteryConsumer.Builder> getUidBatteryConsumerBuilders() {
            return mUidBatteryConsumerBuilders;
        }
    }
}
