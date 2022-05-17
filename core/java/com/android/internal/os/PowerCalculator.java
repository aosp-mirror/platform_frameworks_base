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

import android.annotation.NonNull;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.SparseArray;

import java.io.PrintWriter;

/**
 * Calculates power use of a device subsystem for an app.
 */
public abstract class PowerCalculator {
    protected static final boolean DEBUG = false;

    protected static final double MILLIAMPHOUR_PER_MICROCOULOMB = 1.0 / 1000.0 / 60.0 / 60.0;

    /**
     * Returns true if this power calculator computes power/duration for the specified
     * power component.
     */
    public abstract boolean isPowerComponentSupported(
            @BatteryConsumer.PowerComponent int powerComponent);

    /**
     * Attributes the total amount of power used by this subsystem to various consumers such
     * as apps.
     *
     * @param builder       {@link BatteryUsageStats.Builder that contains a list of
     *                      per-UID battery consumer builders for attribution data.
     *                      The calculator may modify the builder and its constituent parts.
     * @param batteryStats  The recorded battery stats.
     * @param rawRealtimeUs The raw system realtime in microseconds.
     * @param rawUptimeUs   The raw system uptime in microseconds.
     * @param query         The query parameters for the calculator.
     */
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(app, app.getBatteryStatsUid(), rawRealtimeUs, rawUptimeUs, query);
        }
    }

    /**
     * Calculate the amount of power an app used for this subsystem.
     * @param app The UidBatteryConsumer.Builder that represents the power use of an app.
     * @param u The recorded stats for the app.
     * @param rawRealtimeUs The raw system realtime in microseconds.
     * @param rawUptimeUs The raw system uptime in microseconds.
     * @param query Power calculation parameters.
     */
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
    }

    /**
     * Reset any state maintained in this calculator.
     */
    public void reset() {
    }

    protected static @BatteryConsumer.PowerModel int getPowerModel(
            long measuredEnergyUC, @NonNull BatteryUsageStatsQuery query) {
        if (measuredEnergyUC != BatteryStats.POWER_DATA_UNAVAILABLE
                && !query.shouldForceUsePowerProfileModel()) {
            return BatteryConsumer.POWER_MODEL_MEASURED_ENERGY;
        }
        return BatteryConsumer.POWER_MODEL_POWER_PROFILE;
    }

    protected static @BatteryConsumer.PowerModel int getPowerModel(long measuredEnergyUC) {
        return measuredEnergyUC != BatteryStats.POWER_DATA_UNAVAILABLE
                ? BatteryConsumer.POWER_MODEL_MEASURED_ENERGY
                : BatteryConsumer.POWER_MODEL_POWER_PROFILE;
    }

    /**
     * Prints formatted amount of power in milli-amp-hours.
     */
    public static void printPowerMah(PrintWriter pw, double powerMah) {
        pw.print(BatteryStats.formatCharge(powerMah));
    }

    static double uCtoMah(long chargeUC) {
        return chargeUC * MILLIAMPHOUR_PER_MICROCOULOMB;
    }
}
