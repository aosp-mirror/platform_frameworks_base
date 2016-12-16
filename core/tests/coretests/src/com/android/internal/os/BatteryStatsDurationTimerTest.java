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

/**
 * Test BatteryStatsImpl.DurationTimer.
 *
 * In these tests, unless otherwise commented, the time increments by
 * 2x + 100, to make the subtraction unlikely to alias to another time.
 */
public class BatteryStatsDurationTimerTest extends TestCase {

    @SmallTest
    public void testStartStop() throws Exception {
        final MockClocks clocks = new MockClocks();

        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        final BatteryStatsImpl.DurationTimer timer = new BatteryStatsImpl.DurationTimer(clocks,
                null, BatteryStats.WAKE_TYPE_PARTIAL, null, timeBase);

        // TimeBase running, timer not running: current and max are 0
        timeBase.setRunning(true, /* uptimeUs */ 0, /* realtimeUs */ 100*1000);
        assertFalse(timer.isRunningLocked());
        assertEquals(0, timer.getCurrentDurationMsLocked(300));
        assertEquals(0, timer.getMaxDurationMsLocked(301));

        // Start timer: current and max advance
        timer.startRunningLocked(700);
        assertTrue(timer.isRunningLocked());
        assertEquals(800, timer.getCurrentDurationMsLocked(1500));
        assertEquals(801, timer.getMaxDurationMsLocked(1501));

        // Stop timer: current resets to 0, max remains
        timer.stopRunningLocked(3100);
        assertFalse(timer.isRunningLocked());
        assertEquals(0, timer.getCurrentDurationMsLocked(6300));
        assertEquals(2400, timer.getMaxDurationMsLocked(6301));

        // Start time again, but check with a short time, and make sure max doesn't
        // increment.
        timer.startRunningLocked(12700);
        assertTrue(timer.isRunningLocked());
        assertEquals(100, timer.getCurrentDurationMsLocked(12800));
        assertEquals(2400, timer.getMaxDurationMsLocked(12801));

        // And stop it again, but with a short time, and make sure it doesn't increment.
        timer.stopRunningLocked(12900);
        assertFalse(timer.isRunningLocked());
        assertEquals(0, timer.getCurrentDurationMsLocked(13000));
        assertEquals(2400, timer.getMaxDurationMsLocked(13001));

        // Now start and check that the time doesn't increase if the two times are the same.
        timer.startRunningLocked(27000);
        assertTrue(timer.isRunningLocked());
        assertEquals(0, timer.getCurrentDurationMsLocked(27000));
        assertEquals(2400, timer.getMaxDurationMsLocked(27000));

        // Stop the TimeBase. The values should be frozen.
        timeBase.setRunning(false, /* uptimeUs */ 10, /* realtimeUs */ 55000*1000);
        assertTrue(timer.isRunningLocked());
        assertEquals(28000, timer.getCurrentDurationMsLocked(110100));
        assertEquals(28000, timer.getMaxDurationMsLocked(110101));

        // Start the TimeBase. The values should be the old value plus the delta
        // between when the timer restarted and the current time
        timeBase.setRunning(true, /* uptimeUs */ 10, /* realtimeUs */ 220100*1000);
        assertTrue(timer.isRunningLocked());
        assertEquals(28200, timer.getCurrentDurationMsLocked(220300));
        assertEquals(28201, timer.getMaxDurationMsLocked(220301));
    }

    @SmallTest
    public void testReset() throws Exception {
    }

    @SmallTest
    public void testParceling() throws Exception {
        final MockClocks clocks = new MockClocks();

        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        final BatteryStatsImpl.DurationTimer timer = new BatteryStatsImpl.DurationTimer(clocks,
                null, BatteryStats.WAKE_TYPE_PARTIAL, null, timeBase);

        // Start running on battery.
        clocks.realtime = 100;
        clocks.uptime = 10;
        timeBase.setRunning(true, clocks.uptimeMillis()*1000, clocks.elapsedRealtime()*1000);

        timer.startRunningLocked(300);

        // Check that it did start running
        assertEquals(400, timer.getMaxDurationMsLocked(700));
        assertEquals(401, timer.getCurrentDurationMsLocked(701));

        // Write summary
        final Parcel summaryParcel = Parcel.obtain();
        timer.writeSummaryFromParcelLocked(summaryParcel, 1500*1000);
        summaryParcel.setDataPosition(0);

        // Read summary
        final BatteryStatsImpl.DurationTimer summary = new BatteryStatsImpl.DurationTimer(clocks,
                null, BatteryStats.WAKE_TYPE_PARTIAL, null, timeBase);
        summary.startRunningLocked(3100);
        summary.readSummaryFromParcelLocked(summaryParcel);
        // The new one shouldn't be running, and therefore 0 for current time
        assertFalse(summary.isRunningLocked());
        assertEquals(0, summary.getCurrentDurationMsLocked(6300));
        // The new one should have the max duration that we had when we wrote it
        assertEquals(1200, summary.getMaxDurationMsLocked(6301));

        // Write full
        final Parcel fullParcel = Parcel.obtain();
        timer.writeToParcel(fullParcel, 1500*1000);
        fullParcel.setDataPosition(0);

        // Read full - Should be the same as the summary as far as DurationTimer is concerned.
        final BatteryStatsImpl.DurationTimer full = new BatteryStatsImpl.DurationTimer(clocks,
                null, BatteryStats.WAKE_TYPE_PARTIAL, null, timeBase, fullParcel);
        // The new one shouldn't be running, and therefore 0 for current time
        assertFalse(full.isRunningLocked());
        assertEquals(0, full.getCurrentDurationMsLocked(6300));
        // The new one should have the max duration that we had when we wrote it
        assertEquals(1200, full.getMaxDurationMsLocked(6301));
    }
}
