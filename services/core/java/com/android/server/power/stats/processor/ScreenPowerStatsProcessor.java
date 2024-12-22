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

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_AMBIENT;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_FULL;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import android.os.BatteryStats;
import android.util.Slog;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.UsageBasedPowerEstimator;
import com.android.server.power.stats.format.ScreenPowerStatsLayout;

import java.util.ArrayList;
import java.util.List;

class ScreenPowerStatsProcessor extends PowerStatsProcessor {
    private static final String TAG = "ScreenPowerStatsProcessor";
    private final int mDisplayCount;
    private final UsageBasedPowerEstimator[] mScreenOnPowerEstimators;
    private final UsageBasedPowerEstimator[] mScreenDozePowerEstimators;
    private final UsageBasedPowerEstimator[][] mScreenBrightnessLevelPowerEstimators;
    private PowerStats.Descriptor mLastUsedDescriptor;
    private ScreenPowerStatsLayout mStatsLayout;
    private PowerEstimationPlan mPlan;
    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;

    private static class Intermediates {
        public double power;
    }

    ScreenPowerStatsProcessor(PowerProfile powerProfile) {
        mDisplayCount = powerProfile.getNumDisplays();
        mScreenOnPowerEstimators = new UsageBasedPowerEstimator[mDisplayCount];
        mScreenDozePowerEstimators = new UsageBasedPowerEstimator[mDisplayCount];
        mScreenBrightnessLevelPowerEstimators = new UsageBasedPowerEstimator[mDisplayCount][];
        for (int display = 0; display < mDisplayCount; display++) {
            mScreenOnPowerEstimators[display] = new UsageBasedPowerEstimator(
                    powerProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, display));

            double averagePowerFullBrightness = powerProfile.getAveragePowerForOrdinal(
                    POWER_GROUP_DISPLAY_SCREEN_FULL, display);
            mScreenBrightnessLevelPowerEstimators[display] =
                    new UsageBasedPowerEstimator[BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS];
            for (int bin = 0; bin < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; bin++) {
                // For example, if the number of bins is 3, the corresponding averages
                // are calculated as 0.5 * full, 1.5 * full, 2.5 * full
                final double binPowerMah = averagePowerFullBrightness * (bin + 0.5)
                        / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
                mScreenBrightnessLevelPowerEstimators[display][bin] =
                        new UsageBasedPowerEstimator(binPowerMah);
            }

            mScreenDozePowerEstimators[display] = new UsageBasedPowerEstimator(
                    powerProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, display));
        }
    }

    private boolean unpackPowerStatsDescriptor(PowerStats.Descriptor descriptor) {
        if (descriptor == null) {
            return false;
        }

        if (descriptor.equals(mLastUsedDescriptor)) {
            return true;
        }

        mLastUsedDescriptor = descriptor;
        mStatsLayout = new ScreenPowerStatsLayout(descriptor);
        if (mStatsLayout.getDisplayCount() != mDisplayCount) {
            Slog.e(TAG, "Incompatible number of displays: " + mStatsLayout.getDisplayCount()
                    + ", expected: " + mDisplayCount);
            return false;
        }

        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpUidStatsArray = new long[descriptor.uidStatsArrayLength];
        return true;
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        if (!unpackPowerStatsDescriptor(stats.getPowerStatsDescriptor())) {
            return;
        }

        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        computeDevicePowerEstimates(stats);
        combineDeviceStateEstimates();

        List<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);

        if (!uids.isEmpty()) {
            computeUidPowerEstimates(stats, uids);
        }
        mPlan.resetIntermediates();
    }

    private void computeDevicePowerEstimates(PowerComponentAggregatedPowerStats stats) {
        for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation estimation = mPlan.deviceStateEstimations.get(i);
            if (!stats.getDeviceStats(mTmpDeviceStatsArray, estimation.stateValues)) {
                continue;
            }

            if (estimation.stateValues[STATE_SCREEN] == SCREEN_STATE_ON) {
                double power;
                if (mStatsLayout.getEnergyConsumerCount() > 0) {
                    power = uCtoMah(mStatsLayout.getConsumedEnergy(mTmpDeviceStatsArray, 0));
                } else {
                    power = 0;
                    for (int display = 0; display < mStatsLayout.getDisplayCount(); display++) {
                        power += computeDisplayPower(mTmpDeviceStatsArray, display);
                    }
                }
                mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray, power);
                Intermediates intermediates = new Intermediates();
                intermediates.power = power;
                estimation.intermediates = intermediates;
            } else {
                double power = 0;
                if (mStatsLayout.getEnergyConsumerCount() > 0) {
                    power = uCtoMah(mStatsLayout.getConsumedEnergy(mTmpDeviceStatsArray, 0));
                } else {
                    for (int display = 0; display < mStatsLayout.getDisplayCount(); display++) {
                        power += mScreenDozePowerEstimators[display].calculatePower(
                                mStatsLayout.getScreenDozeDuration(mTmpDeviceStatsArray, display));
                    }
                }
                mStatsLayout.setScreenDozePowerEstimate(mTmpDeviceStatsArray, power);
            }

            stats.setDeviceStats(estimation.stateValues, mTmpDeviceStatsArray);
        }
    }

    private double computeDisplayPower(long[] stats, int display) {
        double power = mScreenOnPowerEstimators[display]
                .calculatePower(mStatsLayout.getScreenOnDuration(stats, display));
        for (int bin = 0; bin < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; bin++) {
            power += mScreenBrightnessLevelPowerEstimators[display][bin]
                    .calculatePower(mStatsLayout.getBrightnessLevelDuration(stats, display, bin));
        }
        return power;
    }

    /**
     * Combine power estimates before distributing them proportionally to UIDs.
     */
    private void combineDeviceStateEstimates() {
        for (int i = mPlan.combinedDeviceStateEstimations.size() - 1; i >= 0; i--) {
            CombinedDeviceStateEstimate cdse = mPlan.combinedDeviceStateEstimations.get(i);
            List<DeviceStateEstimation> deviceStateEstimations = cdse.deviceStateEstimations;
            double power = 0;
            for (int j = deviceStateEstimations.size() - 1; j >= 0; j--) {
                DeviceStateEstimation dse = deviceStateEstimations.get(j);
                Intermediates intermediates = (Intermediates) dse.intermediates;
                if (intermediates != null) {
                    power += intermediates.power;
                }
            }
            if (power != 0) {
                Intermediates cdseIntermediates = new Intermediates();
                cdseIntermediates.power = power;
                cdse.intermediates = cdseIntermediates;
            }
        }
    }

    private void computeUidPowerEstimates(PowerComponentAggregatedPowerStats stats,
            List<Integer> uids) {
        int[] uidStateValues = new int[stats.getConfig().getUidStateConfig().length];
        uidStateValues[STATE_SCREEN] = SCREEN_STATE_ON;
        uidStateValues[STATE_PROCESS_STATE] = PROCESS_STATE_UNSPECIFIED;

        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            Intermediates intermediates =
                    (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;
            int[] deviceStateValues = uidStateEstimate.combinedDeviceStateEstimate
                    .stateValues;
            if (deviceStateValues[STATE_SCREEN] != SCREEN_STATE_ON
                    || intermediates == null) {
                continue;
            }

            uidStateValues[STATE_POWER] = deviceStateValues[STATE_POWER];

            long totalTopActivityDuration = 0;
            for (int j = uids.size() - 1; j >= 0; j--) {
                int uid = uids.get(j);
                if (stats.getUidStats(mTmpUidStatsArray, uid, uidStateValues)) {
                    totalTopActivityDuration +=
                            mStatsLayout.getUidTopActivityDuration(mTmpUidStatsArray);
                }
            }

            if (totalTopActivityDuration == 0) {
                return;
            }

            for (int j = uids.size() - 1; j >= 0; j--) {
                int uid = uids.get(j);
                if (stats.getUidStats(mTmpUidStatsArray, uid, uidStateValues)) {
                    long duration = mStatsLayout.getUidTopActivityDuration(mTmpUidStatsArray);
                    double power = intermediates.power * duration / totalTopActivityDuration;
                    mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
                    stats.setUidStats(uid, uidStateValues, mTmpUidStatsArray);
                }
            }
        }
    }
}
