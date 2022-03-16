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

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.SparseArray;

import java.util.List;

public class SensorPowerCalculator extends PowerCalculator {
    private final SparseArray<Sensor> mSensors;

    public SensorPowerCalculator(SensorManager sensorManager) {
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        mSensors = new SparseArray<>(sensors.size());
        for (int i = 0; i < sensors.size(); i++) {
            Sensor sensor = sensors.get(i);
            mSensors.put(sensor.getHandle(), sensor);
        }
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_SENSORS;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        double appsPowerMah = 0;
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            if (!app.isVirtualUid()) {
                appsPowerMah += calculateApp(app, app.getBatteryStatsUid(), rawRealtimeUs);
            }
        }

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS, appsPowerMah);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS, appsPowerMah);
    }

    private double calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs) {
        final double powerMah = calculatePowerMah(u, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SENSORS,
                        calculateDuration(u, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED))
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS, powerMah);
        return powerMah;
    }

    private long calculateDuration(BatteryStats.Uid u, long rawRealtimeUs, int statsType) {
        long durationMs = 0;
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final int NSE = sensorStats.size();
        for (int ise = 0; ise < NSE; ise++) {
            final int sensorHandle = sensorStats.keyAt(ise);
            if (sensorHandle == BatteryStats.Uid.Sensor.GPS) {
                continue;
            }

            final BatteryStats.Uid.Sensor sensor = sensorStats.valueAt(ise);
            final BatteryStats.Timer timer = sensor.getSensorTime();
            durationMs += timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
        }
        return durationMs;
    }

    private double calculatePowerMah(BatteryStats.Uid u, long rawRealtimeUs, int statsType) {
        double powerMah = 0;
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final int count = sensorStats.size();
        for (int ise = 0; ise < count; ise++) {
            final int sensorHandle = sensorStats.keyAt(ise);
            // TODO(b/178127364): remove BatteryStats.Uid.Sensor.GPS and references to it.
            if (sensorHandle == BatteryStats.Uid.Sensor.GPS) {
                continue;
            }

            final BatteryStats.Uid.Sensor sensor = sensorStats.valueAt(ise);
            final BatteryStats.Timer timer = sensor.getSensorTime();
            final long sensorTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            if (sensorTime != 0) {
                Sensor s = mSensors.get(sensorHandle);
                if (s != null) {
                    powerMah += (sensorTime * s.getPower()) / (1000 * 60 * 60);
                }
            }
        }
        return powerMah;
    }
}
