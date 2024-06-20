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

import android.os.BatteryStats;
import android.telephony.CellSignalStrength;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.internal.power.ModemPowerProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MobileRadioPowerStatsProcessor extends PowerStatsProcessor {
    private static final String TAG = "MobileRadioPowerStatsProcessor";
    private static final boolean DEBUG = false;

    private static final int NUM_SIGNAL_STRENGTH_LEVELS =
            CellSignalStrength.getNumSignalStrengthLevels();
    private static final int IGNORE = -1;

    private final UsageBasedPowerEstimator mSleepPowerEstimator;
    private final UsageBasedPowerEstimator mIdlePowerEstimator;
    private final UsageBasedPowerEstimator mCallPowerEstimator;
    private final UsageBasedPowerEstimator mScanPowerEstimator;

    private static class RxTxPowerEstimators {
        UsageBasedPowerEstimator mRxPowerEstimator;
        UsageBasedPowerEstimator[] mTxPowerEstimators =
                new UsageBasedPowerEstimator[ModemActivityInfo.getNumTxPowerLevels()];
    }

    private final SparseArray<RxTxPowerEstimators> mRxTxPowerEstimators = new SparseArray<>();

    private PowerStats.Descriptor mLastUsedDescriptor;
    private MobileRadioPowerStatsLayout mStatsLayout;
    // Sequence of steps for power estimation and intermediate results.
    private PowerEstimationPlan mPlan;

    private long[] mTmpDeviceStatsArray;
    private long[] mTmpStateStatsArray;
    private long[] mTmpUidStatsArray;

    public MobileRadioPowerStatsProcessor(PowerProfile powerProfile) {
        final double sleepDrainRateMa = powerProfile.getAverageBatteryDrainOrDefaultMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP,
                Double.NaN);
        if (Double.isNaN(sleepDrainRateMa)) {
            mSleepPowerEstimator = null;
        } else {
            mSleepPowerEstimator = new UsageBasedPowerEstimator(sleepDrainRateMa);
        }

        final double idleDrainRateMa = powerProfile.getAverageBatteryDrainOrDefaultMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE,
                Double.NaN);
        if (Double.isNaN(idleDrainRateMa)) {
            mIdlePowerEstimator = null;
        } else {
            mIdlePowerEstimator = new UsageBasedPowerEstimator(idleDrainRateMa);
        }

        // Instantiate legacy power estimators
        double powerRadioActiveMa =
                powerProfile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ACTIVE, Double.NaN);
        if (Double.isNaN(powerRadioActiveMa)) {
            double sum = 0;
            sum += powerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
            for (int i = 0; i < NUM_SIGNAL_STRENGTH_LEVELS; i++) {
                sum += powerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
            }
            powerRadioActiveMa = sum / (NUM_SIGNAL_STRENGTH_LEVELS + 1);
        }
        mCallPowerEstimator = new UsageBasedPowerEstimator(powerRadioActiveMa);

        mScanPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_SCANNING, 0));

        for (int rat = 0; rat < BatteryStats.RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
            final int freqCount = rat == BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR
                    ? ServiceState.FREQUENCY_RANGE_COUNT : 1;
            for (int freqRange = 0; freqRange < freqCount; freqRange++) {
                mRxTxPowerEstimators.put(
                        MobileRadioPowerStatsCollector.makeStateKey(rat, freqRange),
                        buildRxTxPowerEstimators(powerProfile, rat, freqRange));
            }
        }
    }

    private static RxTxPowerEstimators buildRxTxPowerEstimators(PowerProfile powerProfile, int rat,
            int freqRange) {
        RxTxPowerEstimators estimators = new RxTxPowerEstimators();
        long rxKey = ModemPowerProfile.getAverageBatteryDrainKey(
                ModemPowerProfile.MODEM_DRAIN_TYPE_RX, rat, freqRange, IGNORE);
        double rxDrainRateMa = powerProfile.getAverageBatteryDrainOrDefaultMa(rxKey, Double.NaN);
        if (Double.isNaN(rxDrainRateMa)) {
            Log.w(TAG, "Unavailable Power Profile constant for key 0x"
                    + Long.toHexString(rxKey));
            rxDrainRateMa = 0;
        }
        estimators.mRxPowerEstimator = new UsageBasedPowerEstimator(rxDrainRateMa);
        for (int txLevel = 0; txLevel < ModemActivityInfo.getNumTxPowerLevels(); txLevel++) {
            long txKey = ModemPowerProfile.getAverageBatteryDrainKey(
                    ModemPowerProfile.MODEM_DRAIN_TYPE_TX, rat, freqRange, txLevel);
            double txDrainRateMa = powerProfile.getAverageBatteryDrainOrDefaultMa(txKey,
                    Double.NaN);
            if (Double.isNaN(txDrainRateMa)) {
                Log.w(TAG, "Unavailable Power Profile constant for key 0x"
                        + Long.toHexString(txKey));
                txDrainRateMa = 0;
            }
            estimators.mTxPowerEstimators[txLevel] = new UsageBasedPowerEstimator(txDrainRateMa);
        }
        return estimators;
    }

    private static class Intermediates {
        /**
         * Number of received packets
         */
        public long rxPackets;
        /**
         * Number of transmitted packets
         */
        public long txPackets;
        /**
         * Estimated power for the RX state of the modem.
         */
        public double rxPower;
        /**
         * Estimated power for the TX state of the modem.
         */
        public double txPower;
        /**
         * Estimated power for IDLE, SLEEP and CELL-SCAN states of the modem.
         */
        public double inactivePower;
        /**
         * Estimated power for IDLE, SLEEP and CELL-SCAN states of the modem.
         */
        public double callPower;
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

        if (mStatsLayout.getEnergyConsumerCount() != 0) {
            double ratio = computeEstimateAdjustmentRatioUsingConsumedEnergy();
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
                    computeUidRxTxTotals(stats, uid, mPlan.uidStateEstimates.get(i));
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
        mStatsLayout = new MobileRadioPowerStatsLayout(descriptor);
        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpStateStatsArray = new long[descriptor.stateStatsArrayLength];
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

        if (mSleepPowerEstimator != null) {
            intermediates.inactivePower += mSleepPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceSleepTime(mTmpDeviceStatsArray));
        }

        if (mIdlePowerEstimator != null) {
            intermediates.inactivePower += mIdlePowerEstimator.calculatePower(
                    mStatsLayout.getDeviceIdleTime(mTmpDeviceStatsArray));
        }

        if (mScanPowerEstimator != null) {
            intermediates.inactivePower += mScanPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceScanTime(mTmpDeviceStatsArray));
        }

        stats.forEachStateStatsKey(key -> {
            RxTxPowerEstimators estimators = mRxTxPowerEstimators.get(key);
            stats.getStateStats(mTmpStateStatsArray, key, deviceStates);
            long rxTime = mStatsLayout.getStateRxTime(mTmpStateStatsArray);
            intermediates.rxPower += estimators.mRxPowerEstimator.calculatePower(rxTime);
            for (int txLevel = 0; txLevel < ModemActivityInfo.getNumTxPowerLevels(); txLevel++) {
                long txTime = mStatsLayout.getStateTxTime(mTmpStateStatsArray, txLevel);
                intermediates.txPower +=
                        estimators.mTxPowerEstimators[txLevel].calculatePower(txTime);
            }
        });

        if (mCallPowerEstimator != null) {
            intermediates.callPower = mCallPowerEstimator.calculatePower(
                    mStatsLayout.getDeviceCallTime(mTmpDeviceStatsArray));
        }

        mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                intermediates.rxPower + intermediates.txPower + intermediates.inactivePower);
        mStatsLayout.setDeviceCallPowerEstimate(mTmpDeviceStatsArray, intermediates.callPower);
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
            totalPower += intermediates.rxPower + intermediates.txPower
                    + intermediates.inactivePower + intermediates.callPower;
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
        intermediates.rxPower *= ratio;
        intermediates.txPower *= ratio;
        intermediates.inactivePower *= ratio;
        intermediates.callPower *= ratio;

        if (!stats.getDeviceStats(mTmpDeviceStatsArray, deviceStates)) {
            return;
        }

        mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                intermediates.rxPower + intermediates.txPower + intermediates.inactivePower);
        mStatsLayout.setDeviceCallPowerEstimate(mTmpDeviceStatsArray, intermediates.callPower);
        stats.setDeviceStats(deviceStates, mTmpDeviceStatsArray);
    }

    /**
     * This step is effectively a no-op in the cases where we track the same states for
     * the entire device and all UIDs (e.g. screen on/off, on-battery/on-charger etc). However,
     * if the lists of tracked states are not the same, we need to combine some estimates
     * before distributing them proportionally to UIDs.
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
                cdseIntermediates.rxPower += intermediates.rxPower;
                cdseIntermediates.txPower += intermediates.txPower;
                cdseIntermediates.inactivePower += intermediates.inactivePower;
                cdseIntermediates.consumedEnergy += intermediates.consumedEnergy;
            }
        }
    }

    private void computeUidRxTxTotals(PowerComponentAggregatedPowerStats stats, int uid,
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
            if (intermediates.rxPackets != 0) {
                power += intermediates.rxPower * mStatsLayout.getUidRxPackets(mTmpUidStatsArray)
                        / intermediates.rxPackets;
            }
            if (intermediates.txPackets != 0) {
                power += intermediates.txPower * mStatsLayout.getUidTxPackets(mTmpUidStatsArray)
                        / intermediates.txPackets;
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
}
