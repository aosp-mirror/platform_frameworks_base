/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.Binder;
import android.os.Process;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import junit.framework.TestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Test cases for android.os.BatteryStats, system server Binder call stats.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class BatteryStatsBinderCallStatsTest extends TestCase {

    private static final int TRANSACTION_CODE1 = 100;
    private static final int TRANSACTION_CODE2 = 101;

    /**
     * Test BatteryStatsImpl.Uid.noteBinderCallStats.
     */
    @Test
    public void testNoteBinderCallStats() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int callingUid = Process.FIRST_APPLICATION_UID + 1;
        int workSourceUid = Process.FIRST_APPLICATION_UID + 1;

        Collection<BinderCallsStats.CallStat> callStats = new ArrayList<>();
        BinderCallsStats.CallStat stat1 = new BinderCallsStats.CallStat(callingUid,
                MockBinder.class, TRANSACTION_CODE1, true /*screenInteractive */);
        stat1.incrementalCallCount = 21;
        stat1.recordedCallCount = 5;
        stat1.cpuTimeMicros = 1000;
        callStats.add(stat1);

        bi.noteBinderCallStats(workSourceUid, 42, callStats);

        callStats.clear();
        BinderCallsStats.CallStat stat2 = new BinderCallsStats.CallStat(callingUid,
                MockBinder.class, TRANSACTION_CODE1, true /*screenInteractive */);
        stat2.incrementalCallCount = 9;
        stat2.recordedCallCount = 8;
        stat2.cpuTimeMicros = 500;
        callStats.add(stat2);

        bi.noteBinderCallStats(workSourceUid, 8, callStats);

        BatteryStatsImpl.Uid uid = bi.getUidStatsLocked(workSourceUid);
        assertEquals(42 + 8, uid.getBinderCallCount());

        BinderTransactionNameResolver resolver = new BinderTransactionNameResolver();
        ArraySet<BatteryStatsImpl.BinderCallStats> stats = uid.getBinderCallStats();
        assertEquals(1, stats.size());
        BatteryStatsImpl.BinderCallStats value = stats.valueAt(0);
        value.ensureMethodName(resolver);
        assertEquals("testMethod", value.getMethodName());
        assertEquals(21 + 9, value.callCount);
        assertEquals(8, value.recordedCallCount);
        assertEquals(500, value.recordedCpuTimeMicros);
    }


    @Test
    public void testProportionalSystemServiceUsage_noStatsForSomeMethods() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int callingUid = Process.FIRST_APPLICATION_UID + 1;
        int workSourceUid1 = Process.FIRST_APPLICATION_UID + 1;
        int workSourceUid2 = Process.FIRST_APPLICATION_UID + 2;
        int workSourceUid3 = Process.FIRST_APPLICATION_UID + 3;

        Collection<BinderCallsStats.CallStat> callStats = new ArrayList<>();
        BinderCallsStats.CallStat stat1a = new BinderCallsStats.CallStat(callingUid,
                MockBinder.class, TRANSACTION_CODE1, true /*screenInteractive */);
        stat1a.incrementalCallCount = 10;
        stat1a.recordedCallCount = 5;
        stat1a.cpuTimeMicros = 1000;
        callStats.add(stat1a);

        BinderCallsStats.CallStat stat1b = new BinderCallsStats.CallStat(callingUid,
                MockBinder.class, TRANSACTION_CODE2, true /*screenInteractive */);
        stat1b.incrementalCallCount = 30;
        stat1b.recordedCallCount = 15;
        stat1b.cpuTimeMicros = 1500;
        callStats.add(stat1b);

        bi.noteBinderCallStats(workSourceUid1, 65, callStats);

        // No recorded stats for some methods. Must use the global average.
        callStats.clear();
        BinderCallsStats.CallStat stat2 = new BinderCallsStats.CallStat(callingUid,
                MockBinder.class, TRANSACTION_CODE1, true /*screenInteractive */);
        stat2.incrementalCallCount = 10;
        callStats.add(stat2);

        bi.noteBinderCallStats(workSourceUid2, 40, callStats);

        // No stats for any calls. Must use the global average
        callStats.clear();
        bi.noteBinderCallStats(workSourceUid3, 50, callStats);

        bi.updateSystemServiceCallStats();

        double prop1 = bi.getUidStatsLocked(workSourceUid1).getProportionalSystemServiceUsage();
        double prop2 = bi.getUidStatsLocked(workSourceUid2).getProportionalSystemServiceUsage();
        double prop3 = bi.getUidStatsLocked(workSourceUid3).getProportionalSystemServiceUsage();

        assertEquals(0.419, prop1, 0.01);
        assertEquals(0.258, prop2, 0.01);
        assertEquals(0.323, prop3, 0.01);
        assertEquals(1.000, prop1 + prop2 + prop3, 0.01);
    }

    private static class MockBinder extends Binder {
        public static String getDefaultTransactionName(int txCode) {
            return txCode == TRANSACTION_CODE1 ? "testMethod" : "unknown";
        }
    }
}
