/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.util.Log;
import android.util.SparseArray;

/**
 * Estimates the amount of power consumed by the System Server handling requests from
 * a given app.
 */
public class SystemServicePowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = false;
    private static final String TAG = "SystemServicePowerCalc";

    // Power estimators per CPU cluster, per CPU frequency. The array is flattened according
    // to this layout:
    // {cluster1-speed1, cluster1-speed2, ..., cluster2-speed1, cluster2-speed2, ...}
    private final UsageBasedPowerEstimator[] mPowerEstimators;
    private final CpuPowerCalculator mCpuPowerCalculator;

    public SystemServicePowerCalculator(PowerProfile powerProfile) {
        mCpuPowerCalculator = new CpuPowerCalculator(powerProfile);
        int numFreqs = 0;
        final int numCpuClusters = powerProfile.getNumCpuClusters();
        for (int cluster = 0; cluster < numCpuClusters; cluster++) {
            numFreqs += powerProfile.getNumSpeedStepsInCpuCluster(cluster);
        }

        mPowerEstimators = new UsageBasedPowerEstimator[numFreqs];
        int index = 0;
        for (int cluster = 0; cluster < numCpuClusters; cluster++) {
            final int numSpeeds = powerProfile.getNumSpeedStepsInCpuCluster(cluster);
            for (int speed = 0; speed < numSpeeds; speed++) {
                mPowerEstimators[index++] = new UsageBasedPowerEstimator(
                        powerProfile.getAveragePowerForCpuCore(cluster, speed));
            }
        }
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final BatteryStats.Uid systemUid = batteryStats.getUidStats().get(Process.SYSTEM_UID);
        if (systemUid == null) {
            return;
        }

        final long consumptionUC = systemUid.getCpuMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);

        double systemServicePowerMah;
        if (powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY) {
            systemServicePowerMah = calculatePowerUsingMeasuredConsumption(batteryStats,
                    systemUid, consumptionUC);
        } else {
            systemServicePowerMah = calculatePowerUsingPowerProfile(batteryStats);
        }

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        final UidBatteryConsumer.Builder systemServerConsumer = uidBatteryConsumerBuilders.get(
                Process.SYSTEM_UID);

        if (systemServerConsumer != null) {
            systemServicePowerMah = Math.min(systemServicePowerMah,
                    systemServerConsumer.getTotalPower());

            // The system server power needs to be adjusted because part of it got
            // distributed to applications
            systemServerConsumer.setConsumedPower(
                    BatteryConsumer.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS,
                    -systemServicePowerMah, powerModel);
        }

        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (app != systemServerConsumer) {
                final BatteryStats.Uid uid = app.getBatteryStatsUid();
                app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES,
                        systemServicePowerMah * uid.getProportionalSystemServiceUsage(),
                        powerModel);
            }
        }

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES,
                        systemServicePowerMah);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES,
                        systemServicePowerMah);
    }

    private double calculatePowerUsingMeasuredConsumption(BatteryStats batteryStats,
            BatteryStats.Uid systemUid, long consumptionUC) {
        // Use the PowerProfile based model to estimate the ratio between the power consumed
        // while handling incoming binder calls and the entire System UID power consumption.
        // Apply that ratio to the _measured_ system UID power consumption to get a more
        // accurate estimate of the power consumed by incoming binder calls.
        final double systemServiceModeledPowerMah = calculatePowerUsingPowerProfile(batteryStats);
        final double systemUidModeledPowerMah = mCpuPowerCalculator.calculateUidModeledPowerMah(
                systemUid, BatteryStats.STATS_SINCE_CHARGED);

        if (systemUidModeledPowerMah > 0) {
            return uCtoMah(consumptionUC) * systemServiceModeledPowerMah / systemUidModeledPowerMah;
        } else {
            return 0;
        }
    }

    private double calculatePowerUsingPowerProfile(BatteryStats batteryStats) {
        final long[] systemServiceTimeAtCpuSpeeds = batteryStats.getSystemServiceTimeAtCpuSpeeds();
        if (systemServiceTimeAtCpuSpeeds == null) {
            return 0;
        }

        // TODO(179210707): additionally account for CPU active and per cluster battery use

        double powerMah = 0;
        final int size = Math.min(mPowerEstimators.length, systemServiceTimeAtCpuSpeeds.length);
        for (int i = 0; i < size; i++) {
            powerMah += mPowerEstimators[i].calculatePower(systemServiceTimeAtCpuSpeeds[i] / 1000);
        }

        if (DEBUG) {
            Log.d(TAG, "System service power:" + powerMah);
        }
        return powerMah;
    }
}
