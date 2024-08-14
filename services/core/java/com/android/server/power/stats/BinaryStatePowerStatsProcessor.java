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

package com.android.server.power.stats;

import android.annotation.IntDef;
import android.os.BatteryStats;
import android.os.PersistableBundle;
import android.os.Process;

import com.android.internal.os.PowerStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

abstract class BinaryStatePowerStatsProcessor extends PowerStatsProcessor {
    static final int STATE_OFF = 0;
    static final int STATE_ON = 1;

    @IntDef(flag = true, prefix = {"STATE_"}, value = {
            STATE_OFF,
            STATE_ON,
    })
    @Retention(RetentionPolicy.SOURCE)
    protected @interface BinaryState {
    }

    private final int mPowerComponentId;
    private final PowerStatsUidResolver mUidResolver;
    private final UsageBasedPowerEstimator mUsageBasedPowerEstimator;
    private boolean mEnergyConsumerSupported;
    private int mInitiatingUid = Process.INVALID_UID;
    private @BinaryState int mLastState = STATE_OFF;
    private long mLastStateTimestamp;
    private long mLastUpdateTimestamp;

    private PowerStats.Descriptor mDescriptor;
    private final BinaryStatePowerStatsLayout mStatsLayout = new BinaryStatePowerStatsLayout();
    private PowerStats mPowerStats;
    private PowerEstimationPlan mPlan;
    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;

    BinaryStatePowerStatsProcessor(int powerComponentId,
            PowerStatsUidResolver uidResolver, double averagePowerMilliAmp) {
        mPowerComponentId = powerComponentId;
        mUsageBasedPowerEstimator = new UsageBasedPowerEstimator(averagePowerMilliAmp);
        mUidResolver = uidResolver;
    }

    protected abstract @BinaryState int getBinaryState(BatteryStats.HistoryItem item);

    private void ensureInitialized() {
        if (mDescriptor != null) {
            return;
        }

        PersistableBundle extras = new PersistableBundle();
        mStatsLayout.toExtras(extras);
        mDescriptor = new PowerStats.Descriptor(mPowerComponentId,
                mStatsLayout.getDeviceStatsArrayLength(), null, 0,
                mStatsLayout.getUidStatsArrayLength(), extras);
        mPowerStats = new PowerStats(mDescriptor);
        mPowerStats.stats = new long[mDescriptor.statsArrayLength];
        mTmpDeviceStatsArray = new long[mDescriptor.statsArrayLength];
        mTmpUidStatsArray = new long[mDescriptor.uidStatsArrayLength];
    }

    @Override
    void start(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        ensureInitialized();

        // Establish a baseline at the beginning of an accumulation pass
        mLastState = STATE_OFF;
        mLastStateTimestamp = timestampMs;
        mInitiatingUid = Process.INVALID_UID;
        flushPowerStats(stats, mLastStateTimestamp);
    }

    @Override
    void noteStateChange(PowerComponentAggregatedPowerStats stats,
            BatteryStats.HistoryItem item) {
        @BinaryState int state = getBinaryState(item);
        if (state == mLastState) {
            return;
        }

        if (state == STATE_ON) {
            if (item.eventCode == (BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_START)) {
                mInitiatingUid = mUidResolver.mapUid(item.eventTag.uid);
            }
        } else {
            recordUsageDuration(item.time);
            mInitiatingUid = Process.INVALID_UID;
            if (!mEnergyConsumerSupported) {
                flushPowerStats(stats, item.time);
            }
        }
        mLastStateTimestamp = item.time;
        mLastState = state;
    }

    private void recordUsageDuration(long time) {
        if (mLastState == STATE_OFF) {
            return;
        }

        long durationMs = time - mLastStateTimestamp;
        mStatsLayout.setUsageDuration(mPowerStats.stats,
                mStatsLayout.getUsageDuration(mPowerStats.stats) + durationMs);

        if (mInitiatingUid != Process.INVALID_UID) {
            long[] uidStats = mPowerStats.uidStats.get(mInitiatingUid);
            if (uidStats == null) {
                uidStats = new long[mDescriptor.uidStatsArrayLength];
                mPowerStats.uidStats.put(mInitiatingUid, uidStats);
                mStatsLayout.setUidUsageDuration(uidStats, durationMs);
            } else {
                mStatsLayout.setUsageDuration(mPowerStats.stats,
                        mStatsLayout.getUsageDuration(mPowerStats.stats) + durationMs);
            }
        }
        mLastStateTimestamp = time;
    }

    void addPowerStats(PowerComponentAggregatedPowerStats stats, PowerStats powerStats,
            long timestampMs) {
        ensureInitialized();
        recordUsageDuration(timestampMs);
        long consumedEnergy = mStatsLayout.getConsumedEnergy(powerStats.stats, 0);
        if (consumedEnergy != BatteryStats.POWER_DATA_UNAVAILABLE) {
            mEnergyConsumerSupported = true;
            mStatsLayout.setConsumedEnergy(mPowerStats.stats, 0, consumedEnergy);
        }

        flushPowerStats(stats, timestampMs);
    }

    private void flushPowerStats(PowerComponentAggregatedPowerStats stats, long timestamp) {
        mPowerStats.durationMs = timestamp - mLastUpdateTimestamp;
        stats.addPowerStats(mPowerStats, timestamp);

        Arrays.fill(mPowerStats.stats, 0);
        mPowerStats.uidStats.clear();
        mLastUpdateTimestamp = timestamp;
    }

    private static class Intermediates {
        public long duration;
        public double power;
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        recordUsageDuration(timestampMs);
        flushPowerStats(stats, timestampMs);

        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        computeDevicePowerEstimates(stats);
        combineDevicePowerEstimates(stats);

        List<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);

        computeUidActivityTotals(stats, uids);
        computeUidPowerEstimates(stats, uids);
    }

    private void computeDevicePowerEstimates(PowerComponentAggregatedPowerStats stats) {
        for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation estimation = mPlan.deviceStateEstimations.get(i);
            if (!stats.getDeviceStats(mTmpDeviceStatsArray, estimation.stateValues)) {
                continue;
            }

            long duration = mStatsLayout.getUsageDuration(mTmpDeviceStatsArray);
            if (duration > 0) {
                double power;
                if (mEnergyConsumerSupported) {
                    power = uCtoMah(mStatsLayout.getConsumedEnergy(mTmpDeviceStatsArray, 0));
                } else {
                    power = mUsageBasedPowerEstimator.calculatePower(duration);
                }
                mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray, power);
                stats.setDeviceStats(estimation.stateValues, mTmpDeviceStatsArray);
            }
        }
    }

    private void combineDevicePowerEstimates(PowerComponentAggregatedPowerStats stats) {
        for (int i = mPlan.combinedDeviceStateEstimations.size() - 1; i >= 0; i--) {
            CombinedDeviceStateEstimate estimation =
                    mPlan.combinedDeviceStateEstimations.get(i);
            Intermediates intermediates = new Intermediates();
            estimation.intermediates = intermediates;
            for (int j = estimation.deviceStateEstimations.size() - 1; j >= 0; j--) {
                DeviceStateEstimation deviceStateEstimation =
                        estimation.deviceStateEstimations.get(j);
                if (!stats.getDeviceStats(mTmpDeviceStatsArray,
                        deviceStateEstimation.stateValues)) {
                    continue;
                }
                intermediates.power += mStatsLayout.getDevicePowerEstimate(mTmpDeviceStatsArray);
            }
        }
    }

    private void computeUidActivityTotals(PowerComponentAggregatedPowerStats stats,
            List<Integer> uids) {
        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            Intermediates intermediates =
                    (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;
            for (int j = uids.size() - 1; j >= 0; j--) {
                int uid = uids.get(j);
                for (UidStateProportionalEstimate proportionalEstimate :
                        uidStateEstimate.proportionalEstimates) {
                    if (stats.getUidStats(mTmpUidStatsArray, uid,
                            proportionalEstimate.stateValues)) {
                        intermediates.duration +=
                                mStatsLayout.getUidUsageDuration(mTmpUidStatsArray);
                    }
                }
            }
        }
    }

    private void computeUidPowerEstimates(PowerComponentAggregatedPowerStats stats,
            List<Integer> uids) {
        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            Intermediates intermediates =
                    (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;
            if (intermediates.duration == 0) {
                continue;
            }
            List<UidStateProportionalEstimate> proportionalEstimates =
                    uidStateEstimate.proportionalEstimates;
            for (int j = proportionalEstimates.size() - 1; j >= 0; j--) {
                UidStateProportionalEstimate proportionalEstimate = proportionalEstimates.get(j);
                for (int k = uids.size() - 1; k >= 0; k--) {
                    int uid = uids.get(k);
                    if (stats.getUidStats(mTmpUidStatsArray, uid,
                            proportionalEstimate.stateValues)) {
                        double power = intermediates.power
                                * mStatsLayout.getUidUsageDuration(mTmpUidStatsArray)
                                / intermediates.duration;
                        mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
                        stats.setUidStats(uid, proportionalEstimate.stateValues,
                                mTmpUidStatsArray);
                    }
                }
            }
        }
    }
}
