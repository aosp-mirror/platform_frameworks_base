/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.os.BatteryConsumer.PROCESS_STATE_UNSPECIFIED;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import android.os.BatteryConsumer;
import android.os.PersistableBundle;
import android.util.SparseLongArray;

import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.BasePowerStatsLayout;

import java.util.ArrayList;
import java.util.List;

class BasePowerStatsProcessor extends PowerStatsProcessor {
    private PowerEstimationPlan mPlan;
    private long mStartTimestamp;
    private final SparseLongArray mUidStartTimestamps = new SparseLongArray();
    private static final BasePowerStatsLayout sStatsLayout = new BasePowerStatsLayout();
    private final PowerStats.Descriptor mPowerStatsDescriptor;
    private final long[] mTmpUidStatsArray;

    BasePowerStatsProcessor() {
        PersistableBundle extras = new PersistableBundle();
        sStatsLayout.toExtras(extras);
        mPowerStatsDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_BASE,
                sStatsLayout.getDeviceStatsArrayLength(), null, 0,
                sStatsLayout.getUidStatsArrayLength(), extras);
        mTmpUidStatsArray = new long[sStatsLayout.getUidStatsArrayLength()];
    }

    @Override
    void start(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        mStartTimestamp = timestampMs;
        mUidStartTimestamps.clear();
        stats.setPowerStatsDescriptor(mPowerStatsDescriptor);
    }

    @Override
    public void setUidState(PowerComponentAggregatedPowerStats stats, int uid,
            @AggregatedPowerStatsConfig.TrackedState int stateId, int state, long timestampMs) {
        super.setUidState(stats, uid, stateId, state, timestampMs);
        if (stateId == STATE_PROCESS_STATE && mUidStartTimestamps.indexOfKey(uid) < 0) {
            mUidStartTimestamps.put(uid, timestampMs);
        }
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        PowerStats powerStats = new PowerStats(mPowerStatsDescriptor);
        sStatsLayout.setUsageDuration(powerStats.stats, timestampMs - mStartTimestamp);

        List<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);

        if (!uids.isEmpty()) {
            for (int i = uids.size() - 1; i >= 0; i--) {
                Integer uid = uids.get(i);
                long durationMs = timestampMs - mUidStartTimestamps.get(uid, mStartTimestamp);
                mUidStartTimestamps.put(uid, timestampMs);

                long[] uidStats = new long[sStatsLayout.getUidStatsArrayLength()];
                sStatsLayout.setUidUsageDuration(uidStats, durationMs);
                powerStats.uidStats.put(uid, uidStats);
            }
        }

        stats.addPowerStats(powerStats, timestampMs);

        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            int[] uidStateValues = new int[stats.getConfig().getUidStateConfig().length];
            uidStateValues[STATE_PROCESS_STATE] = PROCESS_STATE_UNSPECIFIED;

            for (int j = uids.size() - 1; j >= 0; j--) {
                int uid = uids.get(j);
                int[] stateValues = uidStateEstimate.combinedDeviceStateEstimate.stateValues;
                uidStateValues[STATE_SCREEN] = stateValues[STATE_SCREEN];
                uidStateValues[STATE_POWER] = stateValues[STATE_POWER];
                // Erase usage duration for UNSPECIFIED proc state - the app was not running
                if (stats.getUidStats(mTmpUidStatsArray, uid, uidStateValues)) {
                    if (sStatsLayout.getUidUsageDuration(mTmpUidStatsArray) != 0) {
                        sStatsLayout.setUidUsageDuration(mTmpUidStatsArray, 0);
                        stats.setUidStats(uid, uidStateValues, mTmpUidStatsArray);
                    }
                }
            }
        }

        mStartTimestamp = timestampMs;
    }
}
