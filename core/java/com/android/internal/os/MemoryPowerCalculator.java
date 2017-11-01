package com.android.internal.os;

import android.os.BatteryStats;
import android.util.Log;
import android.util.LongSparseArray;

public class MemoryPowerCalculator extends PowerCalculator {

    public static final String TAG = "MemoryPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final double[] powerAverages;

    public MemoryPowerCalculator(PowerProfile profile) {
        int numBuckets = profile.getNumElements(PowerProfile.POWER_MEMORY);
        powerAverages = new double[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            powerAverages[i] = profile.getAveragePower(PowerProfile.POWER_MEMORY, i);
            if (powerAverages[i] == 0 && DEBUG) {
                Log.d(TAG, "Problem with PowerProfile. Received 0 value in MemoryPowerCalculator");
            }
        }
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {}

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        double totalMah = 0;
        long totalTimeMs = 0;
        LongSparseArray<? extends BatteryStats.Timer> timers = stats.getKernelMemoryStats();
        for (int i = 0; i < timers.size() && i < powerAverages.length; i++) {
            double mAatRail = powerAverages[(int) timers.keyAt(i)];
            long timeMs = timers.valueAt(i).getTotalTimeLocked(rawRealtimeUs, statsType);
            double mAm = (mAatRail * timeMs) / (1000*60);
            if(DEBUG) {
                Log.d(TAG, "Calculating mAh for bucket " + timers.keyAt(i) + " while unplugged");
                Log.d(TAG, "Converted power profile number from "
                        + powerAverages[(int) timers.keyAt(i)] + " into " + mAatRail);
                Log.d(TAG, "Calculated mAm " + mAm);
            }
            totalMah += mAm/60;
            totalTimeMs += timeMs;
        }
        app.usagePowerMah = totalMah;
        app.usageTimeMs = totalTimeMs;
        if (DEBUG) {
            Log.d(TAG, String.format("Calculated total mAh for memory %f while unplugged %d ",
                    totalMah, totalTimeMs));
        }
    }
}
