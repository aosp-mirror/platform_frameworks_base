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

package com.android.server.power.stats.format;

import android.annotation.NonNull;
import android.os.PersistableBundle;

import com.android.internal.os.PowerStats;

public class BasePowerStatsLayout extends PowerStatsLayout {
    private static final String EXTRA_DEVICE_BATTERY_DISCHARGE = "d-bd";
    private static final String EXTRA_DEVICE_BATTERY_DISCHARGE_PCT = "d-bdp";
    private static final String EXTRA_DEVICE_BATTERY_DISCHARGE_DURATION = "d-bdd";
    private final int mDeviceBatteryDischargePosition;
    private final int mDeviceBatteryDischargePercentPosition;
    private final int mDeviceBatteryDischargeDurationPosition;

    public BasePowerStatsLayout() {
        addDeviceSectionUsageDuration();
        addUidSectionUsageDuration();
        mDeviceBatteryDischargePosition = addDeviceSection(1, "discharge");
        // Stored with a 1000000 multiplier for precision
        mDeviceBatteryDischargePercentPosition = addDeviceSection(1, "discharge-pct",
                FLAG_FORMAT_AS_POWER);
        mDeviceBatteryDischargeDurationPosition = addDeviceSection(1, "discharge-duration");
    }

    public BasePowerStatsLayout(@NonNull PowerStats.Descriptor descriptor) {
        super(descriptor);
        PersistableBundle extras = descriptor.extras;
        mDeviceBatteryDischargePosition = extras.getInt(EXTRA_DEVICE_BATTERY_DISCHARGE);
        mDeviceBatteryDischargePercentPosition = extras.getInt(EXTRA_DEVICE_BATTERY_DISCHARGE_PCT);
        mDeviceBatteryDischargeDurationPosition =
                extras.getInt(EXTRA_DEVICE_BATTERY_DISCHARGE_DURATION);
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_BATTERY_DISCHARGE, mDeviceBatteryDischargePosition);
        extras.putInt(EXTRA_DEVICE_BATTERY_DISCHARGE_PCT, mDeviceBatteryDischargePercentPosition);
        extras.putInt(EXTRA_DEVICE_BATTERY_DISCHARGE_DURATION,
                mDeviceBatteryDischargeDurationPosition);
    }

    /**
     * Accumulates battery discharge amount.
     */
    public void addBatteryDischargeUah(long[] stats, long dischargeUah) {
        stats[mDeviceBatteryDischargePosition] += dischargeUah;
    }

    /**
     * Returns accumulated battery discharge amount.
     */
    public long getBatteryDischargeUah(long[] stats) {
        return stats[mDeviceBatteryDischargePosition];
    }

    /**
     * Accumulates battery discharge in percentage points.
     */
    public void addBatteryDischargePercent(long[] stats, int dischargePct) {
        // store pct * 1000000 for better rounding precision
        stats[mDeviceBatteryDischargePercentPosition] += dischargePct * 1000000L;
    }

    /**
     * Returns battery discharge amount as percentage of battery capacity.   May exceed 100% if
     * the battery was recharged/discharged during the power stats collection session.
     */
    public double getBatteryDischargePercent(long[] stats) {
        return (int) stats[mDeviceBatteryDischargePercentPosition] / 1000000.0;
    }

    /**
     * Accumulates battery discharge duration.
     */
    public void addBatteryDischargeDuration(long[] stats, long durationMs) {
        stats[mDeviceBatteryDischargeDurationPosition] += durationMs;
    }

    /**
     * Returns accumulated battery discharge duration.
     */
    public long getBatteryDischargeDuration(long[] stats) {
        return (int) stats[mDeviceBatteryDischargeDurationPosition];
    }
}
