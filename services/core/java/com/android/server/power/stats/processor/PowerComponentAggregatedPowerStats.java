/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats.processor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryStats;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.IntConsumer;

/**
 * Aggregated power stats for a specific power component (e.g. CPU, WiFi, etc). This class
 * treats stats as arrays of nonspecific longs. Subclasses contain specific logic to interpret those
 * longs and use them for calculations such as power attribution. They may use meta-data supplied
 * as part of the {@link PowerStats.Descriptor}.
 */
class PowerComponentAggregatedPowerStats {
    private static final String TAG = "AggregatePowerStats";
    static final String XML_TAG_POWER_COMPONENT = "power_component";
    static final String XML_ATTR_ID = "id";
    private static final String XML_TAG_DEVICE_STATS = "device-stats";
    private static final String XML_TAG_STATE_STATS = "state-stats";
    private static final String XML_ATTR_KEY = "key";
    private static final String XML_TAG_UID_STATS = "uid-stats";
    private static final String XML_ATTR_UID = "uid";
    private static final long UNKNOWN = -1;

    public final int powerComponentId;
    @NonNull
    private final AggregatedPowerStats mAggregatedPowerStats;
    @NonNull
    private final AggregatedPowerStatsConfig.PowerComponent mConfig;
    private final MultiStateStats.States[] mDeviceStateConfig;
    private final MultiStateStats.States[] mUidStateConfig;
    private final int[] mDeviceStates;

    private PowerStatsProcessor mProcessor;
    private MultiStateStats.Factory mStatsFactory;
    private MultiStateStats.Factory mStateStatsFactory;
    private MultiStateStats.Factory mUidStatsFactory;
    private PowerStats.Descriptor mPowerStatsDescriptor;
    private long mPowerStatsTimestamp;
    private MultiStateStats mDeviceStats;
    private final SparseArray<MultiStateStats> mStateStats = new SparseArray<>();
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();
    private long[] mZeroArray;

    private static class UidStats {
        public int[] states;
        public MultiStateStats stats;
        public boolean updated;
    }

    PowerComponentAggregatedPowerStats(@NonNull AggregatedPowerStats aggregatedPowerStats,
            @NonNull AggregatedPowerStatsConfig.PowerComponent config) {
        mAggregatedPowerStats = aggregatedPowerStats;
        mConfig = config;
        powerComponentId = config.getPowerComponentId();
        mDeviceStateConfig = config.getDeviceStateConfig();
        mUidStateConfig = config.getUidStateConfig();
        mDeviceStates = new int[mDeviceStateConfig.length];
        mPowerStatsTimestamp = UNKNOWN;
    }

    @NonNull
    AggregatedPowerStats getAggregatedPowerStats() {
        return mAggregatedPowerStats;
    }

    @NonNull
    public AggregatedPowerStatsConfig.PowerComponent getConfig() {
        return mConfig;
    }

    @Nullable
    public PowerStats.Descriptor getPowerStatsDescriptor() {
        return mPowerStatsDescriptor;
    }

    public void setPowerStatsDescriptor(PowerStats.Descriptor powerStatsDescriptor) {
        mPowerStatsDescriptor = powerStatsDescriptor;
    }

    void start(long timestampMs) {
        if (mProcessor == null) {
            mProcessor = mConfig.createProcessor();
        }
        mProcessor.start(this, timestampMs);
    }

    void finish(long timestampMs) {
        mProcessor.finish(this, timestampMs);
    }

    void noteStateChange(BatteryStats.HistoryItem item) {
        mProcessor.noteStateChange(this, item);
    }

    void setState(@AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long timestampMs) {
        if (mDeviceStats == null) {
            createDeviceStats(timestampMs);
        }

        mDeviceStates[stateId] = state;

        if (mDeviceStateConfig[stateId].isTracked()) {
            if (mDeviceStats != null) {
                mDeviceStats.setState(stateId, state, timestampMs);
            }
            for (int i = mStateStats.size() - 1; i >= 0; i--) {
                MultiStateStats stateStats = mStateStats.valueAt(i);
                stateStats.setState(stateId, state, timestampMs);
            }
        }

        int uidStateId = MultiStateStats.States
                .findTrackedStateByName(mUidStateConfig, mDeviceStateConfig[stateId].getName());
        if (uidStateId != MultiStateStats.STATE_DOES_NOT_EXIST
                && mUidStateConfig[uidStateId].isTracked()) {
            for (int i = mUidStats.size() - 1; i >= 0; i--) {
                PowerComponentAggregatedPowerStats.UidStats uidStats = mUidStats.valueAt(i);
                if (uidStats.stats == null) {
                    createUidStats(uidStats, timestampMs);
                }

                uidStats.states[uidStateId] = state;
                if (uidStats.stats != null) {
                    uidStats.stats.setState(uidStateId, state, timestampMs);
                }
            }
        }
    }

    void setUidState(int uid, @AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long timestampMs) {
        if (!mUidStateConfig[stateId].isTracked()) {
            return;
        }

        UidStats uidStats = getUidStats(uid);
        if (uidStats.stats == null) {
            createUidStats(uidStats, timestampMs);
        }

        uidStats.states[stateId] = state;

        if (uidStats.stats != null) {
            uidStats.stats.setState(stateId, state, timestampMs);
        }
    }

    void setDeviceStats(int[] states, long[] values) {
        if (mDeviceStats == null) {
            createDeviceStats(0);
        }
        mDeviceStats.setStats(states, values);
    }

    void setUidStats(int uid, int[] states, long[] values) {
        UidStats uidStats = getUidStats(uid);
        if (uidStats.stats == null) {
            createUidStats(uidStats, mPowerStatsTimestamp);
        }
        uidStats.stats.setStats(states, values);
    }

    boolean isCompatible(PowerStats powerStats) {
        return mPowerStatsDescriptor == null || mPowerStatsDescriptor.equals(powerStats.descriptor);
    }

    void addPowerStats(PowerStats powerStats, long timestampMs) {
        // Should call powerStats.addProcessedPowerStats
        mProcessor.addPowerStats(this, powerStats, timestampMs);
    }

    /**
     * Should be called ONLY by PowerStatsProcessor.processPowerStats.
     */
    void addProcessedPowerStats(PowerStats powerStats, long timestampMs) {
        mPowerStatsDescriptor = powerStats.descriptor;

        if (mDeviceStats == null) {
            createDeviceStats(timestampMs);
        }

        for (int i = powerStats.stateStats.size() - 1; i >= 0; i--) {
            int key = powerStats.stateStats.keyAt(i);
            MultiStateStats stateStats = mStateStats.get(key);
            if (stateStats == null) {
                stateStats = createStateStats(key, timestampMs);
            }
            stateStats.increment(powerStats.stateStats.valueAt(i), timestampMs);
        }
        mDeviceStats.increment(powerStats.stats, timestampMs);

        for (int i = powerStats.uidStats.size() - 1; i >= 0; i--) {
            int uid = powerStats.uidStats.keyAt(i);
            PowerComponentAggregatedPowerStats.UidStats uidStats = getUidStats(uid);
            if (uidStats.stats == null) {
                createUidStats(uidStats, timestampMs);
            }
            uidStats.stats.increment(powerStats.uidStats.valueAt(i), timestampMs);
            uidStats.updated = true;
        }

        // For UIDs not mentioned in the PowerStats object, we must assume a 0 increment.
        // It is essential to call `stats.increment(zero)` in order to record the new
        // timestamp, which will ensure correct proportional attribution across all UIDs
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            PowerComponentAggregatedPowerStats.UidStats uidStats = mUidStats.valueAt(i);
            if (!uidStats.updated && uidStats.stats != null) {
                if (mZeroArray == null
                        || mZeroArray.length != mPowerStatsDescriptor.uidStatsArrayLength) {
                    mZeroArray = new long[mPowerStatsDescriptor.uidStatsArrayLength];
                }
                uidStats.stats.increment(mZeroArray, timestampMs);
            }
            uidStats.updated = false;
        }

        mPowerStatsTimestamp = timestampMs;
    }

    void reset() {
        mStatsFactory = null;
        mUidStatsFactory = null;
        mDeviceStats = null;
        mStateStats.clear();
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            mUidStats.valueAt(i).stats = null;
        }
    }

    private UidStats getUidStats(int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.states = new int[mUidStateConfig.length];
            for (int stateId = 0; stateId < mUidStateConfig.length; stateId++) {
                if (mUidStateConfig[stateId].isTracked()) {
                    int deviceStateId = MultiStateStats.States.findTrackedStateByName(
                            mDeviceStateConfig, mUidStateConfig[stateId].getName());
                    if (deviceStateId != MultiStateStats.STATE_DOES_NOT_EXIST
                            && mDeviceStateConfig[deviceStateId].isTracked()) {
                        uidStats.states[stateId] = mDeviceStates[deviceStateId];
                    }
                }
            }
            mUidStats.put(uid, uidStats);
        }
        return uidStats;
    }

    void collectUids(Collection<Integer> uids) {
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            if (mUidStats.valueAt(i).stats != null) {
                uids.add(mUidStats.keyAt(i));
            }
        }
    }

    boolean getDeviceStats(long[] outValues, int[] deviceStates) {
        if (deviceStates.length != mDeviceStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + deviceStates.length
                    + " expected: " + mDeviceStateConfig.length);
        }
        if (mDeviceStats != null) {
            mDeviceStats.getStats(outValues, deviceStates);
            return true;
        }
        return false;
    }

    boolean getStateStats(long[] outValues, int key, int[] deviceStates) {
        if (deviceStates.length != mDeviceStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + deviceStates.length
                            + " expected: " + mDeviceStateConfig.length);
        }
        MultiStateStats stateStats = mStateStats.get(key);
        if (stateStats != null) {
            stateStats.getStats(outValues, deviceStates);
            return true;
        }
        return false;
    }

    void forEachStateStatsKey(IntConsumer consumer) {
        for (int i = mStateStats.size() - 1; i >= 0; i--) {
            consumer.accept(mStateStats.keyAt(i));
        }
    }

    boolean getUidStats(long[] outValues, int uid, int[] uidStates) {
        if (uidStates.length != mUidStateConfig.length) {
            throw new IllegalArgumentException(
                    "Invalid number of tracked states: " + uidStates.length
                    + " expected: " + mUidStateConfig.length);
        }
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            uidStats.stats.getStats(outValues, uidStates);
            return true;
        }
        return false;
    }

    private void createDeviceStats(long timestampMs) {
        if (mStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.statsArrayLength, mDeviceStateConfig);
        }

        mDeviceStats = mStatsFactory.create();
        if (mPowerStatsTimestamp != UNKNOWN) {
            timestampMs = mPowerStatsTimestamp;
        }
        if (timestampMs != UNKNOWN) {
            for (int stateId = 0; stateId < mDeviceStateConfig.length; stateId++) {
                int state = mDeviceStates[stateId];
                mDeviceStats.setState(stateId, state, timestampMs);
                for (int i = mStateStats.size() - 1; i >= 0; i--) {
                    MultiStateStats stateStats = mStateStats.valueAt(i);
                    stateStats.setState(stateId, state, timestampMs);
                }
            }
        }
    }

    private MultiStateStats createStateStats(int key, long timestampMs) {
        if (mStateStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return null;
            }
            mStateStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.stateStatsArrayLength, mDeviceStateConfig);
        }

        MultiStateStats stateStats = mStateStatsFactory.create();
        mStateStats.put(key, stateStats);
        if (mDeviceStats != null) {
            stateStats.copyStatesFrom(mDeviceStats);
        }

        return stateStats;
    }

    private void createUidStats(UidStats uidStats, long timestampMs) {
        if (mUidStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mUidStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.uidStatsArrayLength, mUidStateConfig);
        }

        uidStats.stats = mUidStatsFactory.create();

        if (mPowerStatsTimestamp != UNKNOWN) {
            timestampMs = mPowerStatsTimestamp;
        }
        if (timestampMs != UNKNOWN) {
            for (int stateId = 0; stateId < mUidStateConfig.length; stateId++) {
                uidStats.stats.setState(stateId, uidStats.states[stateId], timestampMs);
            }
        }
    }

    void copyStatesFrom(PowerComponentAggregatedPowerStats source) {
        if (source.mDeviceStates.length == mDeviceStates.length) {
            System.arraycopy(source.mDeviceStates, 0, mDeviceStates, 0, mDeviceStates.length);
            if (source.mDeviceStats != null) {
                createDeviceStats(0);
                if (mDeviceStats != null) {
                    mDeviceStats.copyStatesFrom(source.mDeviceStats);
                }
            }
        } else {
            Slog.wtf(TAG, "State configurations have different lengths: "
                    + source.mDeviceStates.length + " vs " + mDeviceStates.length);
        }
        for (int i = source.mUidStats.size() - 1; i >= 0; i--) {
            int uid = source.mUidStats.keyAt(i);
            UidStats sourceUidStats = source.mUidStats.valueAt(i);
            if (sourceUidStats.states == null) {
                continue;
            }
            UidStats uidStats = new UidStats();
            uidStats.states = Arrays.copyOf(sourceUidStats.states, sourceUidStats.states.length);
            if (sourceUidStats.stats != null) {
                createUidStats(uidStats, 0);
                if (uidStats.stats != null) {
                    uidStats.stats.copyStatesFrom(sourceUidStats.stats);
                }
            }
            mUidStats.put(uid, uidStats);
        }
    }

    public void writeXml(TypedXmlSerializer serializer) throws IOException {
        // No stats aggregated - can skip writing XML altogether
        if (mPowerStatsDescriptor == null) {
            return;
        }

        serializer.startTag(null, XML_TAG_POWER_COMPONENT);
        serializer.attributeInt(null, XML_ATTR_ID, powerComponentId);
        mPowerStatsDescriptor.writeXml(serializer);

        if (mDeviceStats != null) {
            serializer.startTag(null, XML_TAG_DEVICE_STATS);
            mDeviceStats.writeXml(serializer);
            serializer.endTag(null, XML_TAG_DEVICE_STATS);
        }

        for (int i = 0; i < mStateStats.size(); i++) {
            serializer.startTag(null, XML_TAG_STATE_STATS);
            serializer.attributeInt(null, XML_ATTR_KEY, mStateStats.keyAt(i));
            mStateStats.valueAt(i).writeXml(serializer);
            serializer.endTag(null, XML_TAG_STATE_STATS);
        }

        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            int uid = mUidStats.keyAt(i);
            UidStats uidStats = mUidStats.valueAt(i);
            if (uidStats.stats != null) {
                serializer.startTag(null, XML_TAG_UID_STATS);
                serializer.attributeInt(null, XML_ATTR_UID, uid);
                uidStats.stats.writeXml(serializer);
                serializer.endTag(null, XML_TAG_UID_STATS);
            }
        }

        serializer.endTag(null, XML_TAG_POWER_COMPONENT);
        serializer.flush();
    }

    public boolean readFromXml(TypedXmlPullParser parser) throws XmlPullParserException,
            IOException {
        String outerTag = parser.getName();
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT
                && !(eventType == XmlPullParser.END_TAG && parser.getName().equals(outerTag))) {
            if (eventType == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case PowerStats.Descriptor.XML_TAG_DESCRIPTOR:
                        mPowerStatsDescriptor = PowerStats.Descriptor.createFromXml(parser);
                        if (mPowerStatsDescriptor == null) {
                            return false;
                        }
                        break;
                    case XML_TAG_DEVICE_STATS:
                        if (mDeviceStats == null) {
                            createDeviceStats(UNKNOWN);
                        }
                        if (!mDeviceStats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                    case XML_TAG_STATE_STATS:
                        int key = parser.getAttributeInt(null, XML_ATTR_KEY);
                        MultiStateStats stats = mStateStats.get(key);
                        if (stats == null) {
                            stats = createStateStats(key, UNKNOWN);
                        }
                        if (!stats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                    case XML_TAG_UID_STATS:
                        int uid = parser.getAttributeInt(null, XML_ATTR_UID);
                        UidStats uidStats = getUidStats(uid);
                        if (uidStats.stats == null) {
                            createUidStats(uidStats, UNKNOWN);
                        }
                        if (!uidStats.stats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                }
            }
            eventType = parser.next();
        }
        return true;
    }

    void dumpDevice(IndentingPrintWriter ipw) {
        if (mDeviceStats != null) {
            dumpMultiStateStats(ipw, mDeviceStats, mPowerStatsDescriptor.name, null,
                    mPowerStatsDescriptor.getDeviceStatsFormatter());
        }

        if (mStateStats.size() != 0) {
            ipw.increaseIndent();
            String header = mPowerStatsDescriptor.name + " states";
            PowerStats.PowerStatsFormatter formatter =
                    mPowerStatsDescriptor.getStateStatsFormatter();
            for (int i = 0; i < mStateStats.size(); i++) {
                int key = mStateStats.keyAt(i);
                String stateLabel = mPowerStatsDescriptor.getStateLabel(key);
                MultiStateStats stateStats = mStateStats.valueAt(i);
                dumpMultiStateStats(ipw, stateStats, header, stateLabel, formatter);
            }
            ipw.decreaseIndent();
        }
    }

    void dumpUid(IndentingPrintWriter ipw, int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            dumpMultiStateStats(ipw, uidStats.stats, mPowerStatsDescriptor.name, null,
                    mPowerStatsDescriptor.getUidStatsFormatter());
        }
    }

    private void dumpMultiStateStats(IndentingPrintWriter ipw, MultiStateStats stats,
            String header, String additionalLabel,
            PowerStats.PowerStatsFormatter statsFormatter) {
        boolean[] firstLine = new boolean[]{true};
        long[] values = new long[stats.getDimensionCount()];
        MultiStateStats.States[] stateInfo = stats.getStates();
        MultiStateStats.States.forEachTrackedStateCombination(stateInfo, states -> {
            stats.getStats(values, states);
            boolean nonZero = false;
            for (long value : values) {
                if (value != 0) {
                    nonZero = true;
                    break;
                }
            }
            if (!nonZero) {
                return;
            }

            if (firstLine[0]) {
                ipw.println(header);
                ipw.increaseIndent();
            }
            firstLine[0] = false;
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            boolean first = true;
            for (int i = 0; i < states.length; i++) {
                if (stateInfo[i].isTracked()) {
                    if (!first) {
                        sb.append(" ");
                    }
                    first = false;
                    sb.append(stateInfo[i].getLabels()[states[i]]);
                }
            }
            if (additionalLabel != null) {
                sb.append(" ").append(additionalLabel);
            }
            sb.append(") ").append(statsFormatter.format(values));
            ipw.println(sb);
        });
        if (!firstLine[0]) {
            ipw.decreaseIndent();
        }
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        IndentingPrintWriter ipw = new IndentingPrintWriter(sw);
        ipw.increaseIndent();
        dumpDevice(ipw);
        ipw.decreaseIndent();

        int[] uids = new int[mUidStats.size()];
        for (int i = uids.length - 1; i >= 0; i--) {
            uids[i] = mUidStats.keyAt(i);
        }
        Arrays.sort(uids);
        for (int uid : uids) {
            ipw.println(UserHandle.formatUid(uid));
            ipw.increaseIndent();
            dumpUid(ipw, uid);
            ipw.decreaseIndent();
        }

        ipw.flush();

        return sw.toString();
    }
}
