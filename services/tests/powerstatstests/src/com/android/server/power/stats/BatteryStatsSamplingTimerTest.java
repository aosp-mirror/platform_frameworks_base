/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.BatteryStats;
import android.os.Parcel;
import android.os.SystemClock;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import org.mockito.Mockito;

public class BatteryStatsSamplingTimerTest extends TestCase {

    @SmallTest
    public void testSettingStalePreservesData() throws Exception {
        final MockClock clocks = new MockClock();
        final BatteryStatsImpl.SamplingTimer timer = new BatteryStatsImpl.SamplingTimer(clocks,
                Mockito.mock(BatteryStatsImpl.TimeBase.class));

        timer.onTimeStarted(100, 100, 100);

        // First update is absorbed.
        timer.update(10, 1, SystemClock.elapsedRealtime() * 1000);

        timer.update(20, 2, SystemClock.elapsedRealtime() * 1000);

        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(10, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));

        timer.endSample();

        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(10, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));

        timer.onTimeStopped(200, 200, 200);

        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(10, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testEndSampleAndContinueWhenTimeOrCountDecreases() throws Exception {
        final MockClock clocks = new MockClock();
        final BatteryStatsImpl.TimeBase timeBase = Mockito.mock(BatteryStatsImpl.TimeBase.class);
        final BatteryStatsImpl.SamplingTimer timer = new BatteryStatsImpl.SamplingTimer(clocks,
                timeBase);

        // First once is absorbed.
        timer.update(10, 1, SystemClock.elapsedRealtime() * 1000);

        timer.add(10, 1);

        assertEquals(0, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));

        // This is less than we currently have, so we will end the sample. Time isn't running, so
        // nothing should happen, except that tracking will stop.
        timer.update(0, 0, SystemClock.elapsedRealtime() * 1000);

        // Start tracking again
        timer.update(0, 0, SystemClock.elapsedRealtime() * 1000);

        assertEquals(0, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));

        timer.onTimeStarted(100, 100, 100);

        // This should add.
        timer.add(100, 10);

        assertEquals(100, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(10, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // This is less than we currently have, so we should end our sample.
        timer.update(30, 3, SystemClock.elapsedRealtime() * 1000);

        // Restart tracking
        timer.update(30, 3, SystemClock.elapsedRealtime() * 1000);

        timer.add(50, 5);

        assertEquals(150, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(15, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        timer.onTimeStopped(200, 200, 200);

        assertEquals(150, timer.getTotalTimeLocked(200, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(15, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testFirstUpdateIsAbsorbed() throws Exception {
        final MockClock clocks = new MockClock();
        final BatteryStatsImpl.TimeBase timeBase = Mockito.mock(BatteryStatsImpl.TimeBase.class);

        BatteryStatsImpl.SamplingTimer timer = new BatteryStatsImpl.SamplingTimer(clocks, timeBase);

        // This should be absorbed because it is our first update and we don't know what
        // was being counted before.
        timer.update(10, 1, SystemClock.elapsedRealtime() * 1000);

        assertEquals(0, timer.getTotalTimeLocked(10, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        timer = new BatteryStatsImpl.SamplingTimer(clocks, timeBase);
        timer.onTimeStarted(100, 100, 100);

        // This should be absorbed.
        timer.update(10, 1, SystemClock.elapsedRealtime() * 1000);

        assertEquals(0, timer.getTotalTimeLocked(100, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // This should NOT be aborbed, since we've already done that.
        timer.add(10, 1);

        assertEquals(10, timer.getTotalTimeLocked(100, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        timer.onTimeStopped(200, 200, 200);
        timer.onTimeStarted(300, 300, 300);

        // This should NOT be absorbed.
        timer.add(10, 1);

        assertEquals(20, timer.getTotalTimeLocked(300, BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(2, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testSampleTimerSummaryParceling() throws Exception {
        final MockClock clocks = new MockClock();
        clocks.realtime = 0;
        clocks.uptime = 0;

        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        BatteryStatsImpl.SamplingTimer timer = new BatteryStatsImpl.SamplingTimer(clocks, timeBase);

        // Start running on battery.
        // (Note that the wrong units are used in this class. setRunning is actually supposed to
        // take us, not the ms that clocks uses.)
        timeBase.setRunning(true, clocks.uptimeMillis(), clocks.elapsedRealtime());

        // The first update on battery consumes the values as a way of starting cleanly.
        timer.add(10, 1);

        timer.add(10, 1);

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

        // Set the timebase running again. That way any issues with tracking reported values
        // get tested when we unparcel the timers below.
        timeBase.setRunning(true, clocks.uptimeMillis(), clocks.elapsedRealtime());

        // Read the on battery summary from the parcel.
        BatteryStatsImpl.SamplingTimer unparceledOnBatteryTimer =
                new BatteryStatsImpl.SamplingTimer(clocks, timeBase);
        unparceledOnBatteryTimer.readSummaryFromParcelLocked(onBatterySummaryParcel);

        assertEquals(10, unparceledOnBatteryTimer.getTotalTimeLocked(0,
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledOnBatteryTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Read the off battery summary from the parcel.
        BatteryStatsImpl.SamplingTimer unparceledOffBatteryTimer =
                new BatteryStatsImpl.SamplingTimer(clocks, timeBase);
        unparceledOffBatteryTimer.readSummaryFromParcelLocked(offBatterySummaryParcel);

        assertEquals(10, unparceledOffBatteryTimer.getTotalTimeLocked(0,
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledOffBatteryTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // Now, just like with a fresh timer, the first update should be absorbed to account for
        // data being collected when we weren't recording.
        unparceledOnBatteryTimer.update(10, 10, SystemClock.elapsedRealtime() * 1000);

        assertEquals(10, unparceledOnBatteryTimer.getTotalTimeLocked(0,
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledOnBatteryTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        unparceledOffBatteryTimer.update(10, 10, SystemClock.elapsedRealtime() * 1000);

        assertEquals(10, unparceledOffBatteryTimer.getTotalTimeLocked(0,
                BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, unparceledOffBatteryTimer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }
}
