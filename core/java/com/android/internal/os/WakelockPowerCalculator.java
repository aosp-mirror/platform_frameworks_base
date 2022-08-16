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
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

public class WakelockPowerCalculator extends PowerCalculator {
    private static final String TAG = "WakelockPowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;
    private final UsageBasedPowerEstimator mPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    public WakelockPowerCalculator(PowerProfile profile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_CPU_IDLE));
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_WAKELOCK;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final PowerAndDuration result = new PowerAndDuration();
        UidBatteryConsumer.Builder osBatteryConsumer = null;
        double osPowerMah = 0;
        long osDurationMs = 0;
        long totalAppDurationMs = 0;
        double appPowerMah = 0;
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(result, app.getBatteryStatsUid(), rawRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK, result.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK, result.powerMah);
            if (!app.isVirtualUid()) {
                totalAppDurationMs += result.durationMs;
                appPowerMah += result.powerMah;
            }

            if (app.getUid() == Process.ROOT_UID) {
                osBatteryConsumer = app;
                osDurationMs = result.durationMs;
                osPowerMah = result.powerMah;
            }
        }

        // The device has probably been awake for longer than the screen on
        // time and application wake lock time would account for.  Assign
        // this remainder to the OS, if possible.
        calculateRemaining(result, batteryStats, rawRealtimeUs, rawUptimeUs,
                BatteryStats.STATS_SINCE_CHARGED, osPowerMah, osDurationMs, totalAppDurationMs);
        final double remainingPowerMah = result.powerMah;
        if (osBatteryConsumer != null) {
            osBatteryConsumer.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                    result.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK, remainingPowerMah);
        }

        long wakeTimeMs = calculateWakeTimeMillis(batteryStats, rawRealtimeUs, rawUptimeUs);
        if (wakeTimeMs < 0) {
            wakeTimeMs = 0;
        }
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                        wakeTimeMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                        appPowerMah + remainingPowerMah);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                        totalAppDurationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                        appPowerMah);
    }

    private void calculateApp(PowerAndDuration result, BatteryStats.Uid u, long rawRealtimeUs,
            int statsType) {
        long wakeLockTimeUs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats =
                u.getWakelockStats();
        final int wakelockStatsCount = wakelockStats.size();
        for (int i = 0; i < wakelockStatsCount; i++) {
            final BatteryStats.Uid.Wakelock wakelock = wakelockStats.valueAt(i);

            // Only care about partial wake locks since full wake locks
            // are canceled when the user turns the screen off.
            BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            if (timer != null) {
                wakeLockTimeUs += timer.getTotalTimeLocked(rawRealtimeUs, statsType);
            }
        }
        result.durationMs = wakeLockTimeUs / 1000; // convert to millis

        // Add cost of holding a wake lock.
        result.powerMah = mPowerEstimator.calculatePower(result.durationMs);
        if (DEBUG && result.powerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": wake " + result.durationMs
                    + " power=" + BatteryStats.formatCharge(result.powerMah));
        }
    }

    private void calculateRemaining(PowerAndDuration result, BatteryStats stats, long rawRealtimeUs,
            long rawUptimeUs, int statsType, double osPowerMah, long osDurationMs,
            long totalAppDurationMs) {
        final long wakeTimeMillis = calculateWakeTimeMillis(stats, rawRealtimeUs, rawUptimeUs)
                - totalAppDurationMs;
        if (wakeTimeMillis > 0) {
            final double power = mPowerEstimator.calculatePower(wakeTimeMillis);
            if (DEBUG) {
                Log.d(TAG, "OS wakeLockTime " + wakeTimeMillis
                        + " power " + BatteryStats.formatCharge(power));
            }
            result.durationMs = osDurationMs + wakeTimeMillis;
            result.powerMah = osPowerMah + power;
        } else {
            result.durationMs = 0;
            result.powerMah = 0;
        }
    }

    /**
     * Return on-battery/screen-off time.  May be negative if the screen-on time exceeds
     * the on-battery time.
     */
    private long calculateWakeTimeMillis(BatteryStats batteryStats, long rawRealtimeUs,
            long rawUptimeUs) {
        final long batteryUptimeUs = batteryStats.getBatteryUptime(rawUptimeUs);
        final long screenOnTimeUs =
                batteryStats.getScreenOnTime(rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        return (batteryUptimeUs - screenOnTimeUs) / 1000;
    }
}
