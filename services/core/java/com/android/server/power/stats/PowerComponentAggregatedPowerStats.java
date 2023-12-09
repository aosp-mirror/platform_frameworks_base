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

package com.android.server.power.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collection;

/**
 * Aggregated power stats for a specific power component (e.g. CPU, WiFi, etc). This class
 * treats stats as arrays of nonspecific longs. Subclasses contain specific logic to interpret those
 * longs and use them for calculations such as power attribution. They may use meta-data supplied
 * as part of the {@link PowerStats.Descriptor}.
 */
class PowerComponentAggregatedPowerStats {
    static final String XML_TAG_POWER_COMPONENT = "power_component";
    static final String XML_ATTR_ID = "id";
    private static final String XML_TAG_DEVICE_STATS = "device-stats";
    private static final String XML_TAG_UID_STATS = "uid-stats";
    private static final String XML_ATTR_UID = "uid";
    private static final long UNKNOWN = -1;

    public final int powerComponentId;
    private final MultiStateStats.States[] mDeviceStateConfig;
    private final MultiStateStats.States[] mUidStateConfig;
    @NonNull
    private final AggregatedPowerStatsConfig.PowerComponent mConfig;
    private final int[] mDeviceStates;

    private MultiStateStats.Factory mStatsFactory;
    private MultiStateStats.Factory mUidStatsFactory;
    private PowerStats.Descriptor mPowerStatsDescriptor;
    private long mPowerStatsTimestamp;
    private MultiStateStats mDeviceStats;
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();

    private static class UidStats {
        public int[] states;
        public MultiStateStats stats;
    }

    PowerComponentAggregatedPowerStats(AggregatedPowerStatsConfig.PowerComponent config) {
        mConfig = config;
        powerComponentId = config.getPowerComponentId();
        mDeviceStateConfig = config.getDeviceStateConfig();
        mUidStateConfig = config.getUidStateConfig();
        mDeviceStates = new int[mDeviceStateConfig.length];
        mPowerStatsTimestamp = UNKNOWN;
    }

    @NonNull
    public AggregatedPowerStatsConfig.PowerComponent getConfig() {
        return mConfig;
    }

    @Nullable
    public PowerStats.Descriptor getPowerStatsDescriptor() {
        return mPowerStatsDescriptor;
    }

    void setState(@AggregatedPowerStatsConfig.TrackedState int stateId, int state, long time) {
        if (mDeviceStats == null) {
            createDeviceStats();
        }

        mDeviceStates[stateId] = state;

        if (mDeviceStateConfig[stateId].isTracked()) {
            if (mDeviceStats != null) {
                mDeviceStats.setState(stateId, state, time);
            }
        }

        if (mUidStateConfig[stateId].isTracked()) {
            for (int i = mUidStats.size() - 1; i >= 0; i--) {
                PowerComponentAggregatedPowerStats.UidStats uidStats = mUidStats.valueAt(i);
                if (uidStats.stats == null) {
                    createUidStats(uidStats);
                }

                uidStats.states[stateId] = state;
                if (uidStats.stats != null) {
                    uidStats.stats.setState(stateId, state, time);
                }
            }
        }
    }

    void setUidState(int uid, @AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long time) {
        if (!mUidStateConfig[stateId].isTracked()) {
            return;
        }

        UidStats uidStats = getUidStats(uid);
        if (uidStats.stats == null) {
            createUidStats(uidStats);
        }

        uidStats.states[stateId] = state;

        if (uidStats.stats != null) {
            uidStats.stats.setState(stateId, state, time);
        }
    }

    void setDeviceStats(@AggregatedPowerStatsConfig.TrackedState int[] states, long[] values) {
        mDeviceStats.setStats(states, values);
    }

    void setUidStats(int uid, @AggregatedPowerStatsConfig.TrackedState int[] states,
            long[] values) {
        UidStats uidStats = getUidStats(uid);
        uidStats.stats.setStats(states, values);
    }

    boolean isCompatible(PowerStats powerStats) {
        return mPowerStatsDescriptor == null || mPowerStatsDescriptor.equals(powerStats.descriptor);
    }

    void addPowerStats(PowerStats powerStats, long timestampMs) {
        mPowerStatsDescriptor = powerStats.descriptor;

        if (mDeviceStats == null) {
            createDeviceStats();
        }

        mDeviceStats.increment(powerStats.stats, timestampMs);

        for (int i = powerStats.uidStats.size() - 1; i >= 0; i--) {
            int uid = powerStats.uidStats.keyAt(i);
            PowerComponentAggregatedPowerStats.UidStats uidStats = getUidStats(uid);
            if (uidStats.stats == null) {
                createUidStats(uidStats);
            }
            uidStats.stats.increment(powerStats.uidStats.valueAt(i), timestampMs);
        }

        mPowerStatsTimestamp = timestampMs;
    }

    void reset() {
        mStatsFactory = null;
        mUidStatsFactory = null;
        mDeviceStats = null;
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            mUidStats.valueAt(i).stats = null;
        }
    }

    private UidStats getUidStats(int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.states = new int[mUidStateConfig.length];
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

    private void createDeviceStats() {
        if (mStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.statsArrayLength, mDeviceStateConfig);
        }

        mDeviceStats = mStatsFactory.create();
        if (mPowerStatsTimestamp != UNKNOWN) {
            for (int stateId = 0; stateId < mDeviceStateConfig.length; stateId++) {
                mDeviceStats.setState(stateId, mDeviceStates[stateId], mPowerStatsTimestamp);
            }
        }
    }

    private void createUidStats(UidStats uidStats) {
        if (mUidStatsFactory == null) {
            if (mPowerStatsDescriptor == null) {
                return;
            }
            mUidStatsFactory = new MultiStateStats.Factory(
                    mPowerStatsDescriptor.uidStatsArrayLength, mUidStateConfig);
        }

        uidStats.stats = mUidStatsFactory.create();
        for (int stateId = 0; stateId < mUidStateConfig.length; stateId++) {
            if (mPowerStatsTimestamp != UNKNOWN) {
                uidStats.stats.setState(stateId, uidStats.states[stateId], mPowerStatsTimestamp);
            }
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
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
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
                            createDeviceStats();
                        }
                        if (!mDeviceStats.readFromXml(parser)) {
                            return false;
                        }
                        break;
                    case XML_TAG_UID_STATS:
                        int uid = parser.getAttributeInt(null, XML_ATTR_UID);
                        UidStats uidStats = getUidStats(uid);
                        if (uidStats.stats == null) {
                            createUidStats(uidStats);
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
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            mDeviceStats.dump(ipw, stats ->
                    mConfig.getProcessor().deviceStatsToString(mPowerStatsDescriptor, stats));
            ipw.decreaseIndent();
        }
    }

    void dumpUid(IndentingPrintWriter ipw, int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            uidStats.stats.dump(ipw, stats ->
                    mConfig.getProcessor().uidStatsToString(mPowerStatsDescriptor, stats));
            ipw.decreaseIndent();
        }
    }
}
