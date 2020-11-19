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
 * Estimates power consumed by the screen(s)
 */
public class ScreenPowerCalculator extends PowerCalculator {
    private static final String TAG = "ScreenPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;

    private final PowerProfile mPowerProfile;

    public ScreenPowerCalculator(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
    }

    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        double power = 0;
        final long screenOnTimeMs = batteryStats.getScreenOnTime(rawRealtimeUs, statsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            final long brightnessTime =
                    batteryStats.getScreenBrightnessTime(i, rawRealtimeUs, statsType) / 1000;
            final double p = screenBinPower * brightnessTime;
            if (DEBUG && p != 0) {
                Log.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + formatCharge(p / (60 * 60 * 1000)));
            }
            power += p;
        }
        power /= (60 * 60 * 1000); // To hours
        if (power != 0) {
            final BatterySipper bs = new BatterySipper(BatterySipper.DrainType.SCREEN, null, 0);
            bs.usagePowerMah = power;
            bs.usageTimeMs = screenOnTimeMs;
            bs.sumPower();
            sippers.add(bs);
        }
    }
}
