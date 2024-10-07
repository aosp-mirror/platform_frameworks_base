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

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.UsageBasedPowerEstimator;
import com.android.server.power.stats.format.BluetoothPowerStatsLayout;

import java.util.ArrayList;
import java.util.List;

class BluetoothPowerStatsProcessor extends PowerStatsProcessor {
    private final UsageBasedPowerEstimator mRxPowerEstimator;
    private final UsageBasedPowerEstimator mTxPowerEstimator;
    private final UsageBasedPowerEstimator mIdlePowerEstimator;

    private PowerStats.Descriptor mLastUsedDescriptor;
    private BluetoothPowerStatsLayout mStatsLayout;
    // Sequence of steps for power estimation and intermediate results.
    private PowerEstimationPlan mPlan;

    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;

    BluetoothPowerStatsProcessor(PowerProfile powerProfile) {
        mRxPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX));
        mTxPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX));
        mIdlePowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE));
    }

    private static class Intermediates {
        /**
         * Number of received bytes
         */
        public long rxBytes;
        /**
         * Duration of receiving
         */
        public long rxTime;
        /**
         * Estimated power for the RX state.
         */
        public double rxPower;
        /**
         * Number of transmitted bytes
         */
        public long txBytes;
        /**
         * Duration of transmitting
         */
        public long txTime;
        /**
         * Estimated power for the TX state.
         */
        public double txPower;
        /**
         * Estimated power for IDLE, SCAN states.
         */
        public double idlePower;
        /**
         * Total scan time.
         */
        public long scanTime;
        /**
         * Measured consumed energy from power monitoring hardware (micro-coulombs)
         */
        public long consumedEnergy;
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        if (stats.getPowerStatsDescriptor() == null) {
            return;
        }

        unpackPowerStatsDescriptor(stats.getPowerStatsDescriptor());

        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation estimation = mPlan.deviceStateEstimations.get(i);
            Intermediates intermediates = new Intermediates();
            estimation.intermediates = intermediates;
            computeDevicePowerEstimates(stats, estimation.stateValues, intermediates);
        }

        double ratio = 1.0;
        if (mStatsLayout.getEnergyConsumerCount() != 0) {
            ratio = computeEstimateAdjustmentRatioUsingConsumedEnergy();
            if (ratio != 1) {
                for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
                    DeviceStateEstimation estimation = mPlan.deviceStateEstimations.get(i);
                    adjustDevicePowerEstimates(stats, estimation.stateValues,
                            (Intermediates) estimation.intermediates, ratio);
                }
            }
        }

        combineDeviceStateEstimates();

        ArrayList<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);
        if (!uids.isEmpty()) {
            for (int uid : uids) {
                for (int i = 0; i < mPlan.uidStateEstimates.size(); i++) {
                    computeUidActivityTotals(stats, uid, mPlan.uidStateEstimates.get(i));
                }
            }

            for (int uid : uids) {
                for (int i = 0; i < mPlan.uidStateEstimates.size(); i++) {
                    computeUidPowerEstimates(stats, uid, mPlan.uidStateEstimates.get(i));
                }
            }
        }
        mPlan.resetIntermediates();
    }

    private void unpackPowerStatsDescriptor(PowerStats.Descriptor descriptor) {
        if (descriptor.equals(mLastUsedDescriptor)) {
            return;
        }

        mLastUsedDescriptor = descriptor;
        mStatsLayout = new BluetoothPowerStatsLayout(descriptor);
        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpUidStatsArray = new long[descriptor.uidStatsArrayLength];
    }

    /**
     * Compute power estimates using the power profile.
     */
    private void computeDevicePowerEstimates(PowerComponentAggregatedPowerStats stats,
            int[] deviceStates, Intermediates intermediates) {
        if (!stats.getDeviceStats(mTmpDeviceStatsArray, deviceStates)) {
            return;
        }

        for (int i = mStatsLayout.getEnergyConsumerCount() - 1; i >= 0; i--) {
            intermediates.consumedEnergy += mStatsLayout.getConsumedEnergy(mTmpDeviceStatsArray, i);
        }

        intermediates.rxTime = mStatsLayout.getDeviceRxTime(mTmpDeviceStatsArray);
        intermediates.txTime = mStatsLayout.getDeviceTxTime(mTmpDeviceStatsArray);
        intermediates.scanTime = mStatsLayout.getDeviceScanTime(mTmpDeviceStatsArray);
        long idleTime = mStatsLayout.getDeviceIdleTime(mTmpDeviceStatsArray);

        intermediates.rxPower = mRxPowerEstimator.calculatePower(intermediates.rxTime);
        intermediates.txPower = mTxPowerEstimator.calculatePower(intermediates.txTime);
        intermediates.idlePower = mIdlePowerEstimator.calculatePower(idleTime);
        mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                intermediates.rxPower + intermediates.txPower + intermediates.idlePower);
        stats.setDeviceStats(deviceStates, mTmpDeviceStatsArray);
    }

    /**
     * Compute an adjustment ratio using the total power estimated using the power profile
     * and the total power measured by hardware.
     */
    private double computeEstimateAdjustmentRatioUsingConsumedEnergy() {
        long totalConsumedEnergy = 0;
        double totalPower = 0;

        for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            Intermediates intermediates =
                    (Intermediates) mPlan.deviceStateEstimations.get(i).intermediates;
            totalPower += intermediates.rxPower + intermediates.txPower + intermediates.idlePower;
            totalConsumedEnergy += intermediates.consumedEnergy;
        }

        if (totalPower == 0) {
            return 1;
        }

        return uCtoMah(totalConsumedEnergy) / totalPower;
    }

    /**
     * Uniformly apply the same adjustment to all power estimates in order to ensure that the total
     * estimated power matches the measured consumed power.  We are not claiming that all
     * averages captured in the power profile have to be off by the same percentage in reality.
     */
    private void adjustDevicePowerEstimates(PowerComponentAggregatedPowerStats stats,
            int[] deviceStates, Intermediates intermediates, double ratio) {
        double adjutedPower;
        intermediates.rxPower *= ratio;
        intermediates.txPower *= ratio;
        intermediates.idlePower *= ratio;
        adjutedPower = intermediates.rxPower + intermediates.txPower + intermediates.idlePower;

        if (!stats.getDeviceStats(mTmpDeviceStatsArray, deviceStates)) {
            return;
        }

        mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray, adjutedPower);
        stats.setDeviceStats(deviceStates, mTmpDeviceStatsArray);
    }

    /**
     * Combine power estimates before distributing them proportionally to UIDs.
     */
    private void combineDeviceStateEstimates() {
        for (int i = mPlan.combinedDeviceStateEstimations.size() - 1; i >= 0; i--) {
            CombinedDeviceStateEstimate cdse = mPlan.combinedDeviceStateEstimations.get(i);
            Intermediates cdseIntermediates = new Intermediates();
            cdse.intermediates = cdseIntermediates;
            List<DeviceStateEstimation> deviceStateEstimations = cdse.deviceStateEstimations;
            for (int j = deviceStateEstimations.size() - 1; j >= 0; j--) {
                DeviceStateEstimation dse = deviceStateEstimations.get(j);
                Intermediates intermediates = (Intermediates) dse.intermediates;
                cdseIntermediates.rxTime += intermediates.rxTime;
                cdseIntermediates.rxBytes += intermediates.rxBytes;
                cdseIntermediates.rxPower += intermediates.rxPower;
                cdseIntermediates.txTime += intermediates.txTime;
                cdseIntermediates.txBytes += intermediates.txBytes;
                cdseIntermediates.txPower += intermediates.txPower;
                cdseIntermediates.idlePower += intermediates.idlePower;
                cdseIntermediates.scanTime += intermediates.scanTime;
                cdseIntermediates.consumedEnergy += intermediates.consumedEnergy;
            }
        }
    }

    private void computeUidActivityTotals(PowerComponentAggregatedPowerStats stats, int uid,
            UidStateEstimate uidStateEstimate) {
        Intermediates intermediates =
                (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;
        for (UidStateProportionalEstimate proportionalEstimate :
                uidStateEstimate.proportionalEstimates) {
            if (!stats.getUidStats(mTmpUidStatsArray, uid, proportionalEstimate.stateValues)) {
                continue;
            }

            intermediates.rxBytes += mStatsLayout.getUidRxBytes(mTmpUidStatsArray);
            intermediates.txBytes += mStatsLayout.getUidTxBytes(mTmpUidStatsArray);
        }
    }

    private void computeUidPowerEstimates(PowerComponentAggregatedPowerStats stats, int uid,
            UidStateEstimate uidStateEstimate) {
        Intermediates intermediates =
                (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;

        // Scan is more expensive than data transfer, so in the presence of large
        // of scanning duration, blame apps according to the time they spent scanning.
        // This may disproportionately blame apps that do a lot of scanning, which is
        // the tread-off we are making in the absence of more detailed metrics.
        boolean normalizeRxByScanTime = intermediates.scanTime > intermediates.rxTime;
        boolean normalizeTxByScanTime = intermediates.scanTime > intermediates.txTime;

        for (UidStateProportionalEstimate proportionalEstimate :
                uidStateEstimate.proportionalEstimates) {
            if (!stats.getUidStats(mTmpUidStatsArray, uid, proportionalEstimate.stateValues)) {
                continue;
            }

            double power = 0;
            if (normalizeRxByScanTime) {
                if (intermediates.scanTime != 0) {
                    power += intermediates.rxPower * mStatsLayout.getUidScanTime(mTmpUidStatsArray)
                            / intermediates.scanTime;
                }
            } else {
                if (intermediates.rxBytes != 0) {
                    power += intermediates.rxPower * mStatsLayout.getUidRxBytes(mTmpUidStatsArray)
                            / intermediates.rxBytes;
                }
            }
            if (normalizeTxByScanTime) {
                if (intermediates.scanTime != 0) {
                    power += intermediates.txPower * mStatsLayout.getUidScanTime(mTmpUidStatsArray)
                            / intermediates.scanTime;
                }
            } else {
                if (intermediates.txBytes != 0) {
                    power += intermediates.txPower * mStatsLayout.getUidTxBytes(mTmpUidStatsArray)
                            / intermediates.txBytes;
                }
            }
            mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
            stats.setUidStats(uid, proportionalEstimate.stateValues, mTmpUidStatsArray);
        }
    }
}
