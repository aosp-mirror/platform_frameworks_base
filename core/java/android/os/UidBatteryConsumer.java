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
    public static final class Builder {
        private final PowerComponents.Builder mPowerComponentsBuilder;
        private final BatteryStats.Uid mBatteryStatsUid;
        private final int mUid;
        private String mPackageWithHighestDrain;

        public Builder(int customPowerComponentCount, int customTimeComponentCount,
                BatteryStats.Uid batteryStatsUid) {
            mPowerComponentsBuilder = new PowerComponents.Builder(customPowerComponentCount,
                    customTimeComponentCount);
            mBatteryStatsUid = batteryStatsUid;
            mUid = batteryStatsUid.getUid();
        }

        public BatteryStats.Uid getBatteryStatsUid() {
            return mBatteryStatsUid;
        }

        public int getUid() {
            return mUid;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public UidBatteryConsumer build() {
            return new UidBatteryConsumer(this);
        }

        /**
         * Sets the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
         *
         * @param componentId    The ID of the power component, e.g.
         *                       {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentPower Amount of consumed power in mAh.
         */
        @NonNull
        public Builder setConsumedPower(@PowerComponent int componentId, double componentPower) {
            mPowerComponentsBuilder.setConsumedPower(componentId, componentPower);
            return this;
        }

        /**
         * Sets the amount of drain attributed to the specified custom drain type.
         *
         * @param componentId    The ID of the custom power component.
         * @param componentPower Amount of consumed power in mAh.
         */
        @NonNull
        public Builder setConsumedPowerForCustomComponent(int componentId, double componentPower) {
            mPowerComponentsBuilder.setConsumedPowerForCustomComponent(componentId, componentPower);
            return this;
        }

        /**
         * Sets the amount of power consumed since BatteryStats reset, mAh.
         */
        @NonNull
        public Builder setConsumedPower(double consumedPower) {
            mPowerComponentsBuilder.setTotalPowerConsumed(consumedPower);
            return this;
        }

        /**
         * Sets the amount of time used by the specified component, e.g. CPU, WiFi etc.
         *
         * @param componentId                  The ID of the time component, e.g.
         *                                     {@link UidBatteryConsumer#TIME_COMPONENT_CPU}.
         * @param componentUsageDurationMillis Amount of time in milliseconds.
         */
        @NonNull
        public Builder setUsageDurationMillis(@UidBatteryConsumer.TimeComponent int componentId,
                long componentUsageDurationMillis) {
            mPowerComponentsBuilder.setUsageDurationMillis(componentId,
                    componentUsageDurationMillis);
            return this;
        }

        /**
         * Sets the amount of time used by the specified custom component.
         *
         * @param componentId                  The ID of the custom power component.
         * @param componentUsageDurationMillis Amount of time in milliseconds.
         */
        @NonNull
        public Builder setUsageDurationForCustomComponentMillis(int componentId,
                long componentUsageDurationMillis) {
            mPowerComponentsBuilder.setUsageDurationForCustomComponentMillis(componentId,
                    componentUsageDurationMillis);
            return this;
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
    }
}
