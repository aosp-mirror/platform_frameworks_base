/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

public class CpuPowerCalculator extends PowerCalculator {
    private static final String TAG = "CpuPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final int mNumCpuClusters;

    // Time-in-state based CPU power estimation model computes the estimated power
    // by adding up three components:
    //   - CPU Active power:    the constant amount of charge consumed by the CPU when it is on
    //   - Per Cluster power:   the additional amount of charge consumed by a CPU cluster
    //                          when it is running
    //   - Per frequency power: the additional amount of charge caused by dynamic frequency scaling

    private final UsageBasedPowerEstimator mCpuActivePowerEstimator;
    // One estimator per cluster
    private final UsageBasedPowerEstimator[] mPerClusterPowerEstimators;
    // Multiple estimators per cluster: one per available scaling frequency. Note that different
    // clusters have different sets of frequencies and corresponding power consumption averages.
    private final UsageBasedPowerEstimator[][] mPerCpuFreqPowerEstimators;

    private static class Result {
        public long durationMs;
        public double powerMah;
        public long durationFgMs;
        public String packageWithHighestDrain;
    }

    public CpuPowerCalculator(PowerProfile profile) {
        mNumCpuClusters = profile.getNumCpuClusters();

        mCpuActivePowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE));

        mPerClusterPowerEstimators = new UsageBasedPowerEstimator[mNumCpuClusters];
        for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
            mPerClusterPowerEstimators[cluster] = new UsageBasedPowerEstimator(
                    profile.getAveragePowerForCpuCluster(cluster));
        }

        mPerCpuFreqPowerEstimators = new UsageBasedPowerEstimator[mNumCpuClusters][];
        for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
            final int speedsForCluster = profile.getNumSpeedStepsInCpuCluster(cluster);
            mPerCpuFreqPowerEstimators[cluster] = new UsageBasedPowerEstimator[speedsForCluster];
            for (int speed = 0; speed < speedsForCluster; speed++) {
                mPerCpuFreqPowerEstimators[cluster][speed] =
                        new UsageBasedPowerEstimator(
                                profile.getAveragePowerForCpuCore(cluster, speed));
            }
        }
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        double totalPowerMah = 0;

        Result result = new Result();
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(app, app.getBatteryStatsUid(), query, result);
            totalPowerMah += result.powerMah;
        }

        final long consumptionUC = batteryStats.getCpuMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, totalPowerMah);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU,
                        powerModel == BatteryConsumer.POWER_MODEL_MEASURED_ENERGY
                                ? uCtoMah(consumptionUC) : totalPowerMah, powerModel);
    }

    private void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            BatteryUsageStatsQuery query, Result result) {
        final long consumptionUC = u.getCpuMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);
        calculatePowerAndDuration(u, powerModel, consumptionUC, BatteryStats.STATS_SINCE_CHARGED,
                result);

        app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, result.powerMah, powerModel)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU, result.durationMs)
                .setPackageWithHighestDrain(result.packageWithHighestDrain);
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        Result result = new Result();
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                calculateApp(app, app.uidObj, statsType, result);
            }
        }
    }

    private void calculateApp(BatterySipper app, BatteryStats.Uid u, int statsType, Result result) {
        final long consumptionUC = u.getCpuMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC);
        calculatePowerAndDuration(u, powerModel, consumptionUC, statsType, result);

        app.cpuPowerMah = result.powerMah;
        app.cpuTimeMs = result.durationMs;
        app.cpuFgTimeMs = result.durationFgMs;
        app.packageWithHighestDrain = result.packageWithHighestDrain;
    }

    private void calculatePowerAndDuration(BatteryStats.Uid u,
            @BatteryConsumer.PowerModel int powerModel, long consumptionUC, int statsType,
            Result result) {
        long durationMs = (u.getUserCpuTimeUs(statsType) + u.getSystemCpuTimeUs(statsType)) / 1000;

        final double powerMah;
        switch(powerModel) {
            case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                powerMah = uCtoMah(consumptionUC);
                break;
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
            default:
                powerMah = calculateUidModeledPowerMah(u, statsType);
                break;
        }

        if (DEBUG && (durationMs != 0 || powerMah != 0)) {
            Log.d(TAG, "UID " + u.getUid() + ": CPU time=" + durationMs + " ms power="
                    + formatCharge(powerMah));
        }

        // Keep track of the package with highest drain.
        double highestDrain = 0;
        String packageWithHighestDrain = null;
        long durationFgMs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
        final int processStatsCount = processStats.size();
        for (int i = 0; i < processStatsCount; i++) {
            final BatteryStats.Uid.Proc ps = processStats.valueAt(i);
            final String processName = processStats.keyAt(i);
            durationFgMs += ps.getForegroundTime(statsType);

            final long costValue = ps.getUserTime(statsType) + ps.getSystemTime(statsType)
                    + ps.getForegroundTime(statsType);

            // Each App can have multiple packages and with multiple running processes.
            // Keep track of the package who's process has the highest drain.
            if (packageWithHighestDrain == null || packageWithHighestDrain.startsWith("*")) {
                highestDrain = costValue;
                packageWithHighestDrain = processName;
            } else if (highestDrain < costValue && !processName.startsWith("*")) {
                highestDrain = costValue;
                packageWithHighestDrain = processName;
            }
        }

        // Ensure that the CPU times make sense.
        if (durationFgMs > durationMs) {
            if (DEBUG && durationFgMs > durationMs + 10000) {
                Log.d(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
            }

            // Statistics may not have been gathered yet.
            durationMs = durationFgMs;
        }

        result.durationMs = durationMs;
        result.durationFgMs = durationFgMs;
        result.powerMah = powerMah;
        result.packageWithHighestDrain = packageWithHighestDrain;
    }

    /**
     * Calculates CPU power consumed by the specified app, using the PowerProfile model.
     */
    public double calculateUidModeledPowerMah(BatteryStats.Uid u, int statsType) {
        // Constant battery drain when CPU is active
        double powerMah = calculateActiveCpuPowerMah(u.getCpuActiveTime());

        // Additional per-cluster battery drain
        long[] cpuClusterTimes = u.getCpuClusterTimes();
        if (cpuClusterTimes != null) {
            if (cpuClusterTimes.length == mNumCpuClusters) {
                for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
                    double power = calculatePerCpuClusterPowerMah(cluster,
                            cpuClusterTimes[cluster]);
                    powerMah += power;
                    if (DEBUG) {
                        Log.d(TAG, "UID " + u.getUid() + ": CPU cluster #" + cluster
                                + " clusterTimeMs=" + cpuClusterTimes[cluster]
                                + " power=" + formatCharge(power));
                    }
                }
            } else {
                Log.w(TAG, "UID " + u.getUid() + " CPU cluster # mismatch: Power Profile # "
                        + mNumCpuClusters + " actual # " + cpuClusterTimes.length);
            }
        }

        // Additional per-frequency battery drain
        for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
            final int speedsForCluster = mPerCpuFreqPowerEstimators[cluster].length;
            for (int speed = 0; speed < speedsForCluster; speed++) {
                final long timeUs = u.getTimeAtCpuSpeed(cluster, speed, statsType);
                final double power = calculatePerCpuFreqPowerMah(cluster, speed,
                        timeUs / 1000);
                if (DEBUG) {
                    Log.d(TAG, "UID " + u.getUid() + ": CPU cluster #" + cluster + " step #"
                            + speed + " timeUs=" + timeUs + " power="
                            + formatCharge(power));
                }
                powerMah += power;
            }
        }
        return powerMah;
    }

    /**
     * Calculates active CPU power consumption.
     *
     * @param durationsMs duration of CPU usage.
     * @return a double in milliamp-hours of estimated active CPU power consumption.
     */
    public double calculateActiveCpuPowerMah(long durationsMs) {
        return mCpuActivePowerEstimator.calculatePower(durationsMs);
    }

    /**
     * Calculates CPU cluster power consumption.
     *
     * @param cluster CPU cluster used.
     * @param clusterDurationMs duration of CPU cluster usage.
     * @return a double in milliamp-hours of estimated CPU cluster power consumption.
     */
    public double calculatePerCpuClusterPowerMah(int cluster, long clusterDurationMs) {
        return mPerClusterPowerEstimators[cluster].calculatePower(clusterDurationMs);
    }

    /**
     * Calculates CPU cluster power consumption at a specific speedstep.
     *
     * @param cluster CPU cluster used.
     * @param speedStep which speedstep used.
     * @param clusterSpeedDurationsMs duration of CPU cluster usage at the specified speed step.
     * @return a double in milliamp-hours of estimated CPU cluster-speed power consumption.
     */
    public double calculatePerCpuFreqPowerMah(int cluster, int speedStep,
            long clusterSpeedDurationsMs) {
        return mPerCpuFreqPowerEstimators[cluster][speedStep].calculatePower(
                clusterSpeedDurationsMs);
    }
}
