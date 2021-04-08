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

/**
 * Contains details of battery attribution data broken down to individual power drain types
 * such as CPU, RAM, GPU etc.
 *
 * @hide
 */
class PowerComponents {
    private static final int CUSTOM_POWER_COMPONENT_OFFSET = BatteryConsumer.POWER_COMPONENT_COUNT
            - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
    public static final int CUSTOM_TIME_COMPONENT_OFFSET = BatteryConsumer.TIME_COMPONENT_COUNT
            - BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID;

    private final double mTotalConsumedPowerMah;
    private final double[] mPowerComponentsMah;
    private final long[] mTimeComponentsMs;
    private final int mCustomPowerComponentCount;
    private final byte[] mPowerModels;

    PowerComponents(@NonNull Builder builder) {
        mCustomPowerComponentCount = builder.mCustomPowerComponentCount;
        mPowerComponentsMah = builder.mPowerComponentsMah;
        mTimeComponentsMs = builder.mTimeComponentsMs;
        mTotalConsumedPowerMah = builder.getTotalPower();
        mPowerModels = builder.mPowerModels;
    }

    PowerComponents(@NonNull Parcel source) {
        mTotalConsumedPowerMah = source.readDouble();
        mCustomPowerComponentCount = source.readInt();
        mPowerComponentsMah = source.createDoubleArray();
        mTimeComponentsMs = source.createLongArray();
        if (source.readBoolean()) {
            mPowerModels = new byte[BatteryConsumer.POWER_COMPONENT_COUNT];
            source.readByteArray(mPowerModels);
        } else {
            mPowerModels = null;
        }
    }

    /** Writes contents to Parcel */
    void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mTotalConsumedPowerMah);
        dest.writeInt(mCustomPowerComponentCount);
        dest.writeDoubleArray(mPowerComponentsMah);
        dest.writeLongArray(mTimeComponentsMs);
        if (mPowerModels != null) {
            dest.writeBoolean(true);
            dest.writeByteArray(mPowerModels);
        } else {
            dest.writeBoolean(false);
        }
    }

    /**
     * Total power consumed by this consumer, in mAh.
     */
    public double getTotalConsumedPower() {
        return mTotalConsumedPowerMah;
    }

    /**
     * Returns the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
     *
     * @param componentId The ID of the power component, e.g.
     *                    {@link BatteryConsumer#POWER_COMPONENT_CPU}.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPower(@BatteryConsumer.PowerComponent int componentId) {
        if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "Unsupported power component ID: " + componentId);
        }
        try {
            return mPowerComponentsMah[componentId];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unsupported power component ID: " + componentId);
        }
    }

    /**
     * Returns the amount of drain attributed to the specified custom drain type.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPowerForCustomComponent(int componentId) {
        if (componentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                && componentId < BatteryConsumer.LAST_CUSTOM_POWER_COMPONENT_ID) {
            try {
                return mPowerComponentsMah[CUSTOM_POWER_COMPONENT_OFFSET + componentId];
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
    }

    @BatteryConsumer.PowerModel
    int getPowerModel(@BatteryConsumer.PowerComponent int component) {
        if (mPowerModels == null) {
            throw new IllegalStateException(
                    "Power model IDs were not requested in the BatteryUsageStatsQuery");
        }
        return mPowerModels[component];
    }

    /**
     * Returns the amount of time used by the specified component, e.g. CPU, WiFi etc.
     *
     * @param componentId The ID of the time component, e.g.
     *                    {@link BatteryConsumer#TIME_COMPONENT_CPU}.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationMillis(@BatteryConsumer.TimeComponent int componentId) {
        if (componentId >= BatteryConsumer.TIME_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "Unsupported time component ID: " + componentId);
        }
        try {
            return mTimeComponentsMs[componentId];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unsupported power component ID: " + componentId);
        }
    }

    /**
     * Returns the amount of usage time attributed to the specified custom component.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationForCustomComponentMillis(int componentId) {
        if (componentId < BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID) {
            throw new IllegalArgumentException(
                    "Unsupported custom time component ID: " + componentId);
        }
        try {
            return mTimeComponentsMs[CUSTOM_TIME_COMPONENT_OFFSET + componentId];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(
                    "Unsupported custom time component ID: " + componentId);
        }
    }

    /**
     * Builder for PowerComponents.
     */
    static final class Builder {
        private final double[] mPowerComponentsMah;
        private final int mCustomPowerComponentCount;
        private final long[] mTimeComponentsMs;
        private final byte[] mPowerModels;

        Builder(int customPowerComponentCount, int customTimeComponentCount,
                boolean includePowerModels) {
            mCustomPowerComponentCount = customPowerComponentCount;
            int powerComponentCount =
                    BatteryConsumer.POWER_COMPONENT_COUNT + customPowerComponentCount;
            mPowerComponentsMah = new double[powerComponentCount];
            mTimeComponentsMs =
                    new long[BatteryConsumer.TIME_COMPONENT_COUNT + customTimeComponentCount];
            if (includePowerModels) {
                mPowerModels = new byte[BatteryConsumer.POWER_COMPONENT_COUNT];
            } else {
                mPowerModels = null;
            }
        }

        /**
         * Sets the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
         *
         * @param componentId    The ID of the power component, e.g.
         *                       {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentPower Amount of consumed power in mAh.
         */
        @NonNull
        public Builder setConsumedPower(@BatteryConsumer.PowerComponent int componentId,
                double componentPower, @BatteryConsumer.PowerModel int powerModel) {
            if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
            }
            try {
                mPowerComponentsMah[componentId] = componentPower;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
            }
            if (mPowerModels != null) {
                mPowerModels[componentId] = (byte) powerModel;
            }
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
            if (componentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                    && componentId < BatteryConsumer.LAST_CUSTOM_POWER_COMPONENT_ID) {
                try {
                    mPowerComponentsMah[CUSTOM_POWER_COMPONENT_OFFSET + componentId] =
                            componentPower;
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IllegalArgumentException(
                            "Unsupported custom power component ID: " + componentId);
                }
            } else {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            return this;
        }

        /**
         * Sets the amount of time used by the specified component, e.g. CPU, WiFi etc.
         *
         * @param componentId                  The ID of the time component, e.g.
         *                                     {@link BatteryConsumer#TIME_COMPONENT_CPU}.
         * @param componentUsageDurationMillis Amount of time in milliseconds.
         */
        @NonNull
        public Builder setUsageDurationMillis(@BatteryConsumer.TimeComponent int componentId,
                long componentUsageDurationMillis) {
            if (componentId >= BatteryConsumer.TIME_COMPONENT_COUNT) {
                throw new IllegalArgumentException(
                        "Unsupported time component ID: " + componentId);
            }
            try {
                mTimeComponentsMs[componentId] = componentUsageDurationMillis;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported time component ID: " + componentId);
            }
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
            if (componentId < BatteryConsumer.FIRST_CUSTOM_TIME_COMPONENT_ID) {
                throw new IllegalArgumentException(
                        "Unsupported custom time component ID: " + componentId);
            }
            try {
                mTimeComponentsMs[CUSTOM_TIME_COMPONENT_OFFSET + componentId] =
                        componentUsageDurationMillis;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported custom time component ID: " + componentId);
            }
            return this;
        }

        public void addPowerAndDuration(Builder other) {
            for (int i = 0; i < mPowerComponentsMah.length; i++) {
                mPowerComponentsMah[i] += other.mPowerComponentsMah[i];
            }
            for (int i = 0; i < mTimeComponentsMs.length; i++) {
                mTimeComponentsMs[i] += other.mTimeComponentsMs[i];
            }
        }

        /**
         * Returns the total power accumulated by this builder so far. It may change
         * by the time the {@code build()} method is called.
         */
        public double getTotalPower() {
            double totalPowerMah = 0;
            for (int i = mPowerComponentsMah.length - 1; i >= 0; i--) {
                totalPowerMah += mPowerComponentsMah[i];
            }
            return totalPowerMah;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public PowerComponents build() {
            return new PowerComponents(this);
        }
    }
}
