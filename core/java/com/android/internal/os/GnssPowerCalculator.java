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

import android.location.GnssSignalQuality;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.SparseArray;

import java.util.List;

/**
 * Estimates the amount of power consumed by the GNSS (e.g. GPS).
 */
public class GnssPowerCalculator extends PowerCalculator {
    private final double mAveragePowerGnssOn;
    private final double[] mAveragePowerPerSignalQuality;

    public GnssPowerCalculator(PowerProfile profile) {
        mAveragePowerGnssOn = profile.getAveragePowerOrDefault(PowerProfile.POWER_GPS_ON, -1);
        mAveragePowerPerSignalQuality =
                new double[GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS];
        for (int i = 0; i < GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS; i++) {
            mAveragePowerPerSignalQuality[i] = profile.getAveragePower(
                    PowerProfile.POWER_GPS_SIGNAL_QUALITY_BASED, i);
        }
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final double averageGnssPowerMa = getAverageGnssPower(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(app, app.getBatteryStatsUid(), rawRealtimeUs, rawUptimeUs, query,
                    averageGnssPowerMa);
        }
    }

    private void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query,
            double averageGnssPowerMa) {
        final long durationMs = computeDuration(u, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);

        final long measuredChargeUC = u.getGnssMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable = !query.shouldForceUsePowerProfileModel()
                && measuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE;

        final double powerMah;
        if (isMeasuredPowerAvailable) {
            powerMah = uCtoMah(measuredChargeUC);
        } else {
            powerMah = computePower(durationMs, averageGnssPowerMa);
        }
        app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_GNSS, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_GNSS, powerMah);
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        double averageGnssPowerMa = getAverageGnssPower(batteryStats, rawRealtimeUs, statsType);
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                calculateApp(app, app.uidObj, rawRealtimeUs, statsType, averageGnssPowerMa, false);
            }
        }
    }

    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            int statsType, double averageGnssPowerMa, boolean shouldForceUsePowerProfileModel) {
        final long durationMs = computeDuration(u, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);

        final long measuredChargeUC = u.getGnssMeasuredBatteryConsumptionUC();
        final boolean isMeasuredPowerAvailable = shouldForceUsePowerProfileModel
                && measuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE;

        final double powerMah;
        if (isMeasuredPowerAvailable) {
            powerMah = uCtoMah(measuredChargeUC);
        } else {
            powerMah = computePower(durationMs, averageGnssPowerMa);
        }

        app.gpsTimeMs = durationMs;
        app.gpsPowerMah = powerMah;
    }

    private long computeDuration(BatteryStats.Uid u, long rawRealtimeUs, int statsType) {
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final BatteryStats.Uid.Sensor sensor = sensorStats.get(BatteryStats.Uid.Sensor.GPS);
        if (sensor == null) {
            return 0;
        }

        final BatteryStats.Timer timer = sensor.getSensorTime();
        return timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
    }

    private double computePower(long sensorTime, double averageGnssPowerMa) {
        return (sensorTime * averageGnssPowerMa) / (1000 * 60 * 60);
    }

    private double getAverageGnssPower(BatteryStats stats, long rawRealtimeUs, int statsType) {
        double averagePower = mAveragePowerGnssOn;
        if (averagePower != -1) {
            return averagePower;
        }
        averagePower = 0;
        long totalTime = 0;
        double totalPower = 0;
        for (int i = 0; i < GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS; i++) {
            long timePerLevel = stats.getGpsSignalQualityTime(i, rawRealtimeUs, statsType);
            totalTime += timePerLevel;
            totalPower += mAveragePowerPerSignalQuality[i] * timePerLevel;
        }
        if (totalTime != 0) {
            averagePower = totalPower / totalTime;
        }
        return averagePower;
    }
}
