/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.util.IndentingPrintWriter;

import com.android.internal.os.BatteryStatsHistory;

public interface PowerAttributor {

    /**
     * Returns true if the specified power component can be handled by this PowerAttributor
     */
    boolean isPowerComponentSupported(@BatteryConsumer.PowerComponentId int powerComponentId);

    /**
     * Performs the power attribution calculations and returns the results by populating the
     * supplied BatteryUsageStats.Builder
     */
    void estimatePowerConsumption(BatteryUsageStats.Builder batteryUsageStatsBuilder,
            BatteryStatsHistory batteryHistory, long monotonicStartTime, long monotonicEndTime);

    /**
     * Computes estimated power consumption attribution for the specified time range and stores
     * it in PowerStatsStore for potential accumulation.
     *
     * Returns the monotonic timestamp of the last processed history item.
     */
    long storeEstimatedPowerConsumption(BatteryStatsHistory batteryStatsHistory, long startTime,
            long endTimeMs);

    /**
     * Returns the monotonic timestamp of the last processed history item, stored in
     * PowerStatsStore.
     */
    long getLastSavedEstimatesPowerConsumptionTimestamp();

    /**
     * Performs the power attribution calculation and prints the results.
     */
    void dumpEstimatedPowerConsumption(IndentingPrintWriter ipw,
            BatteryStatsHistory batteryStatsHistory, long startTime, long endTime);
}
