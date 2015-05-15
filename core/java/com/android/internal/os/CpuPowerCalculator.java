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

public class CpuPowerCalculator extends PowerCalculator {
    private static final String TAG = "CpuPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;

    private final double[] mPowerCpuNormal;

    /**
     * Reusable array for calculations.
     */
    private final long[] mSpeedStepTimes;

    public CpuPowerCalculator(PowerProfile profile) {
        final int speedSteps = profile.getNumSpeedSteps();
        mPowerCpuNormal = new double[speedSteps];
        mSpeedStepTimes = new long[speedSteps];
        for (int p = 0; p < speedSteps; p++) {
            mPowerCpuNormal[p] = profile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE, p);
        }
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        final int speedSteps = mSpeedStepTimes.length;

        long totalTimeAtSpeeds = 0;
        for (int step = 0; step < speedSteps; step++) {
            mSpeedStepTimes[step] = u.getTimeAtCpuSpeed(step, statsType);
            totalTimeAtSpeeds += mSpeedStepTimes[step];
        }
        totalTimeAtSpeeds = Math.max(totalTimeAtSpeeds, 1);

        app.cpuTimeMs = (u.getUserCpuTimeUs(statsType) + u.getSystemCpuTimeUs(statsType)) / 1000;
        if (DEBUG && app.cpuTimeMs != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": CPU time " + app.cpuTimeMs + " ms");
        }

        double cpuPowerMaMs = 0;
        for (int step = 0; step < speedSteps; step++) {
            final double ratio = (double) mSpeedStepTimes[step] / totalTimeAtSpeeds;
            final double cpuSpeedStepPower = ratio * app.cpuTimeMs * mPowerCpuNormal[step];
            if (DEBUG && ratio != 0) {
                Log.d(TAG, "UID " + u.getUid() + ": CPU step #"
                        + step + " ratio=" + BatteryStatsHelper.makemAh(ratio) + " power="
                        + BatteryStatsHelper.makemAh(cpuSpeedStepPower / (60 * 60 * 1000)));
            }
            cpuPowerMaMs += cpuSpeedStepPower;
        }

        if (DEBUG && cpuPowerMaMs != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": cpu total power="
                    + BatteryStatsHelper.makemAh(cpuPowerMaMs / (60 * 60 * 1000)));
        }

        // Keep track of the package with highest drain.
        double highestDrain = 0;

        app.cpuFgTimeMs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
        final int processStatsCount = processStats.size();
        for (int i = 0; i < processStatsCount; i++) {
            final BatteryStats.Uid.Proc ps = processStats.valueAt(i);
            final String processName = processStats.keyAt(i);
            app.cpuFgTimeMs += ps.getForegroundTime(statsType);

            final long costValue = ps.getUserTime(statsType) + ps.getSystemTime(statsType)
                    + ps.getForegroundTime(statsType);

            // Each App can have multiple packages and with multiple running processes.
            // Keep track of the package who's process has the highest drain.
            if (app.packageWithHighestDrain == null ||
                    app.packageWithHighestDrain.startsWith("*")) {
                highestDrain = costValue;
                app.packageWithHighestDrain = processName;
            } else if (highestDrain < costValue && !processName.startsWith("*")) {
                highestDrain = costValue;
                app.packageWithHighestDrain = processName;
            }
        }

        // Ensure that the CPU times make sense.
        if (app.cpuFgTimeMs > app.cpuTimeMs) {
            if (DEBUG && app.cpuFgTimeMs > app.cpuTimeMs + 10000) {
                Log.d(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
            }

            // Statistics may not have been gathered yet.
            app.cpuTimeMs = app.cpuFgTimeMs;
        }

        // Convert the CPU power to mAh
        app.cpuPowerMah = cpuPowerMaMs / (60 * 60 * 1000);
    }
}
