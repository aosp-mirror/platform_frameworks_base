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
import android.util.ArrayMap;
import android.util.Log;

public class WakelockPowerCalculator extends PowerCalculator {
    private static final String TAG = "WakelockPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final double mPowerWakelock;
    private long mTotalAppWakelockTimeMs = 0;

    public WakelockPowerCalculator(PowerProfile profile) {
        mPowerWakelock = profile.getAveragePower(PowerProfile.POWER_CPU_AWAKE);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        long wakeLockTimeUs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats =
                u.getWakelockStats();
        final int wakelockStatsCount = wakelockStats.size();
        for (int i = 0; i < wakelockStatsCount; i++) {
            final BatteryStats.Uid.Wakelock wakelock = wakelockStats.valueAt(i);

            // Only care about partial wake locks since full wake locks
            // are canceled when the user turns the screen off.
            BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            if (timer != null) {
                wakeLockTimeUs += timer.getTotalTimeLocked(rawRealtimeUs, statsType);
            }
        }
        app.wakeLockTimeMs = wakeLockTimeUs / 1000; // convert to millis
        mTotalAppWakelockTimeMs += app.wakeLockTimeMs;

        // Add cost of holding a wake lock.
        app.wakeLockPowerMah = (app.wakeLockTimeMs * mPowerWakelock) / (1000*60*60);
        if (DEBUG && app.wakeLockPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": wake " + app.wakeLockTimeMs
                    + " power=" + BatteryStatsHelper.makemAh(app.wakeLockPowerMah));
        }
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        long wakeTimeMillis = stats.getBatteryUptime(rawUptimeUs) / 1000;
        wakeTimeMillis -= mTotalAppWakelockTimeMs
                + (stats.getScreenOnTime(rawRealtimeUs, statsType) / 1000);
        if (wakeTimeMillis > 0) {
            final double power = (wakeTimeMillis * mPowerWakelock) / (1000*60*60);
            if (DEBUG) {
                Log.d(TAG, "OS wakeLockTime " + wakeTimeMillis + " power "
                        + BatteryStatsHelper.makemAh(power));
            }
            app.wakeLockTimeMs += wakeTimeMillis;
            app.wakeLockPowerMah += power;
        }
    }

    @Override
    public void reset() {
        mTotalAppWakelockTimeMs = 0;
    }
}
