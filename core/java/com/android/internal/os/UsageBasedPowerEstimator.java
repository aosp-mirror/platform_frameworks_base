/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * Implements a simple linear power model based on the assumption that the power consumer
 * consumes a fixed current when it is used and no current when it is unused.
 *
 * <code>power = usageDuration * averagePower</code>
 */
public class UsageBasedPowerEstimator {
    private static final double MILLIS_IN_HOUR = 1000.0 * 60 * 60;
    private final double mAveragePowerMahPerMs;

    public UsageBasedPowerEstimator(double averagePowerMilliAmp) {
        mAveragePowerMahPerMs = averagePowerMilliAmp / MILLIS_IN_HOUR;
    }

    public boolean isSupported() {
        return mAveragePowerMahPerMs != 0;
    }

    /**
     * Given a {@link BatteryStats.Timer}, returns the accumulated duration.
     */
    public long calculateDuration(BatteryStats.Timer timer, long rawRealtimeUs, int statsType) {
        return timer == null ? 0 : timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
    }

    /**
     * Given a duration in milliseconds, return the estimated power consumption.
     */
    public double calculatePower(long durationMs) {
        return mAveragePowerMahPerMs * durationMs;
    }
}
