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

import android.util.Slog;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WifiPowerStatsProcessor extends PowerStatsProcessor {
    private static final String TAG = "WifiPowerStatsProcessor";
    private static final boolean DEBUG = false;

    private final UsageBasedPowerEstimator mRxPowerEstimator;
    private final UsageBasedPowerEstimator mTxPowerEstimator;
    private final UsageBasedPowerEstimator mIdlePowerEstimator;

    private final UsageBasedPowerEstimator mActivePowerEstimator;
    private final UsageBasedPowerEstimator mScanPowerEstimator;
    private final UsageBasedPowerEstimator mBatchedScanPowerEstimator;

    private PowerStats.Descriptor mLastUsedDescriptor;
    private WifiPowerStatsLayout mStatsLayout;
    // Sequence of steps for power estimation and intermediate results.
    private PowerEstimationPlan mPlan;

    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;
    private boolean mHasWifiPowerController;

    public WifiPowerStatsProcessor(PowerProfile powerProfile) {
        mRxPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX));
        mTxPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX));
        mIdlePowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE));
        mActivePowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE));
        mScanPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_SCAN));
        mBatchedScanPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN));
    }

    private static class Intermediates {
        /**
         * Estimated power for the RX state.
         */
        public double rxPower;
        /**
         * Estimated power for the TX state.
         */
        public double txPower;
        /**
         * Estimated power in the SCAN state
         */
        public double scanPower;
        /**
         * Estimated power for IDLE, SCAN states.
         */
        public double idlePower;
        /**
         * Number of received packets
         */
        public long rxPackets;
        /**
         * Number of transmitted packets
         */
        public long txPackets;
        /**
         * Total duration of unbatched scans across all UIDs.
         */
        public long basicScanDuration;
        /**
         * Estimated power in the unbatched SCAN state
         */
        public double basicScanPower;
        /**
         * Total duration of batched scans across all UIDs.
         */
        public long batchedScanDuration;
        /**
         * Estimated power in the BATCHED SCAN state
         */
        public double batchedScanPower;
        /**
         * Estimated total power when active; used only in the absence of WiFiManager power
         * reporting.
         */
        public double activePower;
        /**
         * Measured consumed energy from power monitoring hardware (micro-coulombs)
         */
        public long consumedEnergy;
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats) {
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
        mStatsLayout = new WifiPowerStatsLayout(descriptor);
        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpUidStatsArray = new long[descriptor.uidStatsArrayLength];
        mHasWifiPowerController = mStatsLayout.isPowerReportingSupported();
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

        intermediates.basicScanDuration =
                mStatsLayout.getDeviceBasicScanTime(mTmpDeviceStatsArray);
        intermediates.batchedScanDuration =
                mStatsLayout.getDeviceBatchedScanTime(mTmpDeviceStatsArray);
        if (mHasWifiPowerController) {
            intermediates.rxPower = mRxPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceRxTime(mTmpDeviceStatsArray));
            intermediates.txPower = mTxPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceTxTime(mTmpDeviceStatsArray));
            intermediates.scanPower = mScanPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceScanTime(mTmpDeviceStatsArray));
            intermediates.idlePower = mIdlePowerEstimator.calculatePower(
                    mStatsLayout.getDeviceIdleTime(mTmpDeviceStatsArray));
            mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                    intermediates.rxPower + intermediates.txPower + intermediates.scanPower
                            + intermediates.idlePower);
        } else {
            intermediates.activePower = mActivePowerEstimator.calculatePower(
                    mStatsLayout.getDeviceActiveTime(mTmpDeviceStatsArray));
            intermediates.basicScanPower =
                    mScanPowerEstimator.calculatePower(intermediates.basicScanDuration);
            intermediates.batchedScanPower =
                    mBatchedScanPowerEstimator.calculatePower(intermediates.batchedScanDuration);
            mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                    intermediates.activePower + intermediates.basicScanPower
                            + intermediates.batchedScanPower);
        }

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
            if (mHasWifiPowerController) {
                totalPower += intermediates.rxPower + intermediates.txPower
                        + intermediates.scanPower + intermediates.idlePower;
            } else {
                totalPower += intermediates.activePower + intermediates.basicScanPower
                        + intermediates.batchedScanPower;
            }
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
        if (mHasWifiPowerController) {
            intermediates.rxPower *= ratio;
            intermediates.txPower *= ratio;
            intermediates.scanPower *= ratio;
            intermediates.idlePower *= ratio;
            adjutedPower = intermediates.rxPower + intermediates.txPower + intermediates.scanPower
                    + intermediates.idlePower;
        } else {
            intermediates.activePower *= ratio;
            intermediates.basicScanPower *= ratio;
            intermediates.batchedScanPower *= ratio;
            adjutedPower = intermediates.activePower + intermediates.basicScanPower
                    + intermediates.batchedScanPower;
        }

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
            Intermediates
                    cdseIntermediates = new Intermediates();
            cdse.intermediates = cdseIntermediates;
            List<DeviceStateEstimation> deviceStateEstimations = cdse.deviceStateEstimations;
            for (int j = deviceStateEstimations.size() - 1; j >= 0; j--) {
                DeviceStateEstimation dse = deviceStateEstimations.get(j);
                Intermediates intermediates = (Intermediates) dse.intermediates;
                if (mHasWifiPowerController) {
                    cdseIntermediates.rxPower += intermediates.rxPower;
                    cdseIntermediates.txPower += intermediates.txPower;
                    cdseIntermediates.scanPower += intermediates.scanPower;
                    cdseIntermediates.idlePower += intermediates.idlePower;
                } else {
                    cdseIntermediates.activePower += intermediates.activePower;
                    cdseIntermediates.basicScanPower += intermediates.basicScanPower;
                    cdseIntermediates.batchedScanPower += intermediates.batchedScanPower;
                }
                cdseIntermediates.basicScanDuration += intermediates.basicScanDuration;
                cdseIntermediates.batchedScanDuration += intermediates.batchedScanDuration;
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

            intermediates.rxPackets += mStatsLayout.getUidRxPackets(mTmpUidStatsArray);
            intermediates.txPackets += mStatsLayout.getUidTxPackets(mTmpUidStatsArray);
        }
    }

    private void computeUidPowerEstimates(PowerComponentAggregatedPowerStats stats, int uid,
            UidStateEstimate uidStateEstimate) {
        Intermediates intermediates =
                (Intermediates) uidStateEstimate.combinedDeviceStateEstimate.intermediates;
        for (UidStateProportionalEstimate proportionalEstimate :
                uidStateEstimate.proportionalEstimates) {
            if (!stats.getUidStats(mTmpUidStatsArray, uid, proportionalEstimate.stateValues)) {
                continue;
            }

            double power = 0;
            if (mHasWifiPowerController) {
                if (intermediates.rxPackets != 0) {
                    power += intermediates.rxPower * mStatsLayout.getUidRxPackets(mTmpUidStatsArray)
                            / intermediates.rxPackets;
                }
                if (intermediates.txPackets != 0) {
                    power += intermediates.txPower * mStatsLayout.getUidTxPackets(mTmpUidStatsArray)
                            / intermediates.txPackets;
                }
                long totalScanDuration =
                        intermediates.basicScanDuration + intermediates.batchedScanDuration;
                if (totalScanDuration != 0) {
                    long scanDuration = mStatsLayout.getUidScanTime(mTmpUidStatsArray)
                            + mStatsLayout.getUidBatchedScanTime(mTmpUidStatsArray);
                    power += intermediates.scanPower * scanDuration / totalScanDuration;
                }
            } else {
                long totalPackets = intermediates.rxPackets + intermediates.txPackets;
                if (totalPackets != 0) {
                    long packets = mStatsLayout.getUidRxPackets(mTmpUidStatsArray)
                            + mStatsLayout.getUidTxPackets(mTmpUidStatsArray);
                    power += intermediates.activePower * packets / totalPackets;
                }

                if (intermediates.basicScanDuration != 0) {
                    long scanDuration = mStatsLayout.getUidScanTime(mTmpUidStatsArray);
                    power += intermediates.basicScanPower * scanDuration
                            / intermediates.basicScanDuration;
                }

                if (intermediates.batchedScanDuration != 0) {
                    long batchedScanDuration = mStatsLayout.getUidBatchedScanTime(
                            mTmpUidStatsArray);
                    power += intermediates.batchedScanPower * batchedScanDuration
                            / intermediates.batchedScanDuration;
                }
            }
            mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
            stats.setUidStats(uid, proportionalEstimate.stateValues, mTmpUidStatsArray);

            if (DEBUG) {
                Slog.d(TAG, "UID: " + uid
                        + " states: " + Arrays.toString(proportionalEstimate.stateValues)
                        + " stats: " + Arrays.toString(mTmpUidStatsArray)
                        + " rx: " + mStatsLayout.getUidRxPackets(mTmpUidStatsArray)
                        + " rx-power: " + intermediates.rxPower
                        + " rx-packets: " + intermediates.rxPackets
                        + " tx: " + mStatsLayout.getUidTxPackets(mTmpUidStatsArray)
                        + " tx-power: " + intermediates.txPower
                        + " tx-packets: " + intermediates.txPackets
                        + " power: " + power);
            }
        }
    }

    @Override
    String deviceStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
        unpackPowerStatsDescriptor(descriptor);
        if (mHasWifiPowerController) {
            return "rx: " + mStatsLayout.getDeviceRxTime(stats)
                    + " tx: " + mStatsLayout.getDeviceTxTime(stats)
                    + " scan: " + mStatsLayout.getDeviceScanTime(stats)
                    + " idle: " + mStatsLayout.getDeviceIdleTime(stats)
                    + " power: " + mStatsLayout.getDevicePowerEstimate(stats);
        } else {
            return "active: " + mStatsLayout.getDeviceActiveTime(stats)
                    + " scan: " + mStatsLayout.getDeviceBasicScanTime(stats)
                    + " batched-scan: " + mStatsLayout.getDeviceBatchedScanTime(stats)
                    + " power: " + mStatsLayout.getDevicePowerEstimate(stats);
        }
    }

    @Override
    String stateStatsToString(PowerStats.Descriptor descriptor, int key, long[] stats) {
        // Unsupported for this power component
        return null;
    }

    @Override
    String uidStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
        unpackPowerStatsDescriptor(descriptor);
        return "rx: " + mStatsLayout.getUidRxPackets(stats)
                + " tx: " + mStatsLayout.getUidTxPackets(stats)
                + " scan: " + mStatsLayout.getUidScanTime(stats)
                + " batched-scan: " + mStatsLayout.getUidBatchedScanTime(stats)
                + " power: " + mStatsLayout.getUidPowerEstimate(stats);
    }
}
