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

    private final double mTotalPowerConsumed;
    private final double[] mPowerComponents;

    PowerComponents(@NonNull Builder builder) {
        mTotalPowerConsumed = builder.mTotalPowerConsumed;
        mPowerComponents = builder.mPowerComponents;
    }

    PowerComponents(@NonNull Parcel source) {
        mTotalPowerConsumed = source.readDouble();
        mPowerComponents = source.createDoubleArray();
    }

    /** Writes contents to Parcel */
    void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mTotalPowerConsumed);
        dest.writeDoubleArray(mPowerComponents);
    }

    /**
     * Total power consumed by this consumer, in mAh.
     */
    public double getTotalPowerConsumed() {
        return mTotalPowerConsumed;
    }

    /**
     * Returns the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
     *
     * @param componentId The ID of the power component, e.g.
     *                    {@link BatteryConsumer#POWER_COMPONENT_CPU}.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPower(@UidBatteryConsumer.PowerComponent int componentId) {
        if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "Unsupported power component ID: " + componentId);
        }
        try {
            return mPowerComponents[componentId];
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
        if (componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
        try {
            return mPowerComponents[
                    BatteryConsumer.POWER_COMPONENT_COUNT + componentId
                            - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
    }

    /**
     * Builder for PowerComponents.
     */
    static final class Builder {
        private double mTotalPowerConsumed;
        private final double[] mPowerComponents;

        Builder(int customPowerComponentCount) {
            mPowerComponents = new double[BatteryConsumer.POWER_COMPONENT_COUNT
                    + customPowerComponentCount];
        }

        /**
         * Sets the sum amount of power consumed since BatteryStats reset.
         */
        @NonNull
        public Builder setTotalPowerConsumed(double totalPowerConsumed) {
            mTotalPowerConsumed = totalPowerConsumed;
            return this;
        }

        /**
         * Sets the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
         *
         * @param componentId    The ID of the power component, e.g.
         *                       {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentPower Amount of consumed power in mAh.
         */
        @NonNull
        public Builder setConsumedPower(@UidBatteryConsumer.PowerComponent int componentId,
                double componentPower) {
            if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
            }
            try {
                mPowerComponents[componentId] = componentPower;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
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
            if (componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            try {
                mPowerComponents[BatteryConsumer.POWER_COMPONENT_COUNT + componentId
                        - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID] = componentPower;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            return this;
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
