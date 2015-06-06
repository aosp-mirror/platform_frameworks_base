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
 * Power calculator for the flashlight.
 */
public class FlashlightPowerCalculator extends PowerCalculator {
    private final double mFlashlightPowerOnAvg;

    public FlashlightPowerCalculator(PowerProfile profile) {
        mFlashlightPowerOnAvg = profile.getAveragePower(PowerProfile.POWER_FLASHLIGHT);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        // Calculate flashlight power usage.  Right now, this is based on the average power draw
        // of the flash unit when kept on over a short period of time.
        final BatteryStats.Timer timer = u.getFlashlightTurnedOnTimer();
        if (timer != null) {
            final long totalTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            app.flashlightTimeMs = totalTime;
            app.flashlightPowerMah = (totalTime * mFlashlightPowerOnAvg) / (1000*60*60);
        } else {
            app.flashlightTimeMs = 0;
            app.flashlightPowerMah = 0;
        }
    }
}
