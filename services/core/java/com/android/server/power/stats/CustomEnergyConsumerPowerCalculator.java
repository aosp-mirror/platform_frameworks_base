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
package com.android.server.power.stats;

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.PowerProfile;

import java.util.Arrays;

/**
 * Calculates the amount of power consumed by custom energy consumers (i.e. consumers of type
 * {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
 */
public class CustomEnergyConsumerPowerCalculator extends PowerCalculator {
    private static final String TAG = "CustomEnergyCsmrPowerCalc";

    public CustomEnergyConsumerPowerCalculator(PowerProfile powerProfile) {
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

        final double[] customEnergyConsumerPowerMah = uCtoMah(
                batteryStats.getCustomEnergyConsumerBatteryConsumptionUC());
        if (customEnergyConsumerPowerMah != null) {
            final AggregateBatteryConsumer.Builder deviceBatteryConsumerBuilder =
                    builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
            for (int i = 0; i < customEnergyConsumerPowerMah.length; i++) {
                deviceBatteryConsumerBuilder.setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customEnergyConsumerPowerMah[i]);
            }
        }
        if (totalAppPowerMah != null) {
            final AggregateBatteryConsumer.Builder appsBatteryConsumerBuilder =
                    builder.getAggregateBatteryConsumerBuilder(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
            for (int i = 0; i < totalAppPowerMah.length; i++) {
                appsBatteryConsumerBuilder.setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        totalAppPowerMah[i]);
            }
        }
    }

    private double[] calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            double[] totalPowerMah) {
        double[] newTotalPowerMah = null;
        final double[] customEnergyConsumerPowerMah =
                uCtoMah(u.getCustomEnergyConsumerBatteryConsumptionUC());
        if (customEnergyConsumerPowerMah != null) {
            if (totalPowerMah == null) {
                newTotalPowerMah = new double[customEnergyConsumerPowerMah.length];
            } else if (totalPowerMah.length != customEnergyConsumerPowerMah.length) {
                Slog.wtf(TAG, "Number of custom energy components is not the same for all apps: "
                        + totalPowerMah.length + ", " + customEnergyConsumerPowerMah.length);
                newTotalPowerMah = Arrays.copyOf(totalPowerMah,
                        customEnergyConsumerPowerMah.length);
            } else {
                newTotalPowerMah = totalPowerMah;
            }
            for (int i = 0; i < customEnergyConsumerPowerMah.length; i++) {
                app.setConsumedPower(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customEnergyConsumerPowerMah[i]);
                if (!app.isVirtualUid()) {
                    newTotalPowerMah[i] += customEnergyConsumerPowerMah[i];
                }
            }
        }
        return newTotalPowerMah;
    }

    private double[] uCtoMah(long[] chargeUC) {
        if (chargeUC == null) {
            return null;
        }
        final double[] mah = new double[chargeUC.length];
        for (int i = 0; i < chargeUC.length; i++) {
            mah[i] = uCtoMah(chargeUC[i]);
        }
        return mah;
    }
}
