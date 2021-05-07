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
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

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

    public SystemServicePowerCalculator(PowerProfile powerProfile) {
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
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        double systemServicePowerMah = calculateSystemServicePower(batteryStats);
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
                    -systemServicePowerMah);
        }

        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (app != systemServerConsumer) {
                final BatteryStats.Uid uid = app.getBatteryStatsUid();
                app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES,
                        systemServicePowerMah * uid.getProportionalSystemServiceUsage());
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

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType,
            SparseArray<UserHandle> asUsers) {
        double systemServicePowerMah = calculateSystemServicePower(batteryStats);
        BatterySipper systemServerSipper = null;
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                if (app.getUid() == Process.SYSTEM_UID) {
                    systemServerSipper = app;
                    break;
                }
            }
        }

        if (systemServerSipper != null) {
            systemServicePowerMah = Math.min(systemServicePowerMah, systemServerSipper.sumPower());

            // The system server power needs to be adjusted because part of it got
            // distributed to applications
            systemServerSipper.powerReattributedToOtherSippersMah = -systemServicePowerMah;
        }

        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                if (app != systemServerSipper) {
                    final BatteryStats.Uid uid = app.uidObj;
                    app.systemServiceCpuPowerMah =
                            systemServicePowerMah * uid.getProportionalSystemServiceUsage();
                }
            }
        }
    }

    private double calculateSystemServicePower(BatteryStats batteryStats) {
        final long[] systemServiceTimeAtCpuSpeeds = batteryStats.getSystemServiceTimeAtCpuSpeeds();
        if (systemServiceTimeAtCpuSpeeds == null) {
            return 0;
        }

        // TODO(179210707): additionally account for CPU active and per cluster battery use

        double powerMah = 0;
        final int size = Math.min(mPowerEstimators.length, systemServiceTimeAtCpuSpeeds.length);
        for (int i = 0; i < size; i++) {
            powerMah += mPowerEstimators[i].calculatePower(systemServiceTimeAtCpuSpeeds[i]);
        }

        if (DEBUG) {
            Log.d(TAG, "System service power:" + powerMah);
        }

        return powerMah;
    }
}
