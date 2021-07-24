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
import android.util.ArrayMap;
import android.view.Display;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * Test BatteryStatsImpl onBatteryBackgroundTimeBase TimeBase.
 */
public class BatteryStatsBackgroundStatsTest extends TestCase {

    private static final int UID = 10500;

    /** Test that BatteryStatsImpl.Uid.mOnBatteryBackgroundTimeBase works correctly. */
    @SmallTest
    public void testBgTimeBase() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long cur = 0; // realtime in us

        BatteryStatsImpl.TimeBase bgtb = bi.getOnBatteryBackgroundTimeBase(UID);

        // Off-battery, non-existent
        clocks.realtime = clocks.uptime = 10;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(false, Display.STATE_ON, cur, cur); // off battery
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
        bi.updateTimeBasesLocked(true, Display.STATE_ON, cur, cur); // on battery
        // still in ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        assertTrue(bgtb.isRunning());
        assertEquals(0, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));

        // On-battery, background - but change screen state
        clocks.realtime = clocks.uptime = 409;
        cur = clocks.realtime * 1000;
        bi.updateTimeBasesLocked(true, Display.STATE_OFF, cur, cur); // on battery (again)
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
        bi.updateTimeBasesLocked(false, Display.STATE_ON, cur, cur); // off battery
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

    /** Test that BatteryStatsImpl.Uid.mOnBatteryScreenOffBackgroundTimeBase works correctly. */
    @SmallTest
    public void testScreenOffBgTimeBase() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long cur = 0; // realtime in us

        BatteryStatsImpl.TimeBase bgtb = bi.getOnBatteryScreenOffBackgroundTimeBase(UID);

        // battery=off, screen=off, background=off
        cur = (clocks.realtime = clocks.uptime = 100) * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, cur, cur);
        assertFalse(bgtb.isRunning());

        // battery=on, screen=off, background=off
        cur = (clocks.realtime = clocks.uptime = 200) * 1000;
        bi.updateTimeBasesLocked(true, Display.STATE_ON, cur, cur);
        assertFalse(bgtb.isRunning());

        // battery=on, screen=on, background=off
        cur = (clocks.realtime = clocks.uptime = 300) * 1000;
        bi.updateTimeBasesLocked(true, Display.STATE_OFF, cur, cur);
        assertFalse(bgtb.isRunning());

        // battery=on, screen=on, background=on
        // Only during this period should the timebase progress
        cur = (clocks.realtime = clocks.uptime = 400) * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        assertTrue(bgtb.isRunning());

        // battery=on, screen=off, background=on
        cur = (clocks.realtime = clocks.uptime = 550) * 1000;
        bi.updateTimeBasesLocked(true, Display.STATE_ON, cur, cur);
        assertFalse(bgtb.isRunning());

        // battery=off, screen=off, background=on
        cur = (clocks.realtime = clocks.uptime = 660) * 1000;
        bi.updateTimeBasesLocked(false, Display.STATE_ON, cur, cur);
        assertFalse(bgtb.isRunning());

        // battery=off, screen=off, background=off
        cur = (clocks.realtime = clocks.uptime = 770) * 1000;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        assertFalse(bgtb.isRunning());

        assertEquals(150_000, bgtb.computeRealtime(cur, STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testWifiScan() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr); // on battery
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
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr); // off battery

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
        assertEquals(0, bgCount);
        assertEquals((305 - 202) * 1000, actualTime);
        assertEquals((305 - 254) * 1000, bgTime);
    }

    @SmallTest
    public void testAppBluetoothScan() throws Exception {
        doTestAppBluetoothScanInternal(new WorkSource(UID));
    }

    @SmallTest
    public void testAppBluetoothScan_workChain() throws Exception {
        WorkSource ws = new WorkSource();
        ws.createWorkChain().addNode(UID, "foo");
        doTestAppBluetoothScanInternal(ws);
    }

    private void doTestAppBluetoothScanInternal(WorkSource ws) throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr); // on battery

        // App in foreground
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start timer (optimized)
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteBluetoothScanStartedFromSourceLocked(ws, false);

        // Move to background
        curr = 1000 * (clocks.realtime = clocks.uptime = 254);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // Off battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr); // off battery

        // Start timer (unoptimized)
        curr = 1000 * (clocks.realtime = clocks.uptime = 1000);
        bi.noteBluetoothScanStartedFromSourceLocked(ws, true);

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 2001);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr); // on battery

        // Move to foreground
        curr = 1000 * (clocks.realtime = clocks.uptime = 3004);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        // Stop timer (optimized)
        curr = 1000 * (clocks.realtime = clocks.uptime = 3409);
        bi.noteBluetoothScanStoppedFromSourceLocked(ws, false);

        // Stop timer (unoptimized)
        curr = 1000 * (clocks.realtime = clocks.uptime = 4008);
        bi.noteBluetoothScanStoppedFromSourceLocked(ws, true);

        // Test
        curr = 1000 * (clocks.realtime = clocks.uptime = 5000);
        BatteryStats.Timer timer = bi.getUidStats().get(UID).getBluetoothScanTimer();
        BatteryStats.Timer bgTimer = bi.getUidStats().get(UID).getBluetoothScanBackgroundTimer();
        BatteryStats.Timer badTimer = bi.getUidStats().get(UID).getBluetoothUnoptimizedScanTimer();
        BatteryStats.Timer badBgTimer = bi.getUidStats().get(UID)
                .getBluetoothUnoptimizedScanBackgroundTimer();

        long time = timer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        int count = timer.getCountLocked(STATS_SINCE_CHARGED);
        int bgCount = bgTimer.getCountLocked(STATS_SINCE_CHARGED);
        long actualTime = timer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        long bgTime = bgTimer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        long badTime = badTimer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        long badBgTime = badBgTimer.getTotalDurationMsLocked(clocks.realtime) * 1000;
        assertEquals((305 - 202 + 4008 - 2001) * 1000, time);
        assertEquals(1, count); // second scan starts off-battery
        assertEquals(0, bgCount); // first scan starts in fg, second starts off-battery
        assertEquals((305 - 202 + 4008 - 2001) * 1000, actualTime);
        assertEquals((305 - 254 + 3004 - 2001) * 1000, bgTime);
        assertEquals((4008 - 2001) * 1000, badTime);
        assertEquals((3004 - 2001) * 1000, badBgTime);
    }

    @SmallTest
    public void testJob() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        final String jobName = "job_name";
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr); // on battery
        // App in foreground
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 151);
        bi.noteJobStartLocked(jobName, UID);

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 161);
        bi.noteJobFinishLocked(jobName, UID, 0);

        // Move to background
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 254);
        bi.noteJobStartLocked(jobName, UID);

        // Off battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr); // off battery

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 409);
        bi.noteJobFinishLocked(jobName, UID, 0);

        // Test
        curr = 1000 * (clocks.realtime = clocks.uptime = 657);
        final ArrayMap<String, ? extends BatteryStats.Timer> jobs =
                bi.getUidStats().get(UID).getJobStats();
        assertEquals(1, jobs.size());
        BatteryStats.Timer timer = jobs.valueAt(0);
        BatteryStats.Timer bgTimer = timer.getSubTimer();
        long time = timer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        int count = timer.getCountLocked(STATS_SINCE_CHARGED);
        int bgCount = bgTimer.getCountLocked(STATS_SINCE_CHARGED);
        long bgTime = bgTimer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        assertEquals((161 - 151 + 305 - 254) * 1000, time);
        assertEquals(2, count);
        assertEquals(1, bgCount);
        assertEquals((305 - 254) * 1000, bgTime);

        // Test that a second job is separate.
        curr = 1000 * (clocks.realtime = clocks.uptime = 3000);
        final String jobName2 = "second_job";
        bi.noteJobStartLocked(jobName2, UID);
        assertEquals(2, bi.getUidStats().get(UID).getJobStats().size());
        bi.noteJobFinishLocked(jobName2, UID, 0);
    }

    @SmallTest
    public void testSyncs() throws Exception {
        final MockClock clocks = new MockClock();
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        final String syncName = "sync_name";
        long curr = 0; // realtime in us

        // On battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 100);
        bi.updateTimeBasesLocked(true, Display.STATE_ON, curr, curr); // on battery
        // App in foreground
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 151);
        bi.noteSyncStartLocked(syncName, UID);

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 161);
        bi.noteSyncFinishLocked(syncName, UID);

        // Move to background
        curr = 1000 * (clocks.realtime = clocks.uptime = 202);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // Start timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 254);
        bi.noteSyncStartLocked(syncName, UID);

        // Off battery
        curr = 1000 * (clocks.realtime = clocks.uptime = 305);
        bi.updateTimeBasesLocked(false, Display.STATE_ON, curr, curr); // off battery

        // Stop timer
        curr = 1000 * (clocks.realtime = clocks.uptime = 409);
        bi.noteSyncFinishLocked(syncName, UID);

        // Test
        curr = 1000 * (clocks.realtime = clocks.uptime = 657);
        final ArrayMap<String, ? extends BatteryStats.Timer> syncs =
                bi.getUidStats().get(UID).getSyncStats();
        assertEquals(1, syncs.size());
        BatteryStats.Timer timer = syncs.valueAt(0);
        BatteryStats.Timer bgTimer = timer.getSubTimer();
        long time = timer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        int count = timer.getCountLocked(STATS_SINCE_CHARGED);
        int bgCount = bgTimer.getCountLocked(STATS_SINCE_CHARGED);
        long bgTime = bgTimer.getTotalTimeLocked(curr, STATS_SINCE_CHARGED);
        assertEquals((161 - 151 + 305 - 254) * 1000, time);
        assertEquals(2, count);
        assertEquals(1, bgCount);
        assertEquals((305 - 254) * 1000, bgTime);

        // Test that a second sync is separate.
        curr = 1000 * (clocks.realtime = clocks.uptime = 3000);
        final String syncName2 = "second_sync";
        bi.noteSyncStartLocked(syncName2, UID);
        assertEquals(2, bi.getUidStats().get(UID).getSyncStats().size());
        bi.noteSyncFinishLocked(syncName2, UID);
    }
}
