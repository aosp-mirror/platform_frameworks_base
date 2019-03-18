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

import android.os.BatteryStats;

/**
 * Calculates power use of a device subsystem for an app.
 */
public abstract class PowerCalculator {
    /**
     * Calculate the amount of power an app used for this subsystem.
     * @param app The BatterySipper that represents the power use of an app.
     * @param u The recorded stats for the app.
     * @param rawRealtimeUs The raw system realtime in microseconds.
     * @param rawUptimeUs The raw system uptime in microseconds.
     * @param statsType The type of stats. As of {@link android.os.Build.VERSION_CODES#Q}, this can
     *                  only be {@link BatteryStats#STATS_SINCE_CHARGED}, since
     *                  {@link BatteryStats#STATS_CURRENT} and
     *                  {@link BatteryStats#STATS_SINCE_UNPLUGGED} are deprecated.
     */
    public abstract void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                                      long rawUptimeUs, int statsType);

    /**
     * Calculate the remaining power that can not be attributed to an app.
     * @param app The BatterySipper that will represent this remaining power.
     * @param stats The BatteryStats object from which to retrieve data.
     * @param rawRealtimeUs The raw system realtime in microseconds.
     * @param rawUptimeUs The raw system uptime in microseconds.
     * @param statsType The type of stats. As of {@link android.os.Build.VERSION_CODES#Q}, this can
     *                  only be {@link BatteryStats#STATS_SINCE_CHARGED}, since
     *                  {@link BatteryStats#STATS_CURRENT} and
     *                  {@link BatteryStats#STATS_SINCE_UNPLUGGED} are deprecated.
     */
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
    }

    /**
     * Reset any state maintained in this calculator.
     */
    public void reset() {
    }
}
