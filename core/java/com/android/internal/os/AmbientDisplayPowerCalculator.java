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
import android.util.SparseArray;

import java.util.List;

/**
 * Estimates power consumed by the ambient display
 */
public class AmbientDisplayPowerCalculator extends PowerCalculator {

    private final PowerProfile mPowerProfile;

    public AmbientDisplayPowerCalculator(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
    }

    /**
     * Ambient display power is the additional power the screen takes while in ambient display/
     * screen doze/ always-on display (interchangeable terms) mode. Ambient display power should
     * be hidden {@link BatteryStatsHelper#shouldHideSipper(BatterySipper)}, but should not be
     * included in smearing {@link BatteryStatsHelper#removeHiddenBatterySippers(List)}.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {

        long ambientDisplayMs = batteryStats.getScreenDozeTime(rawRealtimeUs, statsType) / 1000;
        double power = mPowerProfile.getAveragePower(PowerProfile.POWER_AMBIENT_DISPLAY)
                * ambientDisplayMs / (60 * 60 * 1000);
        if (power > 0) {
            BatterySipper bs = new BatterySipper(BatterySipper.DrainType.AMBIENT_DISPLAY, null, 0);
            bs.usagePowerMah = power;
            bs.usageTimeMs = ambientDisplayMs;
            bs.sumPower();
            sippers.add(bs);
        }
    }
}
