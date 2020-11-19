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

import android.os.BatteryStats;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

/**
 * Estimates the amount of power consumed when the device is idle.
 */
public class IdlePowerCalculator extends PowerCalculator {
    private static final String TAG = "IdlePowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final PowerProfile mPowerProfile;

    public IdlePowerCalculator(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
    }

    /**
     * Calculate the baseline power usage for the device when it is in suspend and idle.
     * The device is drawing POWER_CPU_SUSPEND power at its lowest power state.
     * The device is drawing POWER_CPU_SUSPEND + POWER_CPU_IDLE power when a wakelock is held.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        long batteryUptimeUs = batteryStats.computeBatteryUptime(rawUptimeUs, statsType);
        long batteryRealtimeUs = batteryStats.computeBatteryRealtime(rawRealtimeUs, statsType);

        if (DEBUG) {
            Log.d(TAG, "Battery type time: realtime=" + (batteryRealtimeUs / 1000) + " uptime="
                    + (batteryUptimeUs / 1000));
        }

        final double suspendPowerMaMs = (batteryRealtimeUs / 1000)
                * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_SUSPEND);
        final double idlePowerMaMs = (batteryUptimeUs / 1000)
                * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE);
        final double totalPowerMah = (suspendPowerMaMs + idlePowerMaMs) / (60 * 60 * 1000);
        if (DEBUG && totalPowerMah != 0) {
            Log.d(TAG, "Suspend: time=" + (batteryRealtimeUs / 1000)
                    + " power=" + formatCharge(suspendPowerMaMs / (60 * 60 * 1000)));
            Log.d(TAG, "Idle: time=" + (batteryUptimeUs / 1000)
                    + " power=" + formatCharge(idlePowerMaMs / (60 * 60 * 1000)));
        }

        if (totalPowerMah != 0) {
            BatterySipper bs = new BatterySipper(BatterySipper.DrainType.IDLE, null, 0);
            bs.usagePowerMah = totalPowerMah;
            bs.usageTimeMs = batteryRealtimeUs / 1000;
            bs.sumPower();
            sippers.add(bs);
        }
    }
}
