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
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl.Counter.
 */
public class BatteryStatsCounterTest extends TestCase {

    @SmallTest
    public void testCounter() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        final BatteryStatsImpl.Counter counter = new BatteryStatsImpl.Counter(timeBase);

        // timeBase off (i.e. plugged in)
        timeBase.setRunning(false, 1, 1);
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        assertEquals(0, counter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(0, counter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(0, counter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase on (i.e. unplugged)
        timeBase.setRunning(true, 2, 2);
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase off (i.e. plugged in)
        timeBase.setRunning(false, 3, 3);
        counter.stepAtomic();
        counter.stepAtomic();
        counter.stepAtomic();
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(4, counter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase on (i.e. unplugged)
        timeBase.setRunning(true, 4, 4);
        counter.stepAtomic();
        counter.stepAtomic();
        assertEquals(6, counter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(6, counter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(2, counter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));
    }


    @SmallTest
    public void testParceling() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        final BatteryStatsImpl.TimeBase timeBase = new BatteryStatsImpl.TimeBase();
        timeBase.init(clocks.uptimeMillis(), clocks.elapsedRealtime());

        final BatteryStatsImpl.Counter origCounter = new BatteryStatsImpl.Counter(timeBase);

        // timeBase on (i.e. unplugged)
        timeBase.setRunning(true, 1, 1);
        origCounter.stepAtomic(); origCounter.stepAtomic(); origCounter.stepAtomic(); // three times
        assertEquals(3, origCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(3, origCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(3, origCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // Test summary parcelling (from origCounter)
        final Parcel summaryParcel = Parcel.obtain();
        origCounter.writeSummaryFromParcelLocked(summaryParcel);
        summaryParcel.setDataPosition(0);
        final BatteryStatsImpl.Counter summaryCounter = new BatteryStatsImpl.Counter(timeBase);
        summaryCounter.stepAtomic(); // occurs before reading the summary, so must not count later
        summaryCounter.readSummaryFromParcelLocked(summaryParcel);

        // timeBase still on (i.e. unplugged)
        summaryCounter.stepAtomic(); // once
        assertEquals(4, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, summaryCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(1, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase off (i.e. plugged in)
        timeBase.setRunning(false, 3, 3);
        summaryCounter.stepAtomic(); summaryCounter.stepAtomic(); // twice
        assertEquals(4, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(1, summaryCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(1, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase on (i.e. unplugged)
        timeBase.setRunning(true, 4, 4);
        summaryCounter.stepAtomic(); summaryCounter.stepAtomic(); // twice
        assertEquals(6, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(3, summaryCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(2, summaryCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));


        // Test full parcelling (from summaryCounter)
        final Parcel fullParcel = Parcel.obtain();
        summaryCounter.writeToParcel(fullParcel);
        fullParcel.setDataPosition(0);
        final BatteryStatsImpl.Counter fullParcelCounter
                = new BatteryStatsImpl.Counter(timeBase, fullParcel);

        // timeBase still on (i.e. unplugged)
        fullParcelCounter.stepAtomic(); // once
        assertEquals(7, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(4, fullParcelCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(3, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase off (i.e. plugged in)
        timeBase.setRunning(false, 5, 5);
        fullParcelCounter.stepAtomic(); fullParcelCounter.stepAtomic(); // twice
        assertEquals(7, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(4, fullParcelCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(3, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));

        // timeBase on (i.e. unplugged)
        timeBase.setRunning(true, 6, 6);
        fullParcelCounter.stepAtomic(); fullParcelCounter.stepAtomic(); // twice
        assertEquals(9, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_CHARGED));
        assertEquals(6, fullParcelCounter.getCountLocked(BatteryStats.STATS_CURRENT));
        assertEquals(2, fullParcelCounter.getCountLocked(BatteryStats.STATS_SINCE_UNPLUGGED));
    }
}
