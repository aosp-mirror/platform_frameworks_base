/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl.StopwatchTimer.
 */
public class BatteryStatsStopwatchTimerTest extends TestCase {

    // Primarily testing previous bug that incremented count when timeBase was off and bug that gave
    // negative values of count.
    @SmallTest
    public void testCount() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());
        final BatteryStatsImpl.StopwatchTimer timer = new BatteryStatsImpl.StopwatchTimer(clocks,
                null, BatteryStats.SENSOR, null, timeBase);
        int expectedCount = 0;

        // for timeBase off tests
        timeBase.setRunning(false, 1000 * clocks.realtime, 1000 * clocks.realtime);

        // timeBase off, start, stop
        timer.startRunningLocked(updateTime(clocks, 100)); // start
        // Used to fail due to b/36730213 increasing count.
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.startRunningLocked(updateTime(clocks, 110)); // start
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 120)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 130)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase off, start and immediately stop
        timer.startRunningLocked(updateTime(clocks, 200)); // start
        timer.stopRunningLocked(updateTime(clocks, 200)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase off, start, reset, stop
        timer.startRunningLocked(updateTime(clocks, 300)); // start
        updateTime(clocks, 310);
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());
        timer.reset(false);
        expectedCount = 0; // count will be reset by reset()
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 350)); // stop
        // Used to fail due to b/30099724 giving -1.
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase off, start and immediately reset, stop
        timer.startRunningLocked(updateTime(clocks, 400)); // start
        timer.reset(false);
        expectedCount = 0; // count will be reset by reset()
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 450)); // stop
        // Used to fail due to b/30099724 giving -1.
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));


        // for timeBase on tests
        updateTime(clocks, 2000);
        timeBase.setRunning(true, 1000 * clocks.realtime, 1000 * clocks.realtime);
        assertFalse(timer.isRunningLocked());

        // timeBase on, start, stop
        timer.startRunningLocked(updateTime(clocks, 2100)); // start
        expectedCount++;
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.startRunningLocked(updateTime(clocks, 2110)); // start
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 2120)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 2130)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase on, start and immediately stop
        timer.startRunningLocked(updateTime(clocks, 2200)); // start
        timer.stopRunningLocked(updateTime(clocks, 2200)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase on, start, reset, stop
        timer.startRunningLocked(updateTime(clocks, 2300)); // start
        updateTime(clocks, 2310);
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());
        timer.reset(false);
        expectedCount = 0; // count will be reset by reset()
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 2350)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase on, start and immediately reset, stop
        timer.startRunningLocked(updateTime(clocks, 2400)); // start
        timer.reset(false);
        expectedCount = 0; // count will be reset by reset()
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        timer.stopRunningLocked(updateTime(clocks, 2450)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));


        // change timeBase tests
        // timeBase off, start
        updateTime(clocks, 3000);
        timeBase.setRunning(false, 1000 * clocks.realtime, 1000 * clocks.realtime);
        timer.startRunningLocked(updateTime(clocks, 3100)); // start
        // Used to fail due to b/36730213 increasing count.
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        // timeBase on, stop
        updateTime(clocks, 3200);
        timeBase.setRunning(true, 1000 * clocks.realtime, 1000 * clocks.realtime);
        timer.stopRunningLocked(updateTime(clocks, 3300)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));

        // timeBase on, start
        timer.startRunningLocked(updateTime(clocks, 3400)); // start
        expectedCount++;
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        // timeBase off, stop
        updateTime(clocks, 3500);
        timeBase.setRunning(false, 1000 * clocks.realtime, 1000 * clocks.realtime);
        timer.stopRunningLocked(updateTime(clocks, 3600)); // stop
        assertEquals(expectedCount, timer.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
    }

    private static long updateTime(MockClocks clocks, long time) {
        return clocks.realtime = clocks.uptime = time;
    }
}
