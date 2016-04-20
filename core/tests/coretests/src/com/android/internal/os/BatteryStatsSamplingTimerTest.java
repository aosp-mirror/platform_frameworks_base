/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.internal.os;

import android.os.BatteryStats;
import android.os.Parcel;
import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

public class BatteryStatsSamplingTimerTest extends TestCase {

    @SmallTest
    public void testSampleTimerSummaryParceling() throws Exception {
        final MockClocks clocks = new MockClocks();
        clocks.realtime = 0;
        clocks.uptime = 0;

        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        BatteryStatsImpl.SamplingTimer timer = new BatteryStatsImpl.SamplingTimer(clocks, timeBase,
                true);

        // Start running on battery.
        timeBase.setRunning(true, clocks.uptimeMillis(), clocks.elapsedRealtime());

        // The first update on battery consumes the values as a way of starting cleanly.
        timer.addCurrentReportedTotalTime(10);
        timer.addCurrentReportedCount(1);

        timer.addCurrentReportedTotalTime(10);
        timer.addCurrentReportedCount(1);

        clocks.realtime = 20;
        clocks.uptime = 20;

        assertEquals(10, timer.getTotalTimeLocked(clocks.elapsedRealtime(),
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Grab a summary parcel while on battery.
        final Parcel onBatterySummaryParcel = Parcel.obtain();
        timer.writeSummaryFromParcelLocked(onBatterySummaryParcel, clocks.elapsedRealtime() * 1000);
        onBatterySummaryParcel.setDataPosition(0);

        // Stop running on battery.
        timeBase.setRunning(false, clocks.uptimeMillis(), clocks.elapsedRealtime());

        assertEquals(10, timer.getTotalTimeLocked(clocks.elapsedRealtime(),
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Grab a summary parcel while not on battery.
        final Parcel offBatterySummaryParcel = Parcel.obtain();
        timer.writeSummaryFromParcelLocked(offBatterySummaryParcel,
                clocks.elapsedRealtime() * 1000);
        offBatterySummaryParcel.setDataPosition(0);

        // Read the on battery summary from the parcel.
        BatteryStatsImpl.SamplingTimer unparceledTimer = new BatteryStatsImpl.SamplingTimer(
                clocks, timeBase, true);
        unparceledTimer.readSummaryFromParcelLocked(onBatterySummaryParcel);

        assertEquals(10, unparceledTimer.getTotalTimeLocked(0, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Read the off battery summary from the parcel.
        unparceledTimer = new BatteryStatsImpl.SamplingTimer(clocks, timeBase, true);
        unparceledTimer.readSummaryFromParcelLocked(offBatterySummaryParcel);

        assertEquals(10, unparceledTimer.getTotalTimeLocked(0, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }
}
