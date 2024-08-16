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

import android.location.GnssSignalQuality;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Process;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import java.util.Arrays;

public class GnssPowerStatsProcessor extends BinaryStatePowerStatsProcessor {
    private int mGnssSignalLevel = GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN;
    private long mGnssSignalLevelTimestamp;
    private final long[] mGnssSignalDurations =
            new long[GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS];
    private static final GnssPowerStatsLayout sStatsLayout = new GnssPowerStatsLayout();
    private final UsageBasedPowerEstimator[] mSignalLevelEstimators =
            new UsageBasedPowerEstimator[GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS];
    private final boolean mUseSignalLevelEstimators;
    private long[] mTmpDeviceStatsArray;

    public GnssPowerStatsProcessor(PowerProfile powerProfile, PowerStatsUidResolver uidResolver) {
        super(BatteryConsumer.POWER_COMPONENT_GNSS, uidResolver,
                powerProfile.getAveragePower(PowerProfile.POWER_GPS_ON),
                sStatsLayout);

        boolean useSignalLevelEstimators = false;
        for (int level = 0; level < GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS; level++) {
            double power = powerProfile.getAveragePower(
                    PowerProfile.POWER_GPS_SIGNAL_QUALITY_BASED, level);
            if (power != 0) {
                useSignalLevelEstimators = true;
            }
            mSignalLevelEstimators[level] = new UsageBasedPowerEstimator(power);
        }
        mUseSignalLevelEstimators = useSignalLevelEstimators;
    }

    @Override
    protected @BinaryState int getBinaryState(BatteryStats.HistoryItem item) {
        if ((item.states & BatteryStats.HistoryItem.STATE_GPS_ON_FLAG) == 0) {
            mGnssSignalLevel = GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN;
            return STATE_OFF;
        }

        noteGnssSignalLevel(item);
        return STATE_ON;
    }

    private void noteGnssSignalLevel(BatteryStats.HistoryItem item) {
        int signalLevel = (item.states2 & BatteryStats.HistoryItem.STATE2_GPS_SIGNAL_QUALITY_MASK)
                >> BatteryStats.HistoryItem.STATE2_GPS_SIGNAL_QUALITY_SHIFT;
        if (signalLevel >= GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS) {
            signalLevel = GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN;
        }
        if (signalLevel == mGnssSignalLevel) {
            return;
        }

        if (mGnssSignalLevel != GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN) {
            mGnssSignalDurations[mGnssSignalLevel] += item.time - mGnssSignalLevelTimestamp;
        }
        mGnssSignalLevel = signalLevel;
        mGnssSignalLevelTimestamp = item.time;
    }

    @Override
    protected void recordUsageDuration(PowerStats powerStats, int uid, long time) {
        super.recordUsageDuration(powerStats, uid, time);

        if (mGnssSignalLevel != GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN) {
            mGnssSignalDurations[mGnssSignalLevel] += time - mGnssSignalLevelTimestamp;
        } else if (mUseSignalLevelEstimators) {
            // Default GNSS signal quality to GOOD for the purposes of power attribution
            mGnssSignalDurations[GnssSignalQuality.GNSS_SIGNAL_QUALITY_GOOD] +=
                    time - mGnssSignalLevelTimestamp;
        }

        for (int level = 0; level < GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS; level++) {
            long duration = mGnssSignalDurations[level];
            sStatsLayout.setDeviceSignalLevelTime(powerStats.stats, level, duration);
            if (uid != Process.INVALID_UID) {
                long[] uidStats = powerStats.uidStats.get(uid);
                if (uidStats == null) {
                    uidStats = new long[powerStats.descriptor.uidStatsArrayLength];
                    powerStats.uidStats.put(uid, uidStats);
                    sStatsLayout.setUidSignalLevelTime(uidStats, level, duration);
                } else {
                    sStatsLayout.setUidSignalLevelTime(uidStats, level,
                            sStatsLayout.getUidSignalLevelTime(uidStats, level) + duration);
                }
            }
        }

        mGnssSignalLevelTimestamp = time;
        Arrays.fill(mGnssSignalDurations, 0);
    }

    protected void computeDevicePowerEstimates(PowerComponentAggregatedPowerStats stats,
            PowerEstimationPlan plan, boolean energyConsumerSupported) {
        if (!mUseSignalLevelEstimators || energyConsumerSupported) {
            super.computeDevicePowerEstimates(stats, plan, energyConsumerSupported);
            return;
        }

        if (mTmpDeviceStatsArray == null) {
            mTmpDeviceStatsArray = new long[stats.getPowerStatsDescriptor().statsArrayLength];
        }

        for (int i = plan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation estimation = plan.deviceStateEstimations.get(i);
            if (!stats.getDeviceStats(mTmpDeviceStatsArray, estimation.stateValues)) {
                continue;
            }

            double power = 0;
            for (int level = 0; level < GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS; level++) {
                long duration = sStatsLayout.getDeviceSignalLevelTime(mTmpDeviceStatsArray, level);
                power += mSignalLevelEstimators[level].calculatePower(duration);
            }
            sStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray, power);
            stats.setDeviceStats(estimation.stateValues, mTmpDeviceStatsArray);
        }
    }
}
