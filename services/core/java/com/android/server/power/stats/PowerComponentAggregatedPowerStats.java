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

import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.internal.os.MultiStateStats;
import com.android.internal.os.PowerStats;

import java.util.Collection;

/**
 * Aggregated power stats for a specific power component (e.g. CPU, WiFi, etc). This class
 * treats stats as arrays of nonspecific longs. Subclasses contain specific logic to interpret those
 * longs and use them for calculations such as power attribution. They may use meta-data supplied
 * as part of the {@link PowerStats.Descriptor}.
 */
class PowerComponentAggregatedPowerStats {
    public final int powerComponentId;
    private final MultiStateStats.States[] mDeviceStateConfig;
    private final MultiStateStats.States[] mUidStateConfig;
    private final int[] mDeviceStates;
    private final long[] mDeviceStateTimestamps;

    private MultiStateStats.Factory mStatsFactory;
    private MultiStateStats.Factory mUidStatsFactory;
    private PowerStats.Descriptor mPowerStatsDescriptor;
    private MultiStateStats mDeviceStats;
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();

    private static class UidStats {
        public int[] states;
        public long[] stateTimestampMs;
        public MultiStateStats stats;
    }

    PowerComponentAggregatedPowerStats(int powerComponentId,
            MultiStateStats.States[] deviceStates,
            MultiStateStats.States[] uidStates) {
        this.powerComponentId = powerComponentId;
        mDeviceStateConfig = deviceStates;
        mUidStateConfig = uidStates;
        mDeviceStates = new int[mDeviceStateConfig.length];
        mDeviceStateTimestamps = new long[mDeviceStateConfig.length];
    }

    void setState(@PowerStatsAggregator.TrackedState int stateId, int state, long time) {
        mDeviceStates[stateId] = state;
        mDeviceStateTimestamps[stateId] = time;

        if (mDeviceStateConfig[stateId].isTracked()) {
            if (mDeviceStats != null || createDeviceStats()) {
                mDeviceStats.setState(stateId, state, time);
            }
        }

        if (mUidStateConfig[stateId].isTracked()) {
            for (int i = mUidStats.size() - 1; i >= 0; i--) {
                PowerComponentAggregatedPowerStats.UidStats uidStats = mUidStats.valueAt(i);
                if (uidStats.stats != null || createUidStats(uidStats)) {
                    uidStats.stats.setState(stateId, state, time);
                }
            }
        }
    }

    void setUidState(int uid, @PowerStatsAggregator.TrackedState int stateId, int state,
            long time) {
        if (!mUidStateConfig[stateId].isTracked()) {
            return;
        }

        UidStats uidStats = getUidStats(uid);
        uidStats.states[stateId] = state;
        uidStats.stateTimestampMs[stateId] = time;

        if (uidStats.stats != null || createUidStats(uidStats)) {
            uidStats.stats.setState(stateId, state, time);
        }
    }

    boolean isCompatible(PowerStats powerStats) {
        return mPowerStatsDescriptor == null || mPowerStatsDescriptor.equals(powerStats.descriptor);
    }

    void addPowerStats(PowerStats powerStats, long timestampMs) {
        mPowerStatsDescriptor = powerStats.descriptor;

        if (mDeviceStats == null) {
            if (mStatsFactory == null) {
                mStatsFactory = new MultiStateStats.Factory(
                        mPowerStatsDescriptor.statsArrayLength, mDeviceStateConfig);
                mUidStatsFactory = new MultiStateStats.Factory(
                        mPowerStatsDescriptor.uidStatsArrayLength, mUidStateConfig);
            }

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
    }

    void reset() {
        mPowerStatsDescriptor = null;
        mStatsFactory = null;
        mUidStatsFactory = null;
        mDeviceStats = null;
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            mUidStats.valueAt(i).stats = null;
        }
    }

    private UidStats getUidStats(int uid) {
        // TODO(b/292247660): map isolated and sandbox UIDs
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.states = new int[mUidStateConfig.length];
            uidStats.stateTimestampMs = new long[mUidStateConfig.length];
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

    private boolean createDeviceStats() {
        if (mStatsFactory == null) {
            return false;
        }

        mDeviceStats = mStatsFactory.create();
        for (int stateId = 0; stateId < mDeviceStateConfig.length; stateId++) {
            mDeviceStats.setState(stateId, mDeviceStates[stateId],
                    mDeviceStateTimestamps[stateId]);
        }
        return true;
    }

    private boolean createUidStats(UidStats uidStats) {
        if (mUidStatsFactory == null) {
            return false;
        }

        uidStats.stats = mUidStatsFactory.create();
        for (int stateId = 0; stateId < mDeviceStateConfig.length; stateId++) {
            uidStats.stats.setState(stateId, mDeviceStates[stateId],
                    mDeviceStateTimestamps[stateId]);
        }
        for (int stateId = mDeviceStateConfig.length; stateId < mUidStateConfig.length; stateId++) {
            uidStats.stats.setState(stateId, uidStats.states[stateId],
                    uidStats.stateTimestampMs[stateId]);
        }
        return true;
    }

    void dumpDevice(IndentingPrintWriter ipw) {
        if (mDeviceStats != null) {
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            mDeviceStats.dump(ipw);
            ipw.decreaseIndent();
        }
    }

    void dumpUid(IndentingPrintWriter ipw, int uid) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.stats != null) {
            ipw.println(mPowerStatsDescriptor.name);
            ipw.increaseIndent();
            uidStats.stats.dump(ipw);
            ipw.decreaseIndent();
        }
    }
}
