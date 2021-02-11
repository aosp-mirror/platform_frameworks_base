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
import android.os.SystemClock;
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

    private final UsageBasedPowerEstimator mScreenOnPowerEstimator;
    private final UsageBasedPowerEstimator mScreenFullPowerEstimator;

    public ScreenPowerCalculator(PowerProfile powerProfile) {
        mScreenOnPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON));
        mScreenFullPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL));
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long durationMs = computeDuration(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = computePower(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED, durationMs);
        if (powerMah != 0) {
            builder.getOrCreateSystemBatteryConsumerBuilder(SystemBatteryConsumer.DRAIN_TYPE_SCREEN)
                    .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE, durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE, powerMah);
        }
        // TODO(b/178140704): Attribute *measured* total usage for BatteryUsageStats.
        // TODO(b/178140704): Attribute (measured/smeared) usage *per app* for BatteryUsageStats.
    }

    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {

        final long energyUJ = batteryStats.getScreenOnEnergy();
        final boolean isMeasuredDataAvailable = energyUJ != BatteryStats.ENERGY_DATA_UNAVAILABLE;

        final long durationMs = computeDuration(batteryStats, rawRealtimeUs, statsType);
        final double powerMah = getMeasuredOrComputedPower(
                energyUJ, batteryStats, rawRealtimeUs, statsType, durationMs);
        if (powerMah == 0) {
            return;
        }

        // First deal with the SCREEN BatterySipper (since we need this for smearing over apps).
        final BatterySipper bs = new BatterySipper(BatterySipper.DrainType.SCREEN, null, 0);
        bs.usagePowerMah = powerMah;
        bs.usageTimeMs = durationMs;
        bs.sumPower();
        sippers.add(bs);

        // Now deal with each app's BatterySipper. The results are stored in the screenPowerMah
        // field, which is considered smeared, but the method depends on the data source.
        if (isMeasuredDataAvailable) {
            super.calculate(sippers, batteryStats, rawRealtimeUs, rawUptimeUs, statsType, asUsers);
        } else {
            smearScreenBatterySipper(sippers, bs);
        }
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        final long energyUJ = u.getScreenOnEnergy();
        if (energyUJ < 0) {
            Slog.wtf(TAG, "Screen energy not supported, so calculateApp shouldn't de called");
            return;
        }
        if (energyUJ == 0) return;
        app.screenPowerMah = mAhToUJ(u.getScreenOnEnergy());
    }

    private long computeDuration(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        return batteryStats.getScreenOnTime(rawRealtimeUs, statsType) / 1000;
    }

    private double computePower(BatteryStats batteryStats, long rawRealtimeUs, int statsType,
            long durationMs) {
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

    private double getMeasuredOrComputedPower(long measuredEnergyUJ,
            BatteryStats batteryStats, long rawRealtimeUs, int statsType, long durationMs) {

        if (measuredEnergyUJ != BatteryStats.ENERGY_DATA_UNAVAILABLE) {
            return mAhToUJ(measuredEnergyUJ);
        } else {
            return computePower(batteryStats, rawRealtimeUs, statsType, durationMs);
        }
    }

    /**
     * Smear the screen on power usage among {@code sippers}, based on ratio of foreground activity
     * time, and store this in the {@link BatterySipper#screenPowerMah} field.
     */
    @VisibleForTesting
    public void smearScreenBatterySipper(List<BatterySipper> sippers, BatterySipper screenSipper) {

        long totalActivityTimeMs = 0;
        final SparseLongArray activityTimeArray = new SparseLongArray();
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatteryStats.Uid uid = sippers.get(i).uidObj;
            if (uid != null) {
                final long timeMs = getProcessForegroundTimeMs(uid);
                activityTimeArray.put(uid.getUid(), timeMs);
                totalActivityTimeMs += timeMs;
            }
        }

        if (screenSipper != null && totalActivityTimeMs >= 10 * DateUtils.MINUTE_IN_MILLIS) {
            final double totalScreenPowerMah = screenSipper.totalPowerMah;
            for (int i = sippers.size() - 1; i >= 0; i--) {
                final BatterySipper sipper = sippers.get(i);
                sipper.screenPowerMah = totalScreenPowerMah
                        * activityTimeArray.get(sipper.getUid(), 0)
                        / totalActivityTimeMs;
            }
        }
    }

    /** Get the minimum of the uid's ForegroundActivity time and its TOP time. */
    @VisibleForTesting
    public long getProcessForegroundTimeMs(BatteryStats.Uid uid) {
        final long rawRealTimeUs = SystemClock.elapsedRealtime() * 1000;
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
