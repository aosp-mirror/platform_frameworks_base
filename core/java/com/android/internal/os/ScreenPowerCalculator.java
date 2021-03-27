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
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Estimates power consumed by the screen(s)
 */
public class ScreenPowerCalculator extends PowerCalculator {
    private static final String TAG = "ScreenPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;

    // Minimum amount of time the screen should be on to start smearing drain to apps
    public static final long MIN_ACTIVE_TIME_FOR_SMEARING = 10 * DateUtils.MINUTE_IN_MILLIS;

    private final UsageBasedPowerEstimator mScreenOnPowerEstimator;
    private final UsageBasedPowerEstimator mScreenFullPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    public ScreenPowerCalculator(PowerProfile powerProfile) {
        mScreenOnPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON));
        mScreenFullPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL));
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final PowerAndDuration totalPowerAndDuration = new PowerAndDuration();

        final boolean useEnergyData = calculateTotalDurationAndPower(totalPowerAndDuration,
                batteryStats, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED,
                query.shouldForceUsePowerProfileModel());

        double totalAppPower = 0;

        // Now deal with each app's UidBatteryConsumer. The results are stored in the
        // BatteryConsumer.POWER_COMPONENT_SCREEN power component, which is considered smeared,
        // but the method depends on the data source.
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        if (useEnergyData) {
            final PowerAndDuration appPowerAndDuration = new PowerAndDuration();
            for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
                final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
                calculateAppUsingMeasuredEnergy(appPowerAndDuration, app.getBatteryStatsUid(),
                        rawRealtimeUs);
                app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN,
                                appPowerAndDuration.durationMs)
                        .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN,
                                appPowerAndDuration.powerMah);
                totalAppPower += appPowerAndDuration.powerMah;
            }
        } else {
            smearScreenBatteryDrain(uidBatteryConsumerBuilders, totalPowerAndDuration,
                    rawRealtimeUs);
            totalAppPower = totalPowerAndDuration.powerMah;
        }

        builder.getOrCreateSystemBatteryConsumerBuilder(SystemBatteryConsumer.DRAIN_TYPE_SCREEN)
                .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE,
                        totalPowerAndDuration.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE,
                        Math.max(totalPowerAndDuration.powerMah, totalAppPower))
                .setPowerConsumedByApps(totalAppPower);
    }

    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final PowerAndDuration totalPowerAndDuration = new PowerAndDuration();
        final boolean useEnergyData = calculateTotalDurationAndPower(totalPowerAndDuration,
                batteryStats, rawRealtimeUs, statsType, false);
        if (totalPowerAndDuration.powerMah == 0) {
            return;
        }

        // First deal with the SCREEN BatterySipper (since we need this for smearing over apps).
        final BatterySipper bs = new BatterySipper(BatterySipper.DrainType.SCREEN, null, 0);
        bs.usagePowerMah = totalPowerAndDuration.powerMah;
        bs.usageTimeMs = totalPowerAndDuration.durationMs;
        bs.sumPower();
        sippers.add(bs);

        // Now deal with each app's BatterySipper. The results are stored in the screenPowerMah
        // field, which is considered smeared, but the method depends on the data source.
        if (useEnergyData) {
            final PowerAndDuration appPowerAndDuration = new PowerAndDuration();
            for (int i = sippers.size() - 1; i >= 0; i--) {
                final BatterySipper app = sippers.get(i);
                if (app.drainType == BatterySipper.DrainType.APP) {
                    calculateAppUsingMeasuredEnergy(appPowerAndDuration, app.uidObj, rawRealtimeUs);
                    app.screenPowerMah = appPowerAndDuration.powerMah;
                }
            }
        } else {
            smearScreenBatterySipper(sippers, bs, rawRealtimeUs);
        }
    }

    /**
     * Stores duration and power information in totalPowerAndDuration and returns true if measured
     * energy data is available and should be used by the model.
     */
    private boolean calculateTotalDurationAndPower(PowerAndDuration totalPowerAndDuration,
            BatteryStats batteryStats, long rawRealtimeUs, int statsType,
            boolean forceUsePowerProfileModel) {
        totalPowerAndDuration.durationMs = calculateDuration(batteryStats, rawRealtimeUs,
                statsType);

        if (!forceUsePowerProfileModel) {
            final long chargeUC = batteryStats.getScreenOnMeasuredBatteryConsumptionUC();
            if (chargeUC != BatteryStats.POWER_DATA_UNAVAILABLE) {
                totalPowerAndDuration.powerMah = uCtoMah(chargeUC);
                return true;
            }
        }

        totalPowerAndDuration.powerMah = calculateTotalPowerFromBrightness(batteryStats,
                rawRealtimeUs, statsType, totalPowerAndDuration.durationMs);
        return false;
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

    private double calculateTotalPowerFromBrightness(BatteryStats batteryStats, long rawRealtimeUs,
            int statsType, long durationMs) {
        double power = mScreenOnPowerEstimator.calculatePower(durationMs);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final long brightnessTime =
                    batteryStats.getScreenBrightnessTime(i, rawRealtimeUs, statsType) / 1000;
            final double binPowerMah = mScreenFullPowerEstimator.calculatePower(brightnessTime)
                    * (i + 0.5f) / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            if (DEBUG && binPowerMah != 0) {
                Slog.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + formatCharge(binPowerMah));
            }
            power += binPowerMah;
        }
        return power;
    }

    /**
     * Smear the screen on power usage among {@code sippers}, based on ratio of foreground activity
     * time, and store this in the {@link BatterySipper#screenPowerMah} field.
     */
    @VisibleForTesting
    public void smearScreenBatterySipper(List<BatterySipper> sippers, BatterySipper screenSipper,
            long rawRealtimeUs) {
        long totalActivityTimeMs = 0;
        final SparseLongArray activityTimeArray = new SparseLongArray();
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatteryStats.Uid uid = sippers.get(i).uidObj;
            if (uid != null) {
                final long timeMs = getProcessForegroundTimeMs(uid, rawRealtimeUs);
                activityTimeArray.put(uid.getUid(), timeMs);
                totalActivityTimeMs += timeMs;
            }
        }

        if (screenSipper != null && totalActivityTimeMs >= MIN_ACTIVE_TIME_FOR_SMEARING) {
            final double totalScreenPowerMah = screenSipper.totalPowerMah;
            for (int i = sippers.size() - 1; i >= 0; i--) {
                final BatterySipper sipper = sippers.get(i);
                sipper.screenPowerMah = totalScreenPowerMah
                        * activityTimeArray.get(sipper.getUid(), 0)
                        / totalActivityTimeMs;
            }
        }
    }

    /**
     * Smear the screen on power usage among {@code sippers}, based on ratio of foreground activity
     * time, and store this in the {@link BatterySipper#screenPowerMah} field.
     */
    private void smearScreenBatteryDrain(
            SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders,
            PowerAndDuration totalPowerAndDuration, long rawRealtimeUs) {
        long totalActivityTimeMs = 0;
        final SparseLongArray activityTimeArray = new SparseLongArray();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final BatteryStats.Uid uid = uidBatteryConsumerBuilders.valueAt(i).getBatteryStatsUid();
            final long timeMs = getProcessForegroundTimeMs(uid, rawRealtimeUs);
            activityTimeArray.put(uid.getUid(), timeMs);
            totalActivityTimeMs += timeMs;
        }

        if (totalActivityTimeMs >= MIN_ACTIVE_TIME_FOR_SMEARING) {
            final double totalScreenPowerMah = totalPowerAndDuration.powerMah;
            for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
                final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
                final long durationMs = activityTimeArray.get(app.getUid(), 0);
                final double powerMah = totalScreenPowerMah * durationMs / totalActivityTimeMs;
                app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_SCREEN, durationMs)
                        .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SCREEN, powerMah);
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
