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

import static android.os.BatteryStats.STATS_SINCE_CHARGED;
import static android.os.BatteryStats.WAKE_TYPE_PARTIAL;

import android.app.ActivityManager;
import android.os.BatteryStats;
import android.os.WorkSource;
import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test various BatteryStatsImpl noteStart methods.
 *
 * Build/Install/Run: bit FrameworksCoreTests:com.android.internal.os.BatteryStatsNoteTest
 *
 * Alternatively,
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 *      ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.BatteryStatsNoteTest -w \
 *      com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 */
public class BatteryStatsNoteTest extends TestCase{
    private static final int UID = 10500;
    private static final WorkSource WS = new WorkSource(UID);

    /** Test BatteryStatsImpl.Uid.noteBluetoothScanResultLocked. */
    @SmallTest
    public void testNoteBluetoothScanResultLocked() throws Exception {
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(new MockClocks());
        bi.updateTimeBasesLocked(true, true, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        bi.noteBluetoothScanResultsFromSourceLocked(WS, 1);
        bi.noteBluetoothScanResultsFromSourceLocked(WS, 100);
        assertEquals(101,
                bi.getUidStats().get(UID).getBluetoothScanResultCounter()
                        .getCountLocked(STATS_SINCE_CHARGED));
        BatteryStats.Counter bgCntr = bi.getUidStats().get(UID).getBluetoothScanResultBgCounter();
        if (bgCntr != null) {
            assertEquals(0, bgCntr.getCountLocked(STATS_SINCE_CHARGED));
        }

        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        bi.noteBluetoothScanResultsFromSourceLocked(WS, 17);
        assertEquals(101 + 17,
                bi.getUidStats().get(UID).getBluetoothScanResultCounter()
                        .getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(17,
                bi.getUidStats().get(UID).getBluetoothScanResultBgCounter()
                        .getCountLocked(STATS_SINCE_CHARGED));
    }

    /** Test BatteryStatsImpl.Uid.noteStartWakeLocked. */
    @SmallTest
    public void testNoteStartWakeLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int pid = 10;
        String name = "name";

        bi.updateTimeBasesLocked(true, true, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.getUidStatsLocked(UID).noteStartWakeLocked(pid, name, WAKE_TYPE_PARTIAL, clocks.realtime);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 220;
        bi.getUidStatsLocked(UID).noteStopWakeLocked(pid, name, WAKE_TYPE_PARTIAL, clocks.realtime);

        BatteryStats.Timer aggregTimer = bi.getUidStats().get(UID).getAggregatedPartialWakelockTimer();
        long actualTime = aggregTimer.getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        long bgTime = aggregTimer.getSubTimer().getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        assertEquals(220_000, actualTime);
        assertEquals(120_000, bgTime);
    }


    /** Test BatteryStatsImpl.noteUidProcessStateLocked. */
    @SmallTest
    public void testNoteUidProcessStateLocked() throws Exception {
        final MockClocks clocks = new MockClocks();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        // map of ActivityManager process states and how long to simulate run time in each state
        Map<Integer, Integer> stateRuntimeMap = new HashMap<Integer, Integer>();
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_TOP, 1111);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE, 1234);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 2468);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_TOP_SLEEPING, 7531);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 4455);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 1337);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_BACKUP, 90210);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_HEAVY_WEIGHT, 911);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_SERVICE, 404);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_RECEIVER, 31459);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_HOME, 1123);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_LAST_ACTIVITY, 5813);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY, 867);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT, 5309);
        stateRuntimeMap.put(ActivityManager.PROCESS_STATE_CACHED_EMPTY, 42);

        bi.updateTimeBasesLocked(true, false, 0, 0);

        for (Map.Entry<Integer, Integer> entry : stateRuntimeMap.entrySet()) {
            bi.noteUidProcessStateLocked(UID, entry.getKey());
            clocks.realtime += entry.getValue();
            clocks.uptime = clocks.realtime;
        }

        long actualRunTimeUs;
        long expectedRunTimeMs;
        long elapsedTimeUs = clocks.realtime * 1000;
        BatteryStats.Uid uid = bi.getUidStats().get(UID);

        // compare runtime of process states to the Uid process states they map to
        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP, elapsedTimeUs,
                STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_TOP);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);


        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(
                ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);


        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP_SLEEPING,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);


        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_FOREGROUND,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);


        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_BACKGROUND,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_BACKUP)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_HEAVY_WEIGHT)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_SERVICE)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_RECEIVER);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);


        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_CACHED,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_HOME)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_LAST_ACTIVITY)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);

        // Special check for foreground service timer
        actualRunTimeUs = uid.getForegroundServiceTimer().getTotalTimeLocked(elapsedTimeUs,
                STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);
    }
}
