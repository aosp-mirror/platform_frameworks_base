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
 * Test BatteryStatsImpl.DualTimer.
 */
public class BatteryStatsDualTimerTest extends TestCase {

    @SmallTest
    public void testResetDetach() throws Exception {
        final MockClocks clocks = new MockClocks();
        clocks.realtime = clocks.uptime = 100;

        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());
        final BatteryStatsImpl.TimeBase subTimeBase = new BatteryStatsImpl.TimeBase();
        subTimeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        final BatteryStatsImpl.DualTimer timer = new BatteryStatsImpl.DualTimer(clocks,
                null, BatteryStats.WAKE_TYPE_PARTIAL, null, timeBase, subTimeBase);

        assertTrue(timeBase.hasObserver(timer));
        assertFalse(subTimeBase.hasObserver(timer));
        assertFalse(timeBase.hasObserver(timer.getSubTimer()));
        assertTrue(subTimeBase.hasObserver(timer.getSubTimer()));

        // Timer is running so resetting it should not remove it from timerbases.
        clocks.realtime = clocks.uptime = 200;
        timer.startRunningLocked(clocks.realtime);
        timer.reset(true);
        assertTrue(timeBase.hasObserver(timer));
        assertTrue(subTimeBase.hasObserver(timer.getSubTimer()));

        // Stop timer and ensure that resetting removes it from timebases.
        clocks.realtime = clocks.uptime = 300;
        timer.stopRunningLocked(clocks.realtime);
        timer.reset(true);
        assertFalse(timeBase.hasObserver(timer));
        assertFalse(timeBase.hasObserver(timer.getSubTimer()));
    }
}
