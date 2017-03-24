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

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import android.app.ActivityManager;
import android.os.BatteryStats;
import android.os.WorkSource;
import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl onBatteryBackgroundTimeBase TimeBase.
 */
public class BatteryStatsBackgroundStatsTest extends TestCase {

    private static final int UID = 10500;

    /** Test that BatteryStatsImpl.Uid.mOnBatteryBackgroundTimeBase works correctly. */
    @SmallTest
    public void testBgTimeBase() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long cur = 0; // realtime in us

        BatteryStatsImpl.TimeBase bgtb = bi.getOnBatteryBackgroundTimeBase(UID);

        // Off-battery, non-existent
        clocks.realtime = clocks.uptime = 10;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(false, false, cur, cur); // off battery
        assertFalse(bgtb.isRunning());
        assertEquals(0, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // Off-battery, foreground
        clocks.realtime = clocks.uptime = 100;
        cur = clocks.realtime * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        assertFalse(bgtb.isRunning());
        assertEquals(0, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // Off-battery, background
        clocks.realtime = clocks.uptime = 201;
        cur = clocks.realtime * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        assertFalse(bgtb.isRunning());
        assertEquals(0, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // On-battery, background
        clocks.realtime = clocks.uptime = 303;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(true, false, cur, cur); // on battery
        // still in ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        assertTrue(bgtb.isRunning());
        assertEquals(0, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // On-battery, background - but change screen state
        clocks.realtime = clocks.uptime = 409;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(true, true, cur, cur); // on battery (again)
        assertTrue(bgtb.isRunning());
        assertEquals(106_000, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // On-battery, background - but a different background state
        clocks.realtime = clocks.uptime = 417;
        cur = clocks.realtime * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_RECEIVER); // background too
        assertTrue(bgtb.isRunning());
        assertEquals(114_000, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // Off-battery, foreground
        clocks.realtime = clocks.uptime = 530;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(false, false, cur, cur); // off battery
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        assertFalse(bgtb.isRunning());
        assertEquals(227_000, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // Off-battery, non-existent
        clocks.realtime = clocks.uptime = 690;
        cur = clocks.realtime * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_NONEXISTENT);
        assertFalse(bgtb.isRunning());
        assertEquals(227_000, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testWifiScan() throws Exception {
        final MockClocks clocks = new MockClocks();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, false, curr, curr); // on battery
        // App in foreground
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteWifiScanStartedLocked(UID);

        // Move to background
        curr = 1000 * (clocks.realtime = clocks.uptime = 254);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // Off battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, false, curr, curr); // off battery

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 409);
        bi.noteWifiScanStoppedLocked(UID);

        // Test
        curr = 1000 * (clocks.realtime = clocks.uptime = 657);
        long time = bi.getUidStats().get(UID).getWifiScanTime(curr, STATS_SINCE_CHARGED);
        int count = bi.getUidStats().get(UID).getWifiScanCount(STATS_SINCE_CHARGED);
        int bgCount = bi.getUidStats().get(UID).getWifiScanBackgroundCount(STATS_SINCE_CHARGED);
        long actualTime = bi.getUidStats().get(UID).getWifiScanActualTime(curr);
        long bgTime = bi.getUidStats().get(UID).getWifiScanBackgroundTime(curr);
        assertEquals((305 - 202) * 1000, time);
        assertEquals(1, count);
        assertEquals(1, bgCount);
        assertEquals((305 - 202) * 1000, actualTime);
        assertEquals((305 - 254) * 1000, bgTime);
    }

    @SmallTest
    public void testAppBluetoothScan() throws Exception {
        final MockClocks clocks = new MockClocks();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        WorkSource ws = new WorkSource(UID); // needed for bluetooth
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, false, curr, curr); // on battery

        // App in foreground
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteBluetoothScanStartedFromSourceLocked(ws);

        // Move to background
        curr = 1000 * (clocks.realtime = clocks.uptime = 254);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // Off battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, false, curr, curr); // off battery

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 409);
        bi.noteBluetoothScanStoppedFromSourceLocked(ws);

        // Test
        curr = 1000 * (clocks.realtime = clocks.uptime = 657);
        BatteryStats.Timer timer = bi.getUidStats().get(UID).getBluetoothScanTimer();
        BatteryStats.Timer bgTimer = bi.getUidStats().get(UID).getBluetoothScanBackgroundTimer();

        long time = timer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        int count = timer.getCountLocked(STATS_SINCE_CHARGED);
        int bgCount = bgTimer.getCountLocked(STATS_SINCE_CHARGED);
        long actualTime = timer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        long bgTime = bgTimer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        assertEquals((305 - 202) * 1000, time);
        assertEquals(1, count);
        assertEquals(1, bgCount);
        assertEquals((305 - 202) * 1000, actualTime);
        assertEquals((305 - 254) * 1000, bgTime);
    }
}
