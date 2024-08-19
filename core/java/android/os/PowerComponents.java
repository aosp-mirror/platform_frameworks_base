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

import static android.os.BatteryConsumer.BatteryConsumerDataLayout.POWER_MODEL_NOT_INCLUDED;
import static android.os.BatteryConsumer.POWER_COMPONENT_ANY;
import static android.os.BatteryConsumer.POWER_STATE_ANY;
import static android.os.BatteryConsumer.POWER_STATE_UNSPECIFIED;
import static android.os.BatteryConsumer.PROCESS_STATE_ANY;
import static android.os.BatteryConsumer.PROCESS_STATE_UNSPECIFIED;
import static android.os.BatteryConsumer.SCREEN_STATE_ANY;
import static android.os.BatteryConsumer.SCREEN_STATE_UNSPECIFIED;
import static android.os.BatteryConsumer.convertMahToDeciCoulombs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoOutputStream;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
class PowerComponents {
    private final BatteryConsumer.BatteryConsumerData mData;

    PowerComponents(@NonNull Builder builder) {
        mData = builder.mData;
    }

    PowerComponents(BatteryConsumer.BatteryConsumerData data) {
        mData = data;
    }

    /**
     * Total power consumed by this consumer, aggregated over the specified dimensions, in mAh.
     */
    public double getConsumedPower(@NonNull BatteryConsumer.Dimensions dimensions) {
        return getConsumedPower(dimensions.powerComponent, dimensions.processState,
                dimensions.screenState, dimensions.powerState);
    }

    /**
     * Total power consumed by this consumer, aggregated over the specified dimensions, in mAh.
     */
    public double getConsumedPower(@BatteryConsumer.PowerComponent int powerComponent,
            @BatteryConsumer.ProcessState int processState,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState) {
        if (powerComponent == POWER_COMPONENT_ANY && processState == PROCESS_STATE_ANY
                && screenState == SCREEN_STATE_ANY && powerState == POWER_STATE_ANY) {
            return mData.getDouble(mData.layout.totalConsumedPowerColumnIndex);
        }

        if (powerComponent != POWER_COMPONENT_ANY
                && ((mData.layout.screenStateDataIncluded && screenState != SCREEN_STATE_ANY)
                || (mData.layout.powerStateDataIncluded && powerState != POWER_STATE_ANY))) {
            BatteryConsumer.Key key = mData.layout.getKey(powerComponent,
                    processState, screenState, powerState);
            if (key != null) {
                return mData.getDouble(key.mPowerColumnIndex);
            }
            return 0;
        }

        if (mData.layout.processStateDataIncluded || mData.layout.screenStateDataIncluded
                || mData.layout.powerStateDataIncluded) {
            double total = 0;
            for (BatteryConsumer.Key key : mData.layout.keys) {
                if (key.processState != PROCESS_STATE_UNSPECIFIED
                        && key.matches(powerComponent, processState, screenState, powerState)) {
                    total += mData.getDouble(key.mPowerColumnIndex);
                }
            }
            if (total != 0) {
                return total;
            }
        }

        BatteryConsumer.Key key = mData.layout.getKey(powerComponent, processState,
                SCREEN_STATE_UNSPECIFIED, POWER_STATE_UNSPECIFIED);
        if (key != null) {
            return mData.getDouble(key.mPowerColumnIndex);
        } else {
            return 0;
        }
    }

    /**
     * Total usage duration by this consumer, aggregated over the specified dimensions, in ms.
     */
    public long getUsageDurationMillis(@NonNull BatteryConsumer.Dimensions dimensions) {
        return getUsageDurationMillis(dimensions.powerComponent, dimensions.processState,
                dimensions.screenState, dimensions.powerState);
    }

    /**
     * Total usage duration by this consumer, aggregated over the specified dimensions, in ms.
     */
    public long getUsageDurationMillis(@BatteryConsumer.PowerComponent int powerComponent,
            @BatteryConsumer.ProcessState int processState,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState) {
        if ((mData.layout.screenStateDataIncluded && screenState != SCREEN_STATE_ANY)
                || (mData.layout.powerStateDataIncluded && powerState != POWER_STATE_ANY)) {
            BatteryConsumer.Key key = mData.layout.getKey(powerComponent,
                    processState, screenState, powerState);
            if (key != null) {
                return mData.getLong(key.mDurationColumnIndex);
            }
            return 0;
        }

        if (mData.layout.screenStateDataIncluded || mData.layout.powerStateDataIncluded) {
            long total = 0;
            for (BatteryConsumer.Key key : mData.layout.keys) {
                if (key.processState != PROCESS_STATE_UNSPECIFIED
                        && key.matches(powerComponent, processState, screenState, powerState)) {
                    total += mData.getLong(key.mDurationColumnIndex);
                }
            }
            if (total != 0) {
                return total;
            }
        }

        BatteryConsumer.Key key = mData.layout.getKey(powerComponent, processState,
                SCREEN_STATE_UNSPECIFIED, POWER_STATE_UNSPECIFIED);
        if (key != null) {
            return mData.getLong(key.mDurationColumnIndex);
        } else {
            return 0;
        }
    }

    /**
     * Returns the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
     *
     * @param key The key of the power component, obtained by calling {@link BatteryConsumer#getKey}
     *            or {@link BatteryConsumer#getKeys} method.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPower(@NonNull BatteryConsumer.Key key) {
        if (mData.hasValue(key.mPowerColumnIndex)) {
            return mData.getDouble(key.mPowerColumnIndex);
        }
        return getConsumedPower(key.powerComponent, key.processState, key.screenState,
                key.powerState);
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
    int getPowerModel(BatteryConsumer.Key key) {
        if (key.mPowerModelColumnIndex == POWER_MODEL_NOT_INCLUDED) {
            throw new IllegalStateException(
                    "Power model IDs were not requested in the BatteryUsageStatsQuery");
        }
        return mData.getInt(key.mPowerModelColumnIndex);
    }

    /**
     * Returns the amount of time used by the specified component, e.g. CPU, WiFi etc.
     *
     * @param key The key of the power component, obtained by calling {@link BatteryConsumer#getKey}
     *            or {@link BatteryConsumer#getKeys} method.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationMillis(BatteryConsumer.Key key) {
        if (mData.hasValue(key.mDurationColumnIndex)) {
            return mData.getLong(key.mDurationColumnIndex);
        }

        return getUsageDurationMillis(key.powerComponent, key.processState, key.screenState,
                key.powerState);
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

    void dump(PrintWriter pw, @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState, boolean skipEmptyComponents) {
        StringBuilder sb = new StringBuilder();
        for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                componentId++) {
            dump(sb, componentId, PROCESS_STATE_ANY, screenState, powerState, skipEmptyComponents);
            if (mData.layout.processStateDataIncluded) {
                for (int processState = 0; processState < BatteryConsumer.PROCESS_STATE_COUNT;
                        processState++) {
                    if (processState == PROCESS_STATE_UNSPECIFIED) {
                        continue;
                    }
                    dump(sb, componentId, processState, screenState, powerState,
                            skipEmptyComponents);
                }
            }
        }

        // TODO(b/352835319): take into account screen and power states
        if (screenState == SCREEN_STATE_ANY && powerState == POWER_STATE_ANY) {
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
                sb.append(getCustomPowerComponentName(customComponentId));
                sb.append("=");
                sb.append(BatteryStats.formatCharge(customComponentPower));
                sb.append(" ");
            }
        }

        // Remove trailing spaces
        while (!sb.isEmpty() && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }

        pw.println(sb);
    }

    private void dump(StringBuilder sb, @BatteryConsumer.PowerComponent int powerComponent,
            @BatteryConsumer.ProcessState int processState,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState, boolean skipEmptyComponents) {
        final double componentPower = getConsumedPower(powerComponent, processState, screenState,
                powerState);
        final long durationMs = getUsageDurationMillis(powerComponent, processState, screenState,
                powerState);
        if (skipEmptyComponents && componentPower == 0 && durationMs == 0) {
            return;
        }

        sb.append(BatteryConsumer.powerComponentIdToString(powerComponent));
        if (processState != PROCESS_STATE_UNSPECIFIED) {
            sb.append(':');
            sb.append(BatteryConsumer.processStateToString(processState));
        }
        sb.append("=");
        sb.append(BatteryStats.formatCharge(componentPower));

        if (durationMs != 0) {
            sb.append(" (");
            BatteryStats.formatTimeMsNoSpace(sb, durationMs);
            sb.append(")");
        }
        sb.append(' ');
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
            final BatteryConsumer.Key[] keys = mData.layout.getKeys(componentId);
            for (BatteryConsumer.Key key : keys) {
                final long powerDeciCoulombs = convertMahToDeciCoulombs(
                        getConsumedPower(key.powerComponent, key.processState, key.screenState,
                                key.powerState));
                final long durationMs = getUsageDurationMillis(key.powerComponent, key.processState,
                        key.screenState, key.powerState);

                if (powerDeciCoulombs == 0 && durationMs == 0) {
                    // No interesting data. Make sure not to even write the COMPONENT int.
                    continue;
                }

                interestingData = true;
                if (proto == null) {
                    // We're just asked whether there is data, not to actually write it.
                    // And there is.
                    return true;
                }

                if (key.processState == PROCESS_STATE_ANY) {
                    writePowerComponentUsage(proto,
                            BatteryUsageStatsAtomsProto.BatteryConsumerData.POWER_COMPONENTS,
                            componentId, powerDeciCoulombs, durationMs);
                } else {
                    writePowerUsageSlice(proto, componentId, powerDeciCoulombs, durationMs,
                            key.processState);
                }
            }
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

            writePowerComponentUsage(proto,
                    BatteryUsageStatsAtomsProto.BatteryConsumerData.POWER_COMPONENTS,
                    componentId, powerDeciCoulombs, durationMs);
        }
        return interestingData;
    }

    private void writePowerUsageSlice(ProtoOutputStream proto, int componentId,
            long powerDeciCoulombs, long durationMs, int processState) {
        final long slicesToken =
                proto.start(BatteryUsageStatsAtomsProto.BatteryConsumerData.SLICES);
        writePowerComponentUsage(proto,
                BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        .POWER_COMPONENT,
                componentId, powerDeciCoulombs, durationMs);

        final int procState;
        switch (processState) {
            case BatteryConsumer.PROCESS_STATE_FOREGROUND:
                procState = BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        .FOREGROUND;
                break;
            case BatteryConsumer.PROCESS_STATE_BACKGROUND:
                procState = BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        .BACKGROUND;
                break;
            case BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE:
                procState = BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        .FOREGROUND_SERVICE;
                break;
            case BatteryConsumer.PROCESS_STATE_CACHED:
                procState = BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                        .CACHED;
                break;
            default:
                throw new IllegalArgumentException("Unknown process state: " + processState);
        }

        proto.write(BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
                .PROCESS_STATE, procState);

        proto.end(slicesToken);
    }

    private void writePowerComponentUsage(ProtoOutputStream proto, long tag, int componentId,
            long powerDeciCoulombs, long durationMs) {
        final long token = proto.start(tag);
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
        for (BatteryConsumer.Key key : mData.layout.keys) {
            if (!mData.hasValue(key.mPowerColumnIndex)
                    && !mData.hasValue(key.mDurationColumnIndex)) {
                continue;
            }

            final double powerMah = getConsumedPower(key);
            final long durationMs = getUsageDurationMillis(key);
            if (powerMah == 0 && durationMs == 0) {
                continue;
            }

            serializer.startTag(null, BatteryUsageStats.XML_TAG_COMPONENT);
            serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_ID, key.powerComponent);
            if (key.processState != PROCESS_STATE_UNSPECIFIED) {
                serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_PROCESS_STATE,
                        key.processState);
            }
            if (key.screenState != SCREEN_STATE_UNSPECIFIED) {
                serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_SCREEN_STATE,
                        key.screenState);
            }
            if (key.powerState != POWER_STATE_UNSPECIFIED) {
                serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_POWER_STATE,
                        key.powerState);
            }
            if (powerMah != 0) {
                serializer.attributeDouble(null, BatteryUsageStats.XML_ATTR_POWER, powerMah);
            }
            if (durationMs != 0) {
                serializer.attributeLong(null, BatteryUsageStats.XML_ATTR_DURATION, durationMs);
            }
            if (mData.layout.powerModelsIncluded) {
                serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_MODEL,
                        getPowerModel(key));
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
                        int processState = PROCESS_STATE_UNSPECIFIED;
                        int screenState = SCREEN_STATE_UNSPECIFIED;
                        int powerState = POWER_STATE_UNSPECIFIED;
                        double powerMah = 0;
                        long durationMs = 0;
                        int model = BatteryConsumer.POWER_MODEL_UNDEFINED;
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            switch (parser.getAttributeName(i)) {
                                case BatteryUsageStats.XML_ATTR_ID:
                                    componentId = parser.getAttributeInt(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_PROCESS_STATE:
                                    processState = parser.getAttributeInt(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_SCREEN_STATE:
                                    screenState = parser.getAttributeInt(i);
                                    break;
                                case BatteryUsageStats.XML_ATTR_POWER_STATE:
                                    powerState = parser.getAttributeInt(i);
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
                        final BatteryConsumer.Key key = builder.mData.layout.getKey(componentId,
                                processState, screenState, powerState);
                        builder.setConsumedPower(key, powerMah, model);
                        builder.setUsageDurationMillis(key, durationMs);
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
        private final double mMinConsumedPowerThreshold;

        Builder(BatteryConsumer.BatteryConsumerData data, double minConsumedPowerThreshold) {
            mData = data;
            mMinConsumedPowerThreshold = minConsumedPowerThreshold;
            for (BatteryConsumer.Key key : mData.layout.keys) {
                if (key.mPowerModelColumnIndex != POWER_MODEL_NOT_INCLUDED) {
                    mData.putInt(key.mPowerModelColumnIndex, POWER_MODEL_UNINITIALIZED);
                }
            }
        }

        @NonNull
        public Builder setConsumedPower(BatteryConsumer.Key key, double componentPower,
                int powerModel) {
            mData.putDouble(key.mPowerColumnIndex, componentPower);
            if (key.mPowerModelColumnIndex != POWER_MODEL_NOT_INCLUDED) {
                mData.putInt(key.mPowerModelColumnIndex, powerModel);
            }
            return this;
        }

        @NonNull
        public Builder addConsumedPower(BatteryConsumer.Key key, double componentPower,
                int powerModel) {
            mData.putDouble(key.mPowerColumnIndex,
                    mData.getDouble(key.mPowerColumnIndex) + componentPower);
            if (key.mPowerModelColumnIndex != POWER_MODEL_NOT_INCLUDED) {
                mData.putInt(key.mPowerModelColumnIndex, powerModel);
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

        @NonNull
        public Builder addConsumedPowerForCustomComponent(int componentId, double componentPower) {
            final int index = componentId - BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
            if (index < 0 || index >= mData.layout.customPowerComponentCount) {
                throw new IllegalArgumentException(
                        "Unsupported custom power component ID: " + componentId);
            }
            mData.putDouble(mData.layout.firstCustomConsumedPowerColumn + index,
                    mData.getDouble(mData.layout.firstCustomConsumedPowerColumn + index)
                            + componentPower);
            return this;
        }

        @NonNull
        public Builder setUsageDurationMillis(BatteryConsumer.Key key,
                long componentUsageDurationMillis) {
            mData.putLong(key.mDurationColumnIndex, componentUsageDurationMillis);
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
                        "Number of custom power components does not match: "
                                + otherData.layout.customPowerComponentCount
                                + ", expected: " + mData.layout.customPowerComponentCount);
            }

            for (BatteryConsumer.Key key : mData.layout.keys) {
                BatteryConsumer.Key otherKey = otherData.layout.getKey(key.powerComponent,
                        key.processState, key.screenState, key.powerState);
                if (otherKey == null) {
                    continue;
                }

                mData.putDouble(key.mPowerColumnIndex,
                        mData.getDouble(key.mPowerColumnIndex)
                                + otherData.getDouble(otherKey.mPowerColumnIndex));
                mData.putLong(key.mDurationColumnIndex,
                        mData.getLong(key.mDurationColumnIndex)
                                + otherData.getLong(otherKey.mDurationColumnIndex));

                if (key.mPowerModelColumnIndex == POWER_MODEL_NOT_INCLUDED) {
                    continue;
                }

                boolean undefined = false;
                if (otherKey.mPowerModelColumnIndex == POWER_MODEL_NOT_INCLUDED) {
                    undefined = true;
                } else {
                    final int powerModel = mData.getInt(key.mPowerModelColumnIndex);
                    int otherPowerModel = otherData.getInt(otherKey.mPowerModelColumnIndex);
                    if (powerModel == POWER_MODEL_UNINITIALIZED) {
                        mData.putInt(key.mPowerModelColumnIndex, otherPowerModel);
                    } else if (powerModel != otherPowerModel
                            && otherPowerModel != POWER_MODEL_UNINITIALIZED) {
                        undefined = true;
                    }
                }

                if (undefined) {
                    mData.putInt(key.mPowerModelColumnIndex,
                            BatteryConsumer.POWER_MODEL_UNDEFINED);
                }
            }

            for (int i = mData.layout.customPowerComponentCount - 1; i >= 0; i--) {
                final int powerColumnIndex = mData.layout.firstCustomConsumedPowerColumn + i;
                final int otherPowerColumnIndex =
                        otherData.layout.firstCustomConsumedPowerColumn + i;
                mData.putDouble(powerColumnIndex,
                        mData.getDouble(powerColumnIndex) + otherData.getDouble(
                                otherPowerColumnIndex));

                final int usageColumnIndex = mData.layout.firstCustomUsageDurationColumn + i;
                final int otherDurationColumnIndex =
                        otherData.layout.firstCustomUsageDurationColumn + i;
                mData.putLong(usageColumnIndex, mData.getLong(usageColumnIndex)
                        + otherData.getLong(otherDurationColumnIndex));
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
                totalPowerMah += mData.getDouble(
                        mData.layout.getKeyOrThrow(componentId, PROCESS_STATE_ANY, SCREEN_STATE_ANY,
                                POWER_STATE_ANY).mPowerColumnIndex);
            }
            for (int i = 0; i < mData.layout.customPowerComponentCount; i++) {
                totalPowerMah += mData.getDouble(
                        mData.layout.firstCustomConsumedPowerColumn + i);
            }
            return totalPowerMah;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public PowerComponents build() {
            for (BatteryConsumer.Key key: mData.layout.keys) {
                if (key.mPowerModelColumnIndex != POWER_MODEL_NOT_INCLUDED) {
                    if (mData.getInt(key.mPowerModelColumnIndex) == POWER_MODEL_UNINITIALIZED) {
                        mData.putInt(key.mPowerModelColumnIndex,
                                BatteryConsumer.POWER_MODEL_UNDEFINED);
                    }
                }

                if (mMinConsumedPowerThreshold != 0) {
                    if (mData.getDouble(key.mPowerColumnIndex) < mMinConsumedPowerThreshold) {
                        mData.putDouble(key.mPowerColumnIndex, 0);
                    }
                }
            }

            if (mData.getDouble(mData.layout.totalConsumedPowerColumnIndex) == 0) {
                mData.putDouble(mData.layout.totalConsumedPowerColumnIndex, getTotalPower());
            }
            return new PowerComponents(this);
        }
    }
}
