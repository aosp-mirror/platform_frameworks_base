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

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_FULL;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.text.format.DateUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Estimates power consumed by the screen(s)
 */
public class ScreenPowerCalculator extends PowerCalculator {
    private static final String TAG = "ScreenPowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;

    // Minimum amount of time the screen should be on to start smearing drain to apps
    public static final long MIN_ACTIVE_TIME_FOR_SMEARING = 10 * DateUtils.MINUTE_IN_MILLIS;

    private final UsageBasedPowerEstimator[] mScreenOnPowerEstimators;
    private final UsageBasedPowerEstimator[] mScreenFullPowerEstimators;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    public ScreenPowerCalculator(PowerProfile powerProfile) {
        final int numDisplays = powerProfile.getNumDisplays();
        mScreenOnPowerEstimators = new UsageBasedPowerEstimator[numDisplays];
        mScreenFullPowerEstimators = new UsageBasedPowerEstimator[numDisplays];
        for (int display = 0; display < numDisplays; display++) {
            mScreenOnPowerEstimators[display] = new UsageBasedPowerEstimator(
                    powerProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, display));
            mScreenFullPowerEstimators[display] = new UsageBasedPowerEstimator(
                    powerProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL,
                            display));
        }
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_SCREEN;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final PowerAndDuration totalPowerAndDuration = new PowerAndDuration();

        final long consumptionUC = batteryStats.getScreenOnMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(consumptionUC, query);
        calculateTotalDurationAndPower(totalPowerAndDuration, powerModel, batteryStats,
                rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED, consumptionUC);

        double totalAppPower = 0;
        long totalAppDuration = 0;

        // Now deal with each app's UidBatteryConsumer. The results are stored in the
        // BatteryConsumer.POWER_COMPONENT_SCREEN power component, which is considered smeared,
        // but the method depends on the data source.
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        switch (powerModel) {
            case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                final PowerAndDuration appPowerAndDuration = new PowerAndDuration();
                for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
                    final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
                    calculateAppUsingMeasuredEnergy(appPowerAndDuration, app.getBatteryStatsUid(),
                            rawRealtimeUs);
                    app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN,
                                    appPowerAndDuration.durationMs)
                            .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN,
                                    appPowerAndDuration.powerMah, powerModel);
                    if (!app.isVirtualUid()) {
                        totalAppPower += appPowerAndDuration.powerMah;
                        totalAppDuration += appPowerAndDuration.durationMs;
                    }
                }
                break;
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
            default:
                smearScreenBatteryDrain(uidBatteryConsumerBuilders, totalPowerAndDuration,
                        rawRealtimeUs);
                totalAppPower = totalPowerAndDuration.powerMah;
                totalAppDuration = totalPowerAndDuration.durationMs;
        }

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN,
                        Math.max(totalPowerAndDuration.powerMah, totalAppPower), powerModel)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN,
                        totalPowerAndDuration.durationMs);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, totalAppPower, powerModel)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN, totalAppDuration);
    }

    /**
     * Stores duration and power information in totalPowerAndDuration.
     */
    private void calculateTotalDurationAndPower(PowerAndDuration totalPowerAndDuration,
            @BatteryConsumer.PowerModel int powerModel, BatteryStats batteryStats,
            long rawRealtimeUs, int statsType, long consumptionUC) {
        totalPowerAndDuration.durationMs = calculateDuration(batteryStats, rawRealtimeUs,
                statsType);

        switch (powerModel) {
            case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                totalPowerAndDuration.powerMah = uCtoMah(consumptionUC);
                break;
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
            default:
                totalPowerAndDuration.powerMah = calculateTotalPowerFromBrightness(batteryStats,
                        rawRealtimeUs);
        }
    }

    private void calculateAppUsingMeasuredEnergy(PowerAndDuration appPowerAndDuration,
            BatteryStats.Uid u, long rawRealtimeUs) {
        appPowerAndDuration.durationMs = getProcessForegroundTimeMs(u, rawRealtimeUs);

        final long chargeUC = u.getScreenOnMeasuredBatteryConsumptionUC();
        if (chargeUC < 0) {
            Slog.wtf(TAG, "Screen energy not supported, so calculateApp shouldn't de called");
            appPowerAndDuration.powerMah = 0;
            return;
        }

        appPowerAndDuration.powerMah = uCtoMah(chargeUC);
    }

    private long calculateDuration(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        return batteryStats.getScreenOnTime(rawRealtimeUs, statsType) / 1000;
    }

    private double calculateTotalPowerFromBrightness(BatteryStats batteryStats,
            long rawRealtimeUs) {
        final int numDisplays = mScreenOnPowerEstimators.length;
        double power = 0;
        for (int display = 0; display < numDisplays; display++) {
            final long displayTime = batteryStats.getDisplayScreenOnTime(display, rawRealtimeUs)
                    / 1000;
            power += mScreenOnPowerEstimators[display].calculatePower(displayTime);
            for (int bin = 0; bin < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; bin++) {
                final long brightnessTime = batteryStats.getDisplayScreenBrightnessTime(display,
                        bin, rawRealtimeUs) / 1000;
                final double binPowerMah = mScreenFullPowerEstimators[display].calculatePower(
                        brightnessTime) * (bin + 0.5f) / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
                if (DEBUG && binPowerMah != 0) {
                    Slog.d(TAG, "Screen bin #" + bin + ": time=" + brightnessTime
                            + " power=" + BatteryStats.formatCharge(binPowerMah));
                }
                power += binPowerMah;
            }
        }
        return power;
    }

    /**
     * Smear the screen on power usage among {@code UidBatteryConsumers}, based on ratio of
     * foreground activity time.
     */
    private void smearScreenBatteryDrain(
            SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders,
            PowerAndDuration totalPowerAndDuration, long rawRealtimeUs) {
        long totalActivityTimeMs = 0;
        final SparseLongArray activityTimeArray = new SparseLongArray();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            final BatteryStats.Uid uid = app.getBatteryStatsUid();
            final long timeMs = getProcessForegroundTimeMs(uid, rawRealtimeUs);
            activityTimeArray.put(uid.getUid(), timeMs);
            if (!app.isVirtualUid()) {
                totalActivityTimeMs += timeMs;
            }
        }

        if (totalActivityTimeMs >= MIN_ACTIVE_TIME_FOR_SMEARING) {
            final double totalScreenPowerMah = totalPowerAndDuration.powerMah;
            for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
                final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
                final long durationMs = activityTimeArray.get(app.getUid(), 0);
                final double powerMah = totalScreenPowerMah * durationMs / totalActivityTimeMs;
                app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SCREEN, durationMs)
                        .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, powerMah,
                                BatteryConsumer.POWER_MODEL_POWER_PROFILE);
            }
        }
    }

    /** Get the minimum of the uid's ForegroundActivity time and its TOP time. */
    @VisibleForTesting
    public long getProcessForegroundTimeMs(BatteryStats.Uid uid, long rawRealTimeUs) {
        final int[] foregroundTypes = {BatteryStats.Uid.PROCESS_STATE_TOP};

        long timeUs = 0;
        for (int type : foregroundTypes) {
            final long localTime = uid.getProcessStateTime(type, rawRealTimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
            timeUs += localTime;
        }

        // Return the min value of STATE_TOP time and foreground activity time, since both of these
        // time have some errors.
        return Math.min(timeUs, getForegroundActivityTotalTimeUs(uid, rawRealTimeUs)) / 1000;
    }

    /** Get the ForegroundActivity time of the given uid. */
    @VisibleForTesting
    public long getForegroundActivityTotalTimeUs(BatteryStats.Uid uid, long rawRealtimeUs) {
        final BatteryStats.Timer timer = uid.getForegroundActivityTimer();
        if (timer == null) {
            return 0;
        }
        return timer.getTotalTimeLocked(rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
    }
}
