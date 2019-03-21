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
import android.os.BatteryStats.HistoryItem;
import android.os.BatteryStats.Uid.Sensor;
import android.os.WorkSource;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.internal.os.BatteryStatsImpl.DualTimer;
import com.android.internal.os.BatteryStatsImpl.Uid;

import junit.framework.TestCase;

import java.util.HashMap;
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
 *      com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner
 */
public class BatteryStatsNoteTest extends TestCase {

    private static final int UID = 10500;
    private static final WorkSource WS = new WorkSource(UID);

    /**
     * Test BatteryStatsImpl.Uid.noteBluetoothScanResultLocked.
     */
    @SmallTest
    public void testNoteBluetoothScanResultLocked() throws Exception {
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(new MockClocks());
        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
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

    /**
     * Test BatteryStatsImpl.Uid.noteStartWakeLocked.
     */
    @SmallTest
    public void testNoteStartWakeLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int pid = 10;
        String name = "name";

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.getUidStatsLocked(UID)
                .noteStartWakeLocked(pid, name, WAKE_TYPE_PARTIAL, clocks.realtime);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 220;
        bi.getUidStatsLocked(UID).noteStopWakeLocked(pid, name, WAKE_TYPE_PARTIAL, clocks.realtime);

        BatteryStats.Timer aggregTimer = bi.getUidStats().get(UID)
                .getAggregatedPartialWakelockTimer();
        long actualTime = aggregTimer.getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        long bgTime = aggregTimer.getSubTimer().getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        assertEquals(220_000, actualTime);
        assertEquals(120_000, bgTime);
    }


    /**
     * Test BatteryStatsImpl.noteUidProcessStateLocked.
     */
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

        bi.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);

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
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);

        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP_SLEEPING,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);

        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_FOREGROUND,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        assertEquals(expectedRunTimeMs * 1000, actualRunTimeUs);

        actualRunTimeUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_BACKGROUND,
                elapsedTimeUs, STATS_SINCE_CHARGED);
        expectedRunTimeMs = stateRuntimeMap.get(ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND)
                + stateRuntimeMap.get(ActivityManager.PROCESS_STATE_BACKUP)
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

    /**
     * Test BatteryStatsImpl.updateTimeBasesLocked.
     */
    @SmallTest
    public void testUpdateTimeBasesLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        bi.updateTimeBasesLocked(false, Display.STATE_OFF, 0, 0);
        assertFalse(bi.getOnBatteryTimeBase().isRunning());
        bi.updateTimeBasesLocked(false, Display.STATE_DOZE, 10, 10);
        assertFalse(bi.getOnBatteryTimeBase().isRunning());
        bi.updateTimeBasesLocked(false, Display.STATE_ON, 20, 20);
        assertFalse(bi.getOnBatteryTimeBase().isRunning());

        bi.updateTimeBasesLocked(true, Display.STATE_ON, 30, 30);
        assertTrue(bi.getOnBatteryTimeBase().isRunning());
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        bi.updateTimeBasesLocked(true, Display.STATE_DOZE, 40, 40);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 40, 40);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
    }

    /**
     * Test BatteryStatsImpl.noteScreenStateLocked sets timebases and screen states correctly.
     */
    @SmallTest
    public void testNoteScreenStateLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        bi.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        bi.noteScreenStateLocked(Display.STATE_ON);
        bi.noteScreenStateLocked(Display.STATE_DOZE);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(bi.getScreenState(), Display.STATE_DOZE);
        bi.noteScreenStateLocked(Display.STATE_ON);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(bi.getScreenState(), Display.STATE_ON);
        bi.noteScreenStateLocked(Display.STATE_OFF);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(bi.getScreenState(), Display.STATE_OFF);
    }

    /*
     * Test BatteryStatsImpl.noteScreenStateLocked updates timers correctly.
     *
     * Unknown and doze should both be subset of off state
     *
     * Timeline 0----100----200----310----400------------1000
     * Unknown         -------
     * On                     -------
     * Off             -------       ----------------------
     * Doze                                ----------------
     */
    @SmallTest
    public void testNoteScreenStateTimersLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        clocks.realtime = clocks.uptime = 100;
        // Device startup, setOnBatteryLocked calls updateTimebases
        bi.updateTimeBasesLocked(true, Display.STATE_UNKNOWN, 100_000, 100_000);
        // Turn on display at 200us
        clocks.realtime = clocks.uptime = 200;
        bi.noteScreenStateLocked(Display.STATE_ON);
        assertEquals(150_000, bi.computeBatteryRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.computeBatteryScreenOffRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(50_000, bi.getScreenOnTime(250_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(250_000, STATS_SINCE_CHARGED));

        clocks.realtime = clocks.uptime = 310;
        bi.noteScreenStateLocked(Display.STATE_OFF);
        assertEquals(250_000, bi.computeBatteryRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(140_000, bi.computeBatteryScreenOffRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(350_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(350_000, STATS_SINCE_CHARGED));

        clocks.realtime = clocks.uptime = 400;
        bi.noteScreenStateLocked(Display.STATE_DOZE);
        assertEquals(400_000, bi.computeBatteryRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(290_000, bi.computeBatteryScreenOffRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(500_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.getScreenDozeTime(500_000, STATS_SINCE_CHARGED));

        clocks.realtime = clocks.uptime = 1000;
        bi.noteScreenStateLocked(Display.STATE_OFF);
        assertEquals(1400_000, bi.computeBatteryRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(1290_000, bi.computeBatteryScreenOffRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(600_000, bi.getScreenDozeTime(1500_000, STATS_SINCE_CHARGED));
    }

    @SmallTest
    public void testAlarmStartAndFinishLocked() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        clocks.realtime = clocks.uptime = 100;
        bi.noteAlarmStartLocked("foo", null, UID);
        clocks.realtime = clocks.uptime = 5000;
        bi.noteAlarmFinishLocked("foo", null, UID);

        HistoryItem item = new HistoryItem();
        assertTrue(bi.startIteratingHistoryLocked());

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_START, item.eventCode);
        assertEquals("foo", item.eventTag.string);
        assertEquals(UID, item.eventTag.uid);

        // TODO(narayan): Figure out why this event is written to the history buffer. See
        // test below where it is being interspersed between multiple START events too.
        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_NONE, item.eventCode);

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_FINISH, item.eventCode);
        assertTrue(item.isDeltaData());
        assertEquals("foo", item.eventTag.string);
        assertEquals(UID, item.eventTag.uid);

        assertFalse(bi.getNextHistoryLocked(item));
    }

    @SmallTest
    public void testAlarmStartAndFinishLocked_workSource() throws Exception {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        WorkSource ws = new WorkSource();
        ws.add(100);
        ws.createWorkChain().addNode(500, "tag");
        bi.noteAlarmStartLocked("foo", ws, UID);
        clocks.realtime = clocks.uptime = 5000;
        bi.noteAlarmFinishLocked("foo", ws, UID);

        HistoryItem item = new HistoryItem();
        assertTrue(bi.startIteratingHistoryLocked());

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_START, item.eventCode);
        assertEquals("foo", item.eventTag.string);
        assertEquals(100, item.eventTag.uid);

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_NONE, item.eventCode);

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_START, item.eventCode);
        assertEquals("foo", item.eventTag.string);
        assertEquals(500, item.eventTag.uid);

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_FINISH, item.eventCode);
        assertEquals("foo", item.eventTag.string);
        assertEquals(100, item.eventTag.uid);

        assertTrue(bi.getNextHistoryLocked(item));
        assertEquals(HistoryItem.EVENT_ALARM_FINISH, item.eventCode);
        assertEquals("foo", item.eventTag.string);
        assertEquals(500, item.eventTag.uid);
    }

    @SmallTest
    public void testNoteWakupAlarmLocked() {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();
        bi.mForceOnBattery = true;

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        bi.noteWakupAlarmLocked("com.foo.bar", UID, null, "tag");

        Uid.Pkg pkg = bi.getPackageStatsLocked(UID, "com.foo.bar");
        assertEquals(1, pkg.getWakeupAlarmStats().get("tag").getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(1, pkg.getWakeupAlarmStats().size());
    }

    @SmallTest
    public void testNoteWakupAlarmLocked_workSource_uid() {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();
        bi.mForceOnBattery = true;

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        WorkSource ws = new WorkSource();
        ws.add(100);

        // When a WorkSource is present, "UID" should not be used - only the uids present in the
        // WorkSource should be reported.
        bi.noteWakupAlarmLocked("com.foo.bar", UID, ws, "tag");
        Uid.Pkg pkg = bi.getPackageStatsLocked(UID, "com.foo.bar");
        assertEquals(0, pkg.getWakeupAlarmStats().size());
        pkg = bi.getPackageStatsLocked(100, "com.foo.bar");
        assertEquals(1, pkg.getWakeupAlarmStats().size());

        // If the WorkSource contains a "name", it should be interpreted as a package name and
        // the packageName supplied as an argument must be ignored.
        ws = new WorkSource();
        ws.add(100, "com.foo.baz_alternate");
        bi.noteWakupAlarmLocked("com.foo.baz", UID, ws, "tag");
        pkg = bi.getPackageStatsLocked(100, "com.foo.baz");
        assertEquals(0, pkg.getWakeupAlarmStats().size());
        pkg = bi.getPackageStatsLocked(100, "com.foo.baz_alternate");
        assertEquals(1, pkg.getWakeupAlarmStats().size());
    }

    @SmallTest
    public void testNoteWakupAlarmLocked_workSource_workChain() {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();
        bi.mForceOnBattery = true;

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        WorkSource ws = new WorkSource();
        ws.createWorkChain().addNode(100, "com.foo.baz_alternate");
        bi.noteWakupAlarmLocked("com.foo.bar", UID, ws, "tag");

        // For WorkChains, again we must only attribute to the uids present in the WorkSource
        // (and not to "UID"). However, unlike the older "tags" we do not change the packagename
        // supplied as an argument, given that we're logging the entire attribution chain.
        Uid.Pkg pkg = bi.getPackageStatsLocked(UID, "com.foo.bar");
        assertEquals(0, pkg.getWakeupAlarmStats().size());
        pkg = bi.getPackageStatsLocked(100, "com.foo.bar");
        assertEquals(1, pkg.getWakeupAlarmStats().size());
        pkg = bi.getPackageStatsLocked(100, "com.foo.baz_alternate");
        assertEquals(0, pkg.getWakeupAlarmStats().size());
    }

    @SmallTest
    public void testNoteGpsChanged() {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();
        bi.mForceOnBattery = true;

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        WorkSource ws = new WorkSource();
        ws.add(UID);

        bi.noteGpsChangedLocked(new WorkSource(), ws);
        DualTimer t = bi.getUidStatsLocked(UID).getSensorTimerLocked(Sensor.GPS, false);
        assertNotNull(t);
        assertTrue(t.isRunningLocked());

        bi.noteGpsChangedLocked(ws, new WorkSource());
        t = bi.getUidStatsLocked(UID).getSensorTimerLocked(Sensor.GPS, false);
        assertFalse(t.isRunningLocked());
    }

    @SmallTest
    public void testNoteGpsChanged_workSource() {
        final MockClocks clocks = new MockClocks(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();
        bi.mForceOnBattery = true;

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);

        WorkSource ws = new WorkSource();
        ws.createWorkChain().addNode(UID, "com.foo");

        bi.noteGpsChangedLocked(new WorkSource(), ws);
        DualTimer t = bi.getUidStatsLocked(UID).getSensorTimerLocked(Sensor.GPS, false);
        assertNotNull(t);
        assertTrue(t.isRunningLocked());

        bi.noteGpsChangedLocked(ws, new WorkSource());
        t = bi.getUidStatsLocked(UID).getSensorTimerLocked(Sensor.GPS, false);
        assertFalse(t.isRunningLocked());
    }
}
