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

import static android.os.BatteryConsumer.convertMahToDeciCoulombs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoOutputStream;

import com.android.internal.os.PowerCalculator;

import java.io.PrintWriter;

/**
 * Contains details of battery attribution data broken down to individual power drain types
 * such as CPU, RAM, GPU etc.
 *
 * @hide
 */
class PowerComponents {
    private static final int CUSTOM_POWER_COMPONENT_OFFSET = BatteryConsumer.POWER_COMPONENT_COUNT
            - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;

    private final double mConsumedPowerMah;
    private final double[] mPowerComponentsMah;
    private final long[] mUsageDurationsMs;
    private final int mCustomPowerComponentCount;
    private final byte[] mPowerModels;
    // Not written to Parcel and must be explicitly restored during the parent object's unparceling
    private String[] mCustomPowerComponentNames;

    PowerComponents(@NonNull Builder builder) {
        mCustomPowerComponentNames = builder.mCustomPowerComponentNames;
        mCustomPowerComponentCount = mCustomPowerComponentNames.length;
        mPowerComponentsMah = builder.mPowerComponentsMah;
        mUsageDurationsMs = builder.mUsageDurationsMs;
        mConsumedPowerMah = builder.getTotalPower();
        mPowerModels = builder.mPowerModels;
    }

    PowerComponents(@NonNull Parcel source) {
        mConsumedPowerMah = source.readDouble();
        mCustomPowerComponentCount = source.readInt();
        mPowerComponentsMah = source.createDoubleArray();
        mUsageDurationsMs = source.createLongArray();
        if (source.readBoolean()) {
            mPowerModels = new byte[BatteryConsumer.POWER_COMPONENT_COUNT];
            source.readByteArray(mPowerModels);
        } else {
            mPowerModels = null;
        }
    }

    /** Writes contents to Parcel */
    void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mConsumedPowerMah);
        dest.writeInt(mCustomPowerComponentCount);
        dest.writeDoubleArray(mPowerComponentsMah);
        dest.writeLongArray(mUsageDurationsMs);
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
    public double getConsumedPower() {
        return mConsumedPowerMah;
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

    void setCustomPowerComponentNames(String[] customPowerComponentNames) {
        mCustomPowerComponentNames = customPowerComponentNames;
    }

    public String getCustomPowerComponentName(int componentId) {
        if (componentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                && componentId < BatteryConsumer.LAST_CUSTOM_POWER_COMPONENT_ID) {
            try {
                return mCustomPowerComponentNames[componentId
                        - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID];
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
     * @param componentId The ID of the power component, e.g.
     *                    {@link BatteryConsumer#POWER_COMPONENT_CPU}.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationMillis(@BatteryConsumer.PowerComponent int componentId) {
        if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "Unsupported power component ID: " + componentId);
        }
        try {
            return mUsageDurationsMs[componentId];
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
        if (componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
        try {
            return mUsageDurationsMs[CUSTOM_POWER_COMPONENT_OFFSET + componentId];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
    }

    public int getCustomPowerComponentCount() {
        return mCustomPowerComponentCount;
    }

    /**
     * Returns the largest usage duration among all power components.
     */
    public long getMaxComponentUsageDurationMillis() {
        long max = 0;
        for (int i = mUsageDurationsMs.length - 1; i >= 0; i--) {
            if (mUsageDurationsMs[i] > max) {
                max = mUsageDurationsMs[i];
            }
        }
        return max;
    }

    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        String separator = "";
        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final double componentPower = getConsumedPower(componentId);
            if (skipEmptyComponents && componentPower == 0) {
                continue;
            }
            pw.print(separator); separator = " ";
            pw.print(BatteryConsumer.powerComponentIdToString(componentId));
            pw.print("=");
            PowerCalculator.printPowerMah(pw, componentPower);
        }

        final int customComponentCount = getCustomPowerComponentCount();
        for (int customComponentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
                customComponentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                        + customComponentCount;
                customComponentId++) {
            final double customComponentPower =
                    getConsumedPowerForCustomComponent(customComponentId);
            if (skipEmptyComponents && customComponentPower == 0) {
                continue;
            }
            pw.print(separator); separator = " ";
            pw.print(getCustomPowerComponentName(customComponentId));
            pw.print("=");
            PowerCalculator.printPowerMah(pw, customComponentPower);
        }
    }

    /** Returns whether there are any atoms.proto POWER_COMPONENTS data to write to a proto. */
    boolean hasStatsProtoData() {
        return writeStatsProtoImpl(null);
    }

    /** Writes all atoms.proto POWER_COMPONENTS for this PowerComponents to the given proto. */
    void writeStatsProto(@NonNull ProtoOutputStream proto) {
        writeStatsProtoImpl(proto);
    }

    /**
     * Returns whether there are any atoms.proto POWER_COMPONENTS data to write to a proto,
     * and writes it to the given proto if it is non-null.
     */
    private boolean writeStatsProtoImpl(@Nullable ProtoOutputStream proto) {
        boolean interestingData = false;

        for (int idx = 0; idx < mPowerComponentsMah.length; idx++) {
            final int componentId = idx < BatteryConsumer.POWER_COMPONENT_COUNT ?
                    idx : idx - CUSTOM_POWER_COMPONENT_OFFSET;
            final long powerDeciCoulombs = convertMahToDeciCoulombs(mPowerComponentsMah[idx]);
            final long durationMs = mUsageDurationsMs[idx];

            if (powerDeciCoulombs == 0 && durationMs == 0) {
                // No interesting data. Make sure not to even write the COMPONENT int.
                continue;
            }

            interestingData = true;
            if (proto == null) {
                // We're just asked whether there is data, not to actually write it. And there is.
                return true;
            }

            final long token =
                    proto.start(BatteryUsageStatsAtomsProto.BatteryConsumerData.POWER_COMPONENTS);
            proto.write(
                    BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage
                            .COMPONENT,
                    componentId);
            proto.write(
                    BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage
                            .POWER_DECI_COULOMBS,
                    powerDeciCoulombs);
            proto.write(
                    BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage
                            .DURATION_MILLIS,
                    durationMs);
            proto.end(token);
        }
        return interestingData;
    }

    /**
     * Builder for PowerComponents.
     */
    static final class Builder {
        private final double[] mPowerComponentsMah;
        private final String[] mCustomPowerComponentNames;
        private final long[] mUsageDurationsMs;
        private final byte[] mPowerModels;

        Builder(@NonNull String[] customPowerComponentNames, boolean includePowerModels) {
            mCustomPowerComponentNames = customPowerComponentNames;
            int powerComponentCount =
                    BatteryConsumer.POWER_COMPONENT_COUNT + mCustomPowerComponentNames.length;
            mPowerComponentsMah = new double[powerComponentCount];
            mUsageDurationsMs = new long[powerComponentCount];
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
         * @param componentId                  The ID of the power component, e.g.
         *                                     {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentUsageDurationMillis Amount of time in milliseconds.
         */
        @NonNull
        public Builder setUsageDurationMillis(@BatteryConsumer.PowerComponent int componentId,
                long componentUsageDurationMillis) {
            if (componentId >= BatteryConsumer.POWER_COMPONENT_COUNT) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
            }
            try {
                mUsageDurationsMs[componentId] = componentUsageDurationMillis;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported power component ID: " + componentId);
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
            if (componentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            try {
                mUsageDurationsMs[CUSTOM_POWER_COMPONENT_OFFSET + componentId] =
                        componentUsageDurationMillis;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            return this;
        }

        public void addPowerAndDuration(Builder other) {
            for (int i = mPowerComponentsMah.length - 1; i >= 0; i--) {
                mPowerComponentsMah[i] += other.mPowerComponentsMah[i];
            }
            for (int i = mUsageDurationsMs.length - 1; i >= 0; i--) {
                mUsageDurationsMs[i] += other.mUsageDurationsMs[i];
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
