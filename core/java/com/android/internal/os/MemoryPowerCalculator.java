package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UserHandle;
import android.util.LongSparseArray;
import android.util.SparseArray;

import java.util.List;

public class MemoryPowerCalculator extends PowerCalculator {
    public static final String TAG = "MemoryPowerCalculator";
    private final UsageBasedPowerEstimator[] mPowerEstimators;

    public MemoryPowerCalculator(PowerProfile profile) {
        int numBuckets = profile.getNumElements(PowerProfile.POWER_MEMORY);
        mPowerEstimators = new UsageBasedPowerEstimator[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            mPowerEstimators[i] = new UsageBasedPowerEstimator(
                    profile.getAveragePower(PowerProfile.POWER_MEMORY, i));
        }
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long durationMs = calculateDuration(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = calculatePower(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MEMORY, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_MEMORY, powerMah);
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final long durationMs = calculateDuration(batteryStats, rawRealtimeUs, statsType);
        final double powerMah = calculatePower(batteryStats, rawRealtimeUs, statsType);
        BatterySipper memory = new BatterySipper(BatterySipper.DrainType.MEMORY, null, 0);
        memory.usageTimeMs = durationMs;
        memory.usagePowerMah = powerMah;
        memory.sumPower();
        if (memory.totalPowerMah > 0) {
            sippers.add(memory);
        }
    }

    private long calculateDuration(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        long usageDurationMs = 0;
        LongSparseArray<? extends BatteryStats.Timer> timers = batteryStats.getKernelMemoryStats();
        for (int i = 0; i < timers.size() && i < mPowerEstimators.length; i++) {
            usageDurationMs += mPowerEstimators[i].calculateDuration(timers.valueAt(i),
                    rawRealtimeUs, statsType);
        }
        return usageDurationMs;
    }

    private double calculatePower(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        double powerMah = 0;
        LongSparseArray<? extends BatteryStats.Timer> timers = batteryStats.getKernelMemoryStats();
        for (int i = 0; i < timers.size() && i < mPowerEstimators.length; i++) {
            UsageBasedPowerEstimator estimator = mPowerEstimators[(int) timers.keyAt(i)];
            final long usageDurationMs =
                    estimator.calculateDuration(timers.valueAt(i), rawRealtimeUs, statsType);
            powerMah += estimator.calculatePower(usageDurationMs);
        }
        return powerMah;
    }
}
