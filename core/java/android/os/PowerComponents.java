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
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.proto.ProtoOutputStream;

import com.android.internal.os.PowerCalculator;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Contains details of battery attribution data broken down to individual power drain types
 * such as CPU, RAM, GPU etc.
 *
 * @hide
 */
class PowerComponents {
    private final BatteryConsumer.BatteryConsumerData mData;

    PowerComponents(@NonNull Builder builder) {
        mData = builder.mData;
    }

    PowerComponents(BatteryConsumer.BatteryConsumerData data) {
        mData = data;
    }

    /**
     * Total power consumed by this consumer, in mAh.
     */
    public double getConsumedPower() {
        return mData.getDouble(mData.layout.consumedPowerColumn);
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
        return mData.getDouble(mData.layout.firstConsumedPowerColumn + componentId);
    }

    /**
     * Returns the amount of drain attributed to the specified custom drain type.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPowerForCustomComponent(int componentId) {
        final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
        if (index >= 0 && index < mData.layout.customPowerComponentCount) {
            return mData.getDouble(mData.layout.firstCustomConsumedPowerColumn + index);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
    }

    public String getCustomPowerComponentName(int componentId) {
        final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
        if (index >= 0 && index < mData.layout.customPowerComponentCount) {
            try {
                return mData.layout.customPowerComponentNames[index];
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
    int getPowerModel(@BatteryConsumer.PowerComponent int componentId) {
        if (!mData.layout.powerModelsIncluded) {
            throw new IllegalStateException(
                    "Power model IDs were not requested in the BatteryUsageStatsQuery");
        }
        return mData.getInt(mData.layout.firstPowerModelColumn + componentId);
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
        return mData.getLong(mData.layout.firstUsageDurationColumn + componentId);
    }

    /**
     * Returns the amount of usage time attributed to the specified custom component.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationForCustomComponentMillis(int componentId) {
        final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
        if (index >= 0 && index < mData.layout.customPowerComponentCount) {
            return mData.getLong(mData.layout.firstCustomUsageDurationColumn + index);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported custom power component ID: " + componentId);
        }
    }

    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        String separator = "";
        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final double componentPower = getConsumedPower(componentId);
            if (skipEmptyComponents && componentPower == 0) {
                continue;
            }
            pw.print(separator);
            separator = " ";
            pw.print(BatteryConsumer.powerComponentIdToString(componentId));
            pw.print("=");
            PowerCalculator.printPowerMah(pw, componentPower);
        }

        final int customComponentCount = mData.layout.customPowerComponentCount;
        for (int customComponentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
                customComponentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                        + customComponentCount;
                customComponentId++) {
            final double customComponentPower =
                    getConsumedPowerForCustomComponent(customComponentId);
            if (skipEmptyComponents && customComponentPower == 0) {
                continue;
            }
            pw.print(separator);
            separator = " ";
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

        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final long powerDeciCoulombs = convertMahToDeciCoulombs(getConsumedPower(componentId));
            final long durationMs = getUsageDurationMillis(componentId);

            if (powerDeciCoulombs == 0 && durationMs == 0) {
                // No interesting data. Make sure not to even write the COMPONENT int.
                continue;
            }

            interestingData = true;
            if (proto == null) {
                // We're just asked whether there is data, not to actually write it. And there is.
                return true;
            }

            writePowerComponent(proto, componentId, powerDeciCoulombs, durationMs);
        }
        for (int idx = 0; idx < mData.layout.customPowerComponentCount; idx++) {
            final int componentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + idx;
            final long powerDeciCoulombs =
                    convertMahToDeciCoulombs(getConsumedPowerForCustomComponent(componentId));
            final long durationMs = getUsageDurationForCustomComponentMillis(componentId);

            if (powerDeciCoulombs == 0 && durationMs == 0) {
                // No interesting data. Make sure not to even write the COMPONENT int.
                continue;
            }

            interestingData = true;
            if (proto == null) {
                // We're just asked whether there is data, not to actually write it. And there is.
                return true;
            }

            writePowerComponent(proto, componentId, powerDeciCoulombs, durationMs);
        }
        return interestingData;
    }

    private void writePowerComponent(ProtoOutputStream proto, int componentId,
            long powerDeciCoulombs, long durationMs) {
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

    void writeToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(null, BatteryUsageStats.XML_TAG_POWER_COMPONENTS);
        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            final double powerMah = getConsumedPower(componentId);
            final long durationMs = getUsageDurationMillis(componentId);
            if (powerMah == 0 && durationMs == 0) {
                continue;
            }

            serializer.startTag(null, BatteryUsageStats.XML_TAG_COMPONENT);
            serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_ID, componentId);
            if (powerMah != 0) {
                serializer.attributeDouble(null, BatteryUsageStats.XML_ATTR_POWER, powerMah);
            }
            if (durationMs != 0) {
                serializer.attributeLong(null, BatteryUsageStats.XML_ATTR_DURATION, durationMs);
            }
            if (mData.layout.powerModelsIncluded) {
                serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_MODEL,
                        getPowerModel(componentId));
            }
            serializer.endTag(null, BatteryUsageStats.XML_TAG_COMPONENT);
        }

        final int customComponentEnd = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID
                + mData.layout.customPowerComponentCount;
        for (int componentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
                componentId < customComponentEnd;
                componentId++) {
            final double powerMah = getConsumedPowerForCustomComponent(componentId);
            final long durationMs = getUsageDurationForCustomComponentMillis(componentId);
            if (powerMah == 0 && durationMs == 0) {
                continue;
            }

            serializer.startTag(null, BatteryUsageStats.XML_TAG_CUSTOM_COMPONENT);
            serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_ID, componentId);
            if (powerMah != 0) {
                serializer.attributeDouble(null, BatteryUsageStats.XML_ATTR_POWER, powerMah);
            }
            if (durationMs != 0) {
                serializer.attributeLong(null, BatteryUsageStats.XML_ATTR_DURATION, durationMs);
            }
            serializer.endTag(null, BatteryUsageStats.XML_TAG_CUSTOM_COMPONENT);
        }

        serializer.endTag(null, BatteryUsageStats.XML_TAG_POWER_COMPONENTS);
    }


    static void parseXml(TypedXmlPullParser parser, PowerComponents.Builder builder)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        if (eventType != XmlPullParser.START_TAG || !parser.getName().equals(
                BatteryUsageStats.XML_TAG_POWER_COMPONENTS)) {
            throw new XmlPullParserException("Invalid XML parser state");
        }

        while (!(eventType == XmlPullParser.END_TAG && parser.getName().equals(
                BatteryUsageStats.XML_TAG_POWER_COMPONENTS))
                && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case BatteryUsageStats.XML_TAG_COMPONENT: {
                        int componentId = -1;
                        double powerMah = 0;
                        long durationMs = 0;
                        int model = BatteryConsumer.POWER_MODEL_UNDEFINED;
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            switch (parser.getAttributeName(i)) {
                                case BatteryUsageStats.XML_ATTR_ID:
                                    componentId = parser.getAttributeInt(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_POWER:
                                    powerMah = parser.getAttributeDouble(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_DURATION:
                                    durationMs = parser.getAttributeLong(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_MODEL:
                                    model = parser.getAttributeInt(i);
                                    break;
                            }
                        }
                        builder.setConsumedPower(componentId, powerMah, model);
                        builder.setUsageDurationMillis(componentId, durationMs);
                        break;
                    }
                    case BatteryUsageStats.XML_TAG_CUSTOM_COMPONENT: {
                        int componentId = -1;
                        double powerMah = 0;
                        long durationMs = 0;
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            switch (parser.getAttributeName(i)) {
                                case BatteryUsageStats.XML_ATTR_ID:
                                    componentId = parser.getAttributeInt(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_POWER:
                                    powerMah = parser.getAttributeDouble(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_DURATION:
                                    durationMs = parser.getAttributeLong(i);
                                    break;
                            }
                        }
                        builder.setConsumedPowerForCustomComponent(componentId, powerMah);
                        builder.setUsageDurationForCustomComponentMillis(componentId, durationMs);
                        break;
                    }
                }
            }
            eventType = parser.next();
        }
    }

    /**
     * Builder for PowerComponents.
     */
    static final class Builder {
        private static final byte POWER_MODEL_UNINITIALIZED = -1;

        private final BatteryConsumer.BatteryConsumerData mData;

        Builder(BatteryConsumer.BatteryConsumerData data) {
            mData = data;
            if (mData.layout.powerModelsIncluded) {
                for (int i = 0; i < BatteryConsumer.POWER_COMPONENT_COUNT; i++) {
                    mData.putLong(mData.layout.firstPowerModelColumn + i,
                            POWER_MODEL_UNINITIALIZED);
                }
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
            mData.putDouble(mData.layout.firstConsumedPowerColumn + componentId, componentPower);
            if (mData.layout.powerModelsIncluded) {
                mData.putLong(mData.layout.firstPowerModelColumn + componentId, powerModel);
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
            final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
            if (index < 0 || index >= mData.layout.customPowerComponentCount) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            mData.putDouble(mData.layout.firstCustomConsumedPowerColumn + index, componentPower);
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
            mData.putLong(mData.layout.firstUsageDurationColumn + componentId,
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
            final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
            if (index < 0 || index >= mData.layout.customPowerComponentCount) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }

            mData.putLong(mData.layout.firstCustomUsageDurationColumn + index,
                    componentUsageDurationMillis);
            return this;
        }

        public void addPowerAndDuration(PowerComponents.Builder other) {
            addPowerAndDuration(other.mData);
        }

        public void addPowerAndDuration(PowerComponents other) {
            addPowerAndDuration(other.mData);
        }

        private void addPowerAndDuration(BatteryConsumer.BatteryConsumerData otherData) {
            if (mData.layout.customPowerComponentCount
                    != otherData.layout.customPowerComponentCount) {
                throw new IllegalArgumentException(
                        "Number of power components does not match: "
                                + otherData.layout.customPowerComponentCount
                                + ", expected: " + mData.layout.customPowerComponentCount);
            }

            for (int i = BatteryConsumer.POWER_COMPONENT_COUNT - 1; i >= 0; i--) {
                final int powerColumnIndex = mData.layout.firstConsumedPowerColumn + i;
                mData.putDouble(powerColumnIndex,
                        mData.getDouble(powerColumnIndex)
                                + otherData.getDouble(powerColumnIndex));

                final int durationColumnIndex = mData.layout.firstUsageDurationColumn + i;
                mData.putLong(durationColumnIndex,
                        mData.getLong(durationColumnIndex)
                                + otherData.getLong(durationColumnIndex));
            }

            for (int i = mData.layout.customPowerComponentCount - 1; i >= 0; i--) {
                final int powerColumnIndex = mData.layout.firstCustomConsumedPowerColumn + i;
                mData.putDouble(powerColumnIndex,
                        mData.getDouble(powerColumnIndex) + otherData.getDouble(powerColumnIndex));

                final int usageColumnIndex = mData.layout.firstCustomUsageDurationColumn + i;
                mData.putLong(usageColumnIndex,
                        mData.getLong(usageColumnIndex) + otherData.getLong(usageColumnIndex)
                );
            }

            if (mData.layout.powerModelsIncluded && otherData.layout.powerModelsIncluded) {
                for (int i = BatteryConsumer.POWER_COMPONENT_COUNT - 1; i >= 0; i--) {
                    final int columnIndex = mData.layout.firstPowerModelColumn + i;
                    int powerModel = mData.getInt(columnIndex);
                    int otherPowerModel = otherData.getInt(columnIndex);
                    if (powerModel == POWER_MODEL_UNINITIALIZED) {
                        mData.putLong(columnIndex, otherPowerModel);
                    } else if (powerModel != otherPowerModel
                            && otherPowerModel != POWER_MODEL_UNINITIALIZED) {
                        mData.putLong(columnIndex, BatteryConsumer.POWER_MODEL_UNDEFINED);
                    }
                }
            }
        }

        /**
         * Returns the total power accumulated by this builder so far. It may change
         * by the time the {@code build()} method is called.
         */
        public double getTotalPower() {
            double totalPowerMah = 0;
            for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                    componentId++) {
                totalPowerMah +=
                        mData.getDouble(mData.layout.firstConsumedPowerColumn + componentId);
            }
            for (int i = 0; i < mData.layout.customPowerComponentCount; i++) {
                totalPowerMah += mData.getDouble(mData.layout.firstCustomConsumedPowerColumn + i);
            }
            return totalPowerMah;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public PowerComponents build() {
            mData.putDouble(mData.layout.consumedPowerColumn, getTotalPower());

            if (mData.layout.powerModelsIncluded) {
                for (int i = BatteryConsumer.POWER_COMPONENT_COUNT - 1; i >= 0; i--) {
                    final int powerModel = mData.getInt(mData.layout.firstPowerModelColumn + i);
                    if (powerModel == POWER_MODEL_UNINITIALIZED) {
                        mData.putInt(mData.layout.firstPowerModelColumn + i,
                                BatteryConsumer.POWER_MODEL_UNDEFINED);
                    }
                }
            }

            return new PowerComponents(this);
        }
    }
}
