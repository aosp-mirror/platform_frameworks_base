/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.Slog;
import android.util.SparseArray;

import java.util.Arrays;

/**
 * Calculates the amount of power consumed by custom energy consumers (i.e. consumers of type
 * {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
 */
public class CustomMeasuredPowerCalculator extends PowerCalculator {
    private static final String TAG = "CustomMeasuredPowerCalc";

    public CustomMeasuredPowerCalculator(PowerProfile powerProfile) {
    }

    @Override
    public boolean isPowerComponentSupported(int powerComponent) {
        return false;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        double[] totalAppPowerMah = null;

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            totalAppPowerMah = calculateApp(app, app.getBatteryStatsUid(), totalAppPowerMah);
        }

        final double[] customMeasuredPowerMah = calculateMeasuredEnergiesMah(
                batteryStats.getCustomConsumerMeasuredBatteryConsumptionUC());
        if (customMeasuredPowerMah != null) {
            final AggregateBatteryConsumer.Builder deviceBatteryConsumerBuilder =
                    builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
            for (int i = 0; i < customMeasuredPowerMah.length; i++) {
                deviceBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customMeasuredPowerMah[i]);
            }
        }
        if (totalAppPowerMah != null) {
            final AggregateBatteryConsumer.Builder appsBatteryConsumerBuilder =
                    builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
            for (int i = 0; i < totalAppPowerMah.length; i++) {
                appsBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        totalAppPowerMah[i]);
            }
        }
    }

    private double[] calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            double[] totalPowerMah) {
        double[] newTotalPowerMah = null;
        final double[] customMeasuredPowerMah = calculateMeasuredEnergiesMah(
                u.getCustomConsumerMeasuredBatteryConsumptionUC());
        if (customMeasuredPowerMah != null) {
            if (totalPowerMah == null) {
                newTotalPowerMah = new double[customMeasuredPowerMah.length];
            } else if (totalPowerMah.length != customMeasuredPowerMah.length) {
                Slog.wtf(TAG, "Number of custom energy components is not the same for all apps: "
                        + totalPowerMah.length + ", " + customMeasuredPowerMah.length);
                newTotalPowerMah = Arrays.copyOf(totalPowerMah, customMeasuredPowerMah.length);
            } else {
                newTotalPowerMah = totalPowerMah;
            }
            for (int i = 0; i < customMeasuredPowerMah.length; i++) {
                app.setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customMeasuredPowerMah[i]);
                if (!app.isVirtualUid()) {
                    newTotalPowerMah[i] += customMeasuredPowerMah[i];
                }
            }
        }
        return newTotalPowerMah;
    }

    private double[] calculateMeasuredEnergiesMah(long[] measuredChargeUC) {
        if (measuredChargeUC == null) {
            return null;
        }
        final double[] measuredEnergiesMah = new double[measuredChargeUC.length];
        for (int i = 0; i < measuredChargeUC.length; i++) {
            measuredEnergiesMah[i] = uCtoMah(measuredChargeUC[i]);
        }
        return measuredEnergiesMah;
    }
}
