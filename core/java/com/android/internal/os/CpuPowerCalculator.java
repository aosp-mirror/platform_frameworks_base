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
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.Arrays;

public class CpuPowerCalculator extends PowerCalculator {
    private static final String TAG = "CpuPowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;
    private static final BatteryConsumer.Key[] UNINITIALIZED_KEYS = new BatteryConsumer.Key[0];
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
    private final UsageBasedPowerEstimator[][] mPerCpuFreqPowerEstimatorsByCluster;
    // Flattened array of estimators across clusters
    private final UsageBasedPowerEstimator[] mPerCpuFreqPowerEstimators;

    private static class Result {
        public long durationMs;
        public double powerMah;
        public long durationFgMs;
        public String packageWithHighestDrain;
        public double[] perProcStatePowerMah;
        public long[] cpuFreqTimes;
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

        int freqCount = 0;
        for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
            freqCount += profile.getNumSpeedStepsInCpuCluster(cluster);
        }

        mPerCpuFreqPowerEstimatorsByCluster = new UsageBasedPowerEstimator[mNumCpuClusters][];
        mPerCpuFreqPowerEstimators = new UsageBasedPowerEstimator[freqCount];
        int index = 0;
        for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
            final int speedsForCluster = profile.getNumSpeedStepsInCpuCluster(cluster);
            mPerCpuFreqPowerEstimatorsByCluster[cluster] =
                    new UsageBasedPowerEstimator[speedsForCluster];
            for (int speed = 0; speed < speedsForCluster; speed++) {
                final UsageBasedPowerEstimator estimator = new UsageBasedPowerEstimator(
                        profile.getAveragePowerForCpuCore(cluster, speed));
                mPerCpuFreqPowerEstimatorsByCluster[cluster][speed] = estimator;
                mPerCpuFreqPowerEstimators[index++] = estimator;
            }
        }
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_CPU;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        double totalPowerMah = 0;

        BatteryConsumer.Key[] keys = UNINITIALIZED_KEYS;
        Result result = new Result();
        if (query.isProcessStateDataNeeded()) {
            result.cpuFreqTimes = new long[batteryStats.getCpuFreqCount()];
        }
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (keys == UNINITIALIZED_KEYS) {
                if (query.isProcessStateDataNeeded()) {
                    keys = app.getKeys(BatteryConsumer.POWER_COMPONENT_CPU);
                } else {
                    keys = null;
                }
            }
            calculateApp(app, app.getBatteryStatsUid(), query, result, keys);
            if (!app.isVirtualUid()) {
                totalPowerMah += result.powerMah;
            }
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
            BatteryUsageStatsQuery query, Result result, BatteryConsumer.Key[] keys) {
        final long consumptionUC = u.getCpuMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);
        calculatePowerAndDuration(u, powerModel, consumptionUC, BatteryStats.STATS_SINCE_CHARGED,
                result);

        app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, result.powerMah, powerModel)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU, result.durationMs)
                .setPackageWithHighestDrain(result.packageWithHighestDrain);

        if (query.isProcessStateDataNeeded() && keys != null) {
            switch (powerModel) {
                case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                    calculateMeasuredPowerPerProcessState(app, u, keys);
                    break;
                case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
                    calculateModeledPowerPerProcessState(app, u, keys, result);
                    break;
            }
        }
    }

    private void calculateMeasuredPowerPerProcessState(UidBatteryConsumer.Builder app,
            BatteryStats.Uid u, BatteryConsumer.Key[] keys) {
        for (BatteryConsumer.Key key : keys) {
            // The key for PROCESS_STATE_UNSPECIFIED aka PROCESS_STATE_ANY has already been
            // populated with the full energy across all states.  We don't want to override it with
            // the energy for "other" states, which excludes the tracked states like
            // foreground, background etc.
            if (key.processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                continue;
            }

            final long consumptionUC = u.getCpuMeasuredBatteryConsumptionUC(key.processState);
            if (consumptionUC != 0) {
                app.setConsumedPower(key, uCtoMah(consumptionUC),
                        BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
            }
        }
    }

    private void calculateModeledPowerPerProcessState(UidBatteryConsumer.Builder app,
            BatteryStats.Uid u, BatteryConsumer.Key[] keys, Result result) {
        if (result.perProcStatePowerMah == null) {
            result.perProcStatePowerMah = new double[BatteryConsumer.PROCESS_STATE_COUNT];
        } else {
            Arrays.fill(result.perProcStatePowerMah, 0);
        }

        for (int uidProcState = 0; uidProcState < BatteryStats.Uid.NUM_PROCESS_STATE;
                uidProcState++) {
            @BatteryConsumer.ProcessState int procState =
                    BatteryStats.mapUidProcessStateToBatteryConsumerProcessState(uidProcState);
            if (procState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                continue;
            }

            // TODO(b/191921016): use per-state CPU cluster times
            final long[] cpuClusterTimes = null;

            boolean hasCpuFreqTimes = u.getCpuFreqTimes(result.cpuFreqTimes, uidProcState);
            if (cpuClusterTimes != null || hasCpuFreqTimes) {
                result.perProcStatePowerMah[procState] += calculateUidModeledPowerMah(u,
                        0, cpuClusterTimes, result.cpuFreqTimes);
            }
        }

        for (BatteryConsumer.Key key : keys) {
            if (key.processState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                continue;
            }

            final long cpuActiveTime = u.getCpuActiveTime(key.processState);

            double powerMah = result.perProcStatePowerMah[key.processState];
            powerMah += mCpuActivePowerEstimator.calculatePower(cpuActiveTime);
            app.setConsumedPower(key, powerMah, BatteryConsumer.POWER_MODEL_POWER_PROFILE)
                    .setUsageDurationMillis(key, cpuActiveTime);
        }
    }

    private void calculatePowerAndDuration(BatteryStats.Uid u,
            @BatteryConsumer.PowerModel int powerModel, long consumptionUC, int statsType,
            Result result) {
        long durationMs = (u.getUserCpuTimeUs(statsType) + u.getSystemCpuTimeUs(statsType)) / 1000;

        final double powerMah;
        switch (powerModel) {
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
                    + BatteryStats.formatCharge(powerMah));
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
        return calculateUidModeledPowerMah(u, u.getCpuActiveTime(), u.getCpuClusterTimes(),
                u.getCpuFreqTimes(statsType));
    }

    private double calculateUidModeledPowerMah(BatteryStats.Uid u, long cpuActiveTime,
            long[] cpuClusterTimes, long[] cpuFreqTimes) {
        // Constant battery drain when CPU is active
        double powerMah = calculateActiveCpuPowerMah(cpuActiveTime);

        // Additional per-cluster battery drain
        if (cpuClusterTimes != null) {
            if (cpuClusterTimes.length == mNumCpuClusters) {
                for (int cluster = 0; cluster < mNumCpuClusters; cluster++) {
                    final double power = mPerClusterPowerEstimators[cluster]
                            .calculatePower(cpuClusterTimes[cluster]);
                    powerMah += power;
                    if (DEBUG) {
                        Log.d(TAG, "UID " + u.getUid() + ": CPU cluster #" + cluster
                                + " clusterTimeMs=" + cpuClusterTimes[cluster]
                                + " power=" + BatteryStats.formatCharge(power));
                    }
                }
            } else {
                Log.w(TAG, "UID " + u.getUid() + " CPU cluster # mismatch: Power Profile # "
                        + mNumCpuClusters + " actual # " + cpuClusterTimes.length);
            }
        }

        if (cpuFreqTimes != null) {
            if (cpuFreqTimes.length == mPerCpuFreqPowerEstimators.length) {
                for (int i = 0; i < cpuFreqTimes.length; i++) {
                    powerMah += mPerCpuFreqPowerEstimators[i].calculatePower(cpuFreqTimes[i]);
                }
            } else {
                Log.w(TAG, "UID " + u.getUid() + " CPU freq # mismatch: Power Profile # "
                        + mPerCpuFreqPowerEstimators.length + " actual # " + cpuFreqTimes.length);
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
     * @param cluster           CPU cluster used.
     * @param clusterDurationMs duration of CPU cluster usage.
     * @return a double in milliamp-hours of estimated CPU cluster power consumption.
     */
    public double calculatePerCpuClusterPowerMah(int cluster, long clusterDurationMs) {
        return mPerClusterPowerEstimators[cluster].calculatePower(clusterDurationMs);
    }

    /**
     * Calculates CPU cluster power consumption at a specific speedstep.
     *
     * @param cluster                 CPU cluster used.
     * @param speedStep               which speedstep used.
     * @param clusterSpeedDurationsMs duration of CPU cluster usage at the specified speed step.
     * @return a double in milliamp-hours of estimated CPU cluster-speed power consumption.
     */
    public double calculatePerCpuFreqPowerMah(int cluster, int speedStep,
            long clusterSpeedDurationsMs) {
        return mPerCpuFreqPowerEstimatorsByCluster[cluster][speedStep].calculatePower(
                clusterSpeedDurationsMs);
    }
}
