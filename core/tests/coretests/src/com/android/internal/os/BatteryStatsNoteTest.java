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

import static android.os.BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;
import static android.os.BatteryStats.RADIO_ACCESS_TECHNOLOGY_COUNT;
import static android.os.BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR;
import static android.os.BatteryStats.STATS_SINCE_CHARGED;
import static android.os.BatteryStats.WAKE_TYPE_PARTIAL;

import static com.android.internal.os.BatteryStatsImpl.ExternalStatsSync.UPDATE_CPU;
import static com.android.internal.os.BatteryStatsImpl.ExternalStatsSync.UPDATE_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.app.usage.NetworkStatsManager;
import android.hardware.radio.V1_5.AccessNetwork;
import android.os.BatteryStats;
import android.os.BatteryStats.HistoryItem;
import android.os.BatteryStats.Uid.Sensor;
import android.os.Process;
import android.os.UserHandle;
import android.os.WorkSource;
import android.telephony.AccessNetworkConstants;
import android.telephony.ActivityStatsTechSpecificInfo;
import android.telephony.Annotation;
import android.telephony.CellSignalStrength;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.internal.os.BatteryStatsImpl.DualTimer;
import com.android.internal.os.BatteryStatsImpl.Uid;
import com.android.internal.power.MeasuredEnergyStats;

import junit.framework.TestCase;

import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

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
    private static final int ISOLATED_APP_ID = Process.FIRST_ISOLATED_UID + 23;
    private static final int ISOLATED_UID = UserHandle.getUid(0, ISOLATED_APP_ID);
    private static final WorkSource WS = new WorkSource(UID);

    enum ModemState {
        SLEEP, IDLE, RECEIVING, TRANSMITTING
    }

    @Mock
    NetworkStatsManager mNetworkStatsManager;

    /**
     * Test BatteryStatsImpl.Uid.noteBluetoothScanResultLocked.
     */
    @SmallTest
    public void testNoteBluetoothScanResultLocked() throws Exception {
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(new MockClock());
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
     * Test BatteryStatsImpl.Uid.noteStartWakeLocked for an isolated uid.
     */
    @SmallTest
    public void testNoteStartWakeLocked_isolatedUid() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int pid = 10;
        String name = "name";
        String historyName = "historyName";

        WorkSource.WorkChain isolatedWorkChain = new WorkSource.WorkChain();
        isolatedWorkChain.addNode(ISOLATED_UID, name);

        // Map ISOLATED_UID to UID.
        bi.addIsolatedUidLocked(ISOLATED_UID, UID);

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.noteStartWakeLocked(ISOLATED_UID, pid, isolatedWorkChain, name, historyName,
                WAKE_TYPE_PARTIAL, false);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 220;
        bi.noteStopWakeLocked(ISOLATED_UID, pid, isolatedWorkChain, name, historyName,
                WAKE_TYPE_PARTIAL);

        // ISOLATED_UID wakelock time should be attributed to UID.
        BatteryStats.Timer aggregTimer = bi.getUidStats().get(UID)
                .getAggregatedPartialWakelockTimer();
        long actualTime = aggregTimer.getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        long bgTime = aggregTimer.getSubTimer().getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        assertEquals(220_000, actualTime);
        assertEquals(120_000, bgTime);
    }

    /**
     * Test BatteryStatsImpl.Uid.noteStartWakeLocked for an isolated uid, with a race where the
     * isolated uid is removed from batterystats before the wakelock has been stopped.
     */
    @SmallTest
    public void testNoteStartWakeLocked_isolatedUidRace() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        int pid = 10;
        String name = "name";
        String historyName = "historyName";

        WorkSource.WorkChain isolatedWorkChain = new WorkSource.WorkChain();
        isolatedWorkChain.addNode(ISOLATED_UID, name);

        // Map ISOLATED_UID to UID.
        bi.addIsolatedUidLocked(ISOLATED_UID, UID);

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.noteStartWakeLocked(ISOLATED_UID, pid, isolatedWorkChain, name, historyName,
                WAKE_TYPE_PARTIAL, false);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 150;
        bi.maybeRemoveIsolatedUidLocked(ISOLATED_UID, clocks.realtime, clocks.uptime);

        clocks.realtime = clocks.uptime = 220;
        bi.noteStopWakeLocked(ISOLATED_UID, pid, isolatedWorkChain, name, historyName,
                WAKE_TYPE_PARTIAL);

        // ISOLATED_UID wakelock time should be attributed to UID.
        BatteryStats.Timer aggregTimer = bi.getUidStats().get(UID)
                .getAggregatedPartialWakelockTimer();
        long actualTime = aggregTimer.getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        long bgTime = aggregTimer.getSubTimer().getTotalTimeLocked(300_000, STATS_SINCE_CHARGED);
        assertEquals(220_000, actualTime);
        assertEquals(120_000, bgTime);
    }

    /**
     * Test BatteryStatsImpl.Uid.noteLongPartialWakelockStart for an isolated uid.
     */
    @SmallTest
    public void testNoteLongPartialWakelockStart_isolatedUid() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);


        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();

        int pid = 10;
        String name = "name";
        String historyName = "historyName";

        WorkSource.WorkChain isolatedWorkChain = new WorkSource.WorkChain();
        isolatedWorkChain.addNode(ISOLATED_UID, name);

        // Map ISOLATED_UID to UID.
        bi.addIsolatedUidLocked(ISOLATED_UID, UID);

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.noteLongPartialWakelockStart(name, historyName, ISOLATED_UID);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 220;
        bi.noteLongPartialWakelockFinish(name, historyName, ISOLATED_UID);

        final BatteryStatsHistoryIterator iterator =
                bi.createBatteryStatsHistoryIterator();

        BatteryStats.HistoryItem item = new BatteryStats.HistoryItem();

        while (iterator.next(item)) {
            if (item.eventCode == HistoryItem.EVENT_LONG_WAKE_LOCK_START) break;
        }
        assertThat(item.eventCode).isEqualTo(HistoryItem.EVENT_LONG_WAKE_LOCK_START);
        assertThat(item.eventTag).isNotNull();
        assertThat(item.eventTag.string).isEqualTo(historyName);
        assertThat(item.eventTag.uid).isEqualTo(UID);

        while (iterator.next(item)) {
            if (item.eventCode == HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH) break;
        }
        assertThat(item.eventCode).isEqualTo(HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH);
        assertThat(item.eventTag).isNotNull();
        assertThat(item.eventTag.string).isEqualTo(historyName);
        assertThat(item.eventTag.uid).isEqualTo(UID);
    }

    /**
     * Test BatteryStatsImpl.Uid.noteLongPartialWakelockStart for an isolated uid.
     */
    @SmallTest
    public void testNoteLongPartialWakelockStart_isolatedUidRace() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);


        bi.setRecordAllHistoryLocked(true);
        bi.forceRecordAllHistory();

        int pid = 10;
        String name = "name";
        String historyName = "historyName";

        WorkSource.WorkChain isolatedWorkChain = new WorkSource.WorkChain();
        isolatedWorkChain.addNode(ISOLATED_UID, name);

        // Map ISOLATED_UID to UID.
        bi.addIsolatedUidLocked(ISOLATED_UID, UID);

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_TOP);
        bi.noteLongPartialWakelockStart(name, historyName, ISOLATED_UID);

        clocks.realtime = clocks.uptime = 100;
        bi.noteUidProcessStateLocked(UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        clocks.realtime = clocks.uptime = 150;
        bi.maybeRemoveIsolatedUidLocked(ISOLATED_UID, clocks.realtime, clocks.uptime);

        clocks.realtime = clocks.uptime = 220;
        bi.noteLongPartialWakelockFinish(name, historyName, ISOLATED_UID);

        final BatteryStatsHistoryIterator iterator =
                bi.createBatteryStatsHistoryIterator();

        BatteryStats.HistoryItem item = new BatteryStats.HistoryItem();

        while (iterator.next(item)) {
            if (item.eventCode == HistoryItem.EVENT_LONG_WAKE_LOCK_START) break;
        }
        assertThat(item.eventCode).isEqualTo(HistoryItem.EVENT_LONG_WAKE_LOCK_START);
        assertThat(item.eventTag).isNotNull();
        assertThat(item.eventTag.string).isEqualTo(historyName);
        assertThat(item.eventTag.uid).isEqualTo(UID);

        while (iterator.next(item)) {
            if (item.eventCode == HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH) break;
        }
        assertThat(item.eventCode).isEqualTo(HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH);
        assertThat(item.eventTag).isNotNull();
        assertThat(item.eventTag.string).isEqualTo(historyName);
        assertThat(item.eventTag.uid).isEqualTo(UID);
    }

    /**
     * Test BatteryStatsImpl.noteUidProcessStateLocked.
     */
    @SmallTest
    public void testNoteUidProcessStateLocked() throws Exception {
        final MockClock clocks = new MockClock();
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});

        bi.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        bi.noteScreenStateLocked(0, Display.STATE_ON);

        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_OFF, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_DOZE_SUSPEND);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE_SUSPEND, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        // STATE_VR note should map to STATE_ON.
        bi.noteScreenStateLocked(0, Display.STATE_VR);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        // STATE_ON_SUSPEND note should map to STATE_ON.
        bi.noteScreenStateLocked(0, Display.STATE_ON_SUSPEND);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        // Transition from ON to ON state should not cause an External Sync
        assertEquals(0, bi.getAndClearExternalStatsSyncFlags());
    }

    /**
     * Test BatteryStatsImpl.noteScreenStateLocked sets timebases and screen states correctly for
     * multi display devices
     */
    @SmallTest
    public void testNoteScreenStateLocked_multiDisplay() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setDisplayCountLocked(2);
        bi.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});

        bi.updateTimeBasesLocked(true, Display.STATE_OFF, 0, 0);
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        bi.noteScreenStateLocked(1, Display.STATE_OFF);

        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_OFF, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_DOZE_SUSPEND);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE_SUSPEND, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        // STATE_VR note should map to STATE_ON.
        bi.noteScreenStateLocked(0, Display.STATE_VR);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        // STATE_ON_SUSPEND note should map to STATE_ON.
        bi.noteScreenStateLocked(0, Display.STATE_ON_SUSPEND);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        // Transition from ON to ON state should not cause an External Sync
        assertEquals(0, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(1, Display.STATE_DOZE);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        // Should remain STATE_ON since display0 is still on.
        assertEquals(Display.STATE_ON, bi.getScreenState());
        // Overall screen state did not change, so no need to sync CPU stats.
        assertEquals(UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_DOZE_SUSPEND);
        assertTrue(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_DOZE, bi.getScreenState());
        // Overall screen state did not change, so no need to sync CPU stats.
        assertEquals(UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_VR);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(UPDATE_CPU | UPDATE_DISPLAY, bi.getAndClearExternalStatsSyncFlags());

        bi.noteScreenStateLocked(0, Display.STATE_ON_SUSPEND);
        assertFalse(bi.getOnBatteryScreenOffTimeBase().isRunning());
        assertEquals(Display.STATE_ON, bi.getScreenState());
        assertEquals(0, bi.getAndClearExternalStatsSyncFlags());
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        clocks.realtime = clocks.uptime = 100;
        // Device startup, setOnBatteryLocked calls updateTimebases
        bi.updateTimeBasesLocked(true, Display.STATE_UNKNOWN, 100_000, 100_000);
        // Turn on display at 200us
        clocks.realtime = clocks.uptime = 200;
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertEquals(150_000, bi.computeBatteryRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.computeBatteryScreenOffRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(50_000, bi.getScreenOnTime(250_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(250_000, STATS_SINCE_CHARGED));
        assertEquals(50_000, bi.getDisplayScreenOnTime(0, 250_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(0, 250_000));

        clocks.realtime = clocks.uptime = 310;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(250_000, bi.computeBatteryRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(140_000, bi.computeBatteryScreenOffRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(350_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(350_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 350_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(0, 350_000));

        clocks.realtime = clocks.uptime = 400;
        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertEquals(400_000, bi.computeBatteryRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(290_000, bi.computeBatteryScreenOffRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(500_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.getScreenDozeTime(500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 500_000));
        assertEquals(100_000, bi.getDisplayScreenDozeTime(0, 500_000));

        clocks.realtime = clocks.uptime = 1000;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(1400_000, bi.computeBatteryRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(1290_000, bi.computeBatteryScreenOffRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(600_000, bi.getScreenDozeTime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 1500_000));
        assertEquals(600_000, bi.getDisplayScreenDozeTime(0, 1500_000));
    }

    /*
     * Test BatteryStatsImpl.noteScreenStateLocked updates timers correctly for multi display
     * devices.
     */
    @SmallTest
    public void testNoteScreenStateTimersLocked_multiDisplay() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.setDisplayCountLocked(2);

        clocks.realtime = clocks.uptime = 100;
        // Device startup, setOnBatteryLocked calls updateTimebases
        bi.updateTimeBasesLocked(true, Display.STATE_UNKNOWN, 100_000, 100_000);
        // Turn on display at 200us
        clocks.realtime = clocks.uptime = 200;
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        bi.noteScreenStateLocked(1, Display.STATE_OFF);
        assertEquals(150_000, bi.computeBatteryRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.computeBatteryScreenOffRealtime(250_000, STATS_SINCE_CHARGED));
        assertEquals(50_000, bi.getScreenOnTime(250_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(250_000, STATS_SINCE_CHARGED));
        assertEquals(50_000, bi.getDisplayScreenOnTime(0, 250_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(0, 250_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 250_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(1, 250_000));

        clocks.realtime = clocks.uptime = 310;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(250_000, bi.computeBatteryRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(140_000, bi.computeBatteryScreenOffRealtime(350_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(350_000, STATS_SINCE_CHARGED));
        assertEquals(0, bi.getScreenDozeTime(350_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 350_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(0, 350_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 350_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(1, 350_000));

        clocks.realtime = clocks.uptime = 400;
        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertEquals(400_000, bi.computeBatteryRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(290_000, bi.computeBatteryScreenOffRealtime(500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(500_000, STATS_SINCE_CHARGED));
        assertEquals(100_000, bi.getScreenDozeTime(500_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 500_000));
        assertEquals(100_000, bi.getDisplayScreenDozeTime(0, 500_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 500_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(1, 500_000));

        clocks.realtime = clocks.uptime = 1000;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(1000_000, bi.computeBatteryRealtime(1100_000, STATS_SINCE_CHARGED));
        assertEquals(890_000, bi.computeBatteryScreenOffRealtime(1100_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(1100_000, STATS_SINCE_CHARGED));
        assertEquals(600_000, bi.getScreenDozeTime(1100_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 1100_000));
        assertEquals(600_000, bi.getDisplayScreenDozeTime(0, 1100_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 1100_000));
        assertEquals(0, bi.getDisplayScreenDozeTime(1, 1100_000));

        clocks.realtime = clocks.uptime = 1200;
        // Change state of second display to doze
        bi.noteScreenStateLocked(1, Display.STATE_DOZE);
        assertEquals(1150_000, bi.computeBatteryRealtime(1250_000, STATS_SINCE_CHARGED));
        assertEquals(1040_000, bi.computeBatteryScreenOffRealtime(1250_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getScreenOnTime(1250_000, STATS_SINCE_CHARGED));
        assertEquals(650_000, bi.getScreenDozeTime(1250_000, STATS_SINCE_CHARGED));
        assertEquals(110_000, bi.getDisplayScreenOnTime(0, 1250_000));
        assertEquals(600_000, bi.getDisplayScreenDozeTime(0, 1250_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 1250_000));
        assertEquals(50_000, bi.getDisplayScreenDozeTime(1, 1250_000));

        clocks.realtime = clocks.uptime = 1310;
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertEquals(1250_000, bi.computeBatteryRealtime(1350_000, STATS_SINCE_CHARGED));
        assertEquals(1100_000, bi.computeBatteryScreenOffRealtime(1350_000, STATS_SINCE_CHARGED));
        assertEquals(150_000, bi.getScreenOnTime(1350_000, STATS_SINCE_CHARGED));
        assertEquals(710_000, bi.getScreenDozeTime(1350_000, STATS_SINCE_CHARGED));
        assertEquals(150_000, bi.getDisplayScreenOnTime(0, 1350_000));
        assertEquals(600_000, bi.getDisplayScreenDozeTime(0, 1350_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 1350_000));
        assertEquals(150_000, bi.getDisplayScreenDozeTime(1, 1350_000));

        clocks.realtime = clocks.uptime = 1400;
        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertEquals(1400_000, bi.computeBatteryRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(1200_000, bi.computeBatteryScreenOffRealtime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(200_000, bi.getScreenOnTime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(810_000, bi.getScreenDozeTime(1500_000, STATS_SINCE_CHARGED));
        assertEquals(200_000, bi.getDisplayScreenOnTime(0, 1500_000));
        assertEquals(700_000, bi.getDisplayScreenDozeTime(0, 1500_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 1500_000));
        assertEquals(300_000, bi.getDisplayScreenDozeTime(1, 1500_000));

        clocks.realtime = clocks.uptime = 2000;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(2000_000, bi.computeBatteryRealtime(2100_000, STATS_SINCE_CHARGED));
        assertEquals(1800_000, bi.computeBatteryScreenOffRealtime(2100_000, STATS_SINCE_CHARGED));
        assertEquals(200_000, bi.getScreenOnTime(2100_000, STATS_SINCE_CHARGED));
        assertEquals(1410_000, bi.getScreenDozeTime(2100_000, STATS_SINCE_CHARGED));
        assertEquals(200_000, bi.getDisplayScreenOnTime(0, 2100_000));
        assertEquals(1200_000, bi.getDisplayScreenDozeTime(0, 2100_000));
        assertEquals(0, bi.getDisplayScreenOnTime(1, 2100_000));
        assertEquals(900_000, bi.getDisplayScreenDozeTime(1, 2100_000));


        clocks.realtime = clocks.uptime = 2200;
        // Change state of second display to on
        bi.noteScreenStateLocked(1, Display.STATE_ON);
        assertEquals(2150_000, bi.computeBatteryRealtime(2250_000, STATS_SINCE_CHARGED));
        assertEquals(1900_000, bi.computeBatteryScreenOffRealtime(2250_000, STATS_SINCE_CHARGED));
        assertEquals(250_000, bi.getScreenOnTime(2250_000, STATS_SINCE_CHARGED));
        assertEquals(1510_000, bi.getScreenDozeTime(2250_000, STATS_SINCE_CHARGED));
        assertEquals(200_000, bi.getDisplayScreenOnTime(0, 2250_000));
        assertEquals(1200_000, bi.getDisplayScreenDozeTime(0, 2250_000));
        assertEquals(50_000, bi.getDisplayScreenOnTime(1, 2250_000));
        assertEquals(1000_000, bi.getDisplayScreenDozeTime(1, 2250_000));

        clocks.realtime = clocks.uptime = 2310;
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        assertEquals(2250_000, bi.computeBatteryRealtime(2350_000, STATS_SINCE_CHARGED));
        assertEquals(1900_000, bi.computeBatteryScreenOffRealtime(2350_000, STATS_SINCE_CHARGED));
        assertEquals(350_000, bi.getScreenOnTime(2350_000, STATS_SINCE_CHARGED));
        assertEquals(1510_000, bi.getScreenDozeTime(2350_000, STATS_SINCE_CHARGED));
        assertEquals(240_000, bi.getDisplayScreenOnTime(0, 2350_000));
        assertEquals(1200_000, bi.getDisplayScreenDozeTime(0, 2350_000));
        assertEquals(150_000, bi.getDisplayScreenOnTime(1, 2350_000));
        assertEquals(1000_000, bi.getDisplayScreenDozeTime(1, 2350_000));

        clocks.realtime = clocks.uptime = 2400;
        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        assertEquals(2400_000, bi.computeBatteryRealtime(2500_000, STATS_SINCE_CHARGED));
        assertEquals(1900_000, bi.computeBatteryScreenOffRealtime(2500_000, STATS_SINCE_CHARGED));
        assertEquals(500_000, bi.getScreenOnTime(2500_000, STATS_SINCE_CHARGED));
        assertEquals(1510_000, bi.getScreenDozeTime(2500_000, STATS_SINCE_CHARGED));
        assertEquals(290_000, bi.getDisplayScreenOnTime(0, 2500_000));
        assertEquals(1300_000, bi.getDisplayScreenDozeTime(0, 2500_000));
        assertEquals(300_000, bi.getDisplayScreenOnTime(1, 2500_000));
        assertEquals(1000_000, bi.getDisplayScreenDozeTime(1, 2500_000));

        clocks.realtime = clocks.uptime = 3000;
        bi.noteScreenStateLocked(0, Display.STATE_OFF);
        assertEquals(3000_000, bi.computeBatteryRealtime(3100_000, STATS_SINCE_CHARGED));
        assertEquals(1900_000, bi.computeBatteryScreenOffRealtime(3100_000, STATS_SINCE_CHARGED));
        assertEquals(1100_000, bi.getScreenOnTime(3100_000, STATS_SINCE_CHARGED));
        assertEquals(1510_000, bi.getScreenDozeTime(3100_000, STATS_SINCE_CHARGED));
        assertEquals(290_000, bi.getDisplayScreenOnTime(0, 3100_000));
        assertEquals(1800_000, bi.getDisplayScreenDozeTime(0, 3100_000));
        assertEquals(900_000, bi.getDisplayScreenOnTime(1, 3100_000));
        assertEquals(1000_000, bi.getDisplayScreenDozeTime(1, 3100_000));
    }


    /**
     * Test BatteryStatsImpl.noteScreenBrightnessLocked updates timers correctly.
     */
    @SmallTest
    public void testScreenBrightnessLocked_multiDisplay() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);

        final int numDisplay = 2;
        bi.setDisplayCountLocked(numDisplay);


        final long[] overallExpected = new long[NUM_SCREEN_BRIGHTNESS_BINS];
        final long[][] perDisplayExpected = new long[numDisplay][NUM_SCREEN_BRIGHTNESS_BINS];
        class Bookkeeper {
            public long currentTimeMs = 100;
            public int overallActiveBin = -1;
            public int[] perDisplayActiveBin = new int[numDisplay];
        }
        final Bookkeeper bk = new Bookkeeper();
        Arrays.fill(bk.perDisplayActiveBin, -1);

        IntConsumer incrementTime = inc -> {
            bk.currentTimeMs += inc;
            if (bk.overallActiveBin >= 0) {
                overallExpected[bk.overallActiveBin] += inc;
            }
            for (int i = 0; i < numDisplay; i++) {
                final int bin = bk.perDisplayActiveBin[i];
                if (bin >= 0) {
                    perDisplayExpected[i][bin] += inc;
                }
            }
            clocks.realtime = clocks.uptime = bk.currentTimeMs;
        };

        bi.updateTimeBasesLocked(true, Display.STATE_ON, 0, 0);
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        bi.noteScreenStateLocked(1, Display.STATE_ON);

        incrementTime.accept(100);
        bi.noteScreenBrightnessLocked(0, 25);
        bi.noteScreenBrightnessLocked(1, 25);
        // floor(25/256*5) = bin 0
        bk.overallActiveBin = 0;
        bk.perDisplayActiveBin[0] = 0;
        bk.perDisplayActiveBin[1] = 0;

        incrementTime.accept(50);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(13);
        bi.noteScreenBrightnessLocked(0, 100);
        // floor(25/256*5) = bin 1
        bk.overallActiveBin = 1;
        bk.perDisplayActiveBin[0] = 1;

        incrementTime.accept(44);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(22);
        bi.noteScreenBrightnessLocked(1, 200);
        // floor(200/256*5) = bin 3
        bk.overallActiveBin = 3;
        bk.perDisplayActiveBin[1] = 3;

        incrementTime.accept(33);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(77);
        bi.noteScreenBrightnessLocked(0, 150);
        // floor(150/256*5) = bin 2
        // Overall active bin should not change
        bk.perDisplayActiveBin[0] = 2;

        incrementTime.accept(88);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(11);
        bi.noteScreenStateLocked(1, Display.STATE_OFF);
        // Display 1 should timers should stop incrementing
        // Overall active bin should fallback to display 0's bin
        bk.overallActiveBin = 2;
        bk.perDisplayActiveBin[1] = -1;

        incrementTime.accept(99);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(200);
        bi.noteScreenBrightnessLocked(0, 255);
        // floor(150/256*5) = bin 4
        bk.overallActiveBin = 4;
        bk.perDisplayActiveBin[0] = 4;

        incrementTime.accept(300);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(200);
        bi.noteScreenStateLocked(0, Display.STATE_DOZE);
        // No displays are on. No brightness timers should be active.
        bk.overallActiveBin = -1;
        bk.perDisplayActiveBin[0] = -1;

        incrementTime.accept(300);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(400);
        bi.noteScreenStateLocked(1, Display.STATE_ON);
        // Display 1 turned back on.
        bk.overallActiveBin = 3;
        bk.perDisplayActiveBin[1] = 3;

        incrementTime.accept(500);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);

        incrementTime.accept(600);
        bi.noteScreenStateLocked(0, Display.STATE_ON);
        // Display 0 turned back on.
        bk.overallActiveBin = 4;
        bk.perDisplayActiveBin[0] = 4;

        incrementTime.accept(700);
        checkScreenBrightnesses(overallExpected, perDisplayExpected, bi, bk.currentTimeMs);
    }

    @SmallTest
    public void testAlarmStartAndFinishLocked() throws Exception {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
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

    @SmallTest
    public void testUpdateDisplayMeasuredEnergyStatsLocked() {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});

        clocks.realtime = 0;
        int[] screen = new int[]{Display.STATE_OFF};
        boolean battery = false;

        final int uid1 = 10500;
        final int uid2 = 10501;
        long blame1 = 0;
        long blame2 = 0;
        long globalDoze = 0;

        // Case A: uid1 off, uid2 off, battery off, screen off
        bi.updateTimeBasesLocked(battery, screen[0], clocks.realtime * 1000, 0);
        bi.setOnBatteryInternal(battery);
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{500_000}, screen, clocks.realtime);
        checkMeasuredCharge("A", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case B: uid1 off, uid2 off, battery ON,  screen off
        clocks.realtime += 17;
        battery = true;
        bi.updateTimeBasesLocked(battery, screen[0], clocks.realtime * 1000, 0);
        bi.setOnBatteryInternal(battery);
        clocks.realtime += 19;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{510_000}, screen, clocks.realtime);
        checkMeasuredCharge("B", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case C: uid1 ON,  uid2 off, battery on,  screen off
        clocks.realtime += 18;
        setFgState(uid1, true, bi);
        clocks.realtime += 18;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{520_000}, screen, clocks.realtime);
        checkMeasuredCharge("C", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case D: uid1 on,  uid2 off, battery on,  screen ON
        clocks.realtime += 17;
        screen[0] = Display.STATE_ON;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{521_000}, screen, clocks.realtime);
        blame1 += 0; // Screen had been off during the measurement period
        checkMeasuredCharge("D.1", uid1, blame1, uid2, blame2, globalDoze, bi);
        clocks.realtime += 101;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{530_000}, screen, clocks.realtime);
        blame1 += 530_000;
        checkMeasuredCharge("D.2", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case E: uid1 on,  uid2 ON,  battery on,  screen on
        clocks.realtime += 20;
        setFgState(uid2, true, bi);
        clocks.realtime += 40;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{540_000}, screen, clocks.realtime);
        // In the past 60ms, sum of fg is 20+40+40=100ms. uid1 is blamed for 60/100; uid2 for 40/100
        blame1 += 540_000 * (20 + 40) / (20 + 40 + 40);
        blame2 += 540_000 * (0 + 40) / (20 + 40 + 40);
        checkMeasuredCharge("E", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case F: uid1 on,  uid2 OFF, battery on,  screen on
        clocks.realtime += 40;
        setFgState(uid2, false, bi);
        clocks.realtime += 120;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{550_000}, screen, clocks.realtime);
        // In the past 160ms, sum f fg is 200ms. uid1 is blamed for 40+120 of it; uid2 for 40 of it.
        blame1 += 550_000 * (40 + 120) / (40 + 40 + 120);
        blame2 += 550_000 * (40 + 0) / (40 + 40 + 120);
        checkMeasuredCharge("F", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case G: uid1 on,  uid2 off,  battery on, screen DOZE
        clocks.realtime += 5;
        screen[0] = Display.STATE_DOZE;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{570_000}, screen, clocks.realtime);
        blame1 += 570_000; // All of this pre-doze time is blamed on uid1.
        checkMeasuredCharge("G", uid1, blame1, uid2, blame2, globalDoze, bi);

        // Case H: uid1 on,  uid2 off,  battery on, screen ON
        clocks.realtime += 6;
        screen[0] = Display.STATE_ON;
        bi.updateDisplayMeasuredEnergyStatsLocked(new long[]{580_000}, screen, clocks.realtime);
        blame1 += 0; // The screen had been doze during the energy period
        globalDoze += 580_000;
        checkMeasuredCharge("H", uid1, blame1, uid2, blame2, globalDoze, bi);
    }

    @SmallTest
    public void testUpdateCustomMeasuredEnergyStatsLocked_neverCalled() {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});
        bi.setOnBatteryInternal(true);

        final int uid1 = 11500;
        final int uid2 = 11501;

        // Initially, all custom buckets report charge of 0.
        checkCustomBatteryConsumption("0", 0, 0, uid1, 0, 0, uid2, 0, 0, bi);
    }

    @SmallTest
    public void testUpdateCustomMeasuredEnergyStatsLocked() {
        final MockClock clocks = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clocks);
        bi.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});

        final int bucketA = 0; // Custom bucket 0
        final int bucketB = 1; // Custom bucket 1

        long totalBlameA = 0; // Total charge consumption for bucketA (may exceed sum of uids)
        long totalBlameB = 0; // Total charge consumption for bucketB (may exceed sum of uids)

        final int uid1 = 10500;
        long blame1A = 0; // Blame for uid1 in bucketA
        long blame1B = 0; // Blame for uid1 in bucketB

        final int uid2 = 10501;
        long blame2A = 0; // Blame for uid2 in bucketA
        long blame2B = 0; // Blame for uid2 in bucketB

        final SparseLongArray newChargesA = new SparseLongArray(2);
        final SparseLongArray newChargesB = new SparseLongArray(2);


        // ----- Case A: battery off (so blame does not increase)
        bi.setOnBatteryInternal(false);

        newChargesA.put(uid1, 20_000);
        // Implicit newChargesA.put(uid2, 0);
        bi.updateCustomMeasuredEnergyStatsLocked(bucketA, 500_000, newChargesA);

        newChargesB.put(uid1, 60_000);
        // Implicit newChargesB.put(uid2, 0);
        bi.updateCustomMeasuredEnergyStatsLocked(bucketB, 700_000, newChargesB);

        checkCustomBatteryConsumption(
                "A", totalBlameA, totalBlameB, uid1, blame1A, blame1B, uid2, blame2A, blame2B, bi);


        // ----- Case B: battery on
        bi.setOnBatteryInternal(true);

        newChargesA.put(uid1, 7_000); blame1A += 7_000;
        // Implicit newChargesA.put(uid2, 0); blame2A += 0;
        bi.updateCustomMeasuredEnergyStatsLocked(bucketA, 310_000, newChargesA);
        totalBlameA += 310_000;

        newChargesB.put(uid1, 63_000); blame1B += 63_000;
        newChargesB.put(uid2, 15_000); blame2B += 15_000;
        bi.updateCustomMeasuredEnergyStatsLocked(bucketB, 790_000, newChargesB);
        totalBlameB += 790_000;

        checkCustomBatteryConsumption(
                "B", totalBlameA, totalBlameB, uid1, blame1A, blame1B, uid2, blame2A, blame2B, bi);


        // ----- Case C: battery still on
        newChargesA.delete(uid1); blame1A += 0;
        newChargesA.put(uid2, 16_000); blame2A += 16_000;
        bi.updateCustomMeasuredEnergyStatsLocked(bucketA, 560_000, newChargesA);
        totalBlameA += 560_000;

        bi.updateCustomMeasuredEnergyStatsLocked(bucketB, 10_000, null);
        totalBlameB += 10_000;

        checkCustomBatteryConsumption(
                "C", totalBlameA, totalBlameB, uid1, blame1A, blame1B, uid2, blame2A, blame2B, bi);


        // ----- Case D: battery still on
        bi.updateCustomMeasuredEnergyStatsLocked(bucketA, 0, newChargesA);
        bi.updateCustomMeasuredEnergyStatsLocked(bucketB, 15_000, new SparseLongArray(1));
        totalBlameB += 15_000;
        checkCustomBatteryConsumption(
                "D", totalBlameA, totalBlameB, uid1, blame1A, blame1B, uid2, blame2A, blame2B, bi);
    }

    @SmallTest
    public void testGetPerStateActiveRadioDurationMs_noModemActivity() {
        final MockClock clock = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clock);
        final int ratCount = RADIO_ACCESS_TECHNOLOGY_COUNT;
        final int frequencyCount = ServiceState.FREQUENCY_RANGE_MMWAVE + 1;
        final int txLevelCount = CellSignalStrength.getNumSignalStrengthLevels();

        final long[][][] expectedDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        final long[][] expectedRxDurationsMs = new long[ratCount][frequencyCount];
        final long[][][] expectedTxDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        for (int rat = 0; rat < ratCount; rat++) {
            for (int freq = 0; freq < frequencyCount; freq++) {
                // Should have no RX data without Modem Activity Info
                expectedRxDurationsMs[rat][freq] = POWER_DATA_UNAVAILABLE;
                for (int txLvl = 0; txLvl < txLevelCount; txLvl++) {
                    expectedDurationsMs[rat][freq][txLvl] = 0;
                    // Should have no TX data without Modem Activity Info
                    expectedTxDurationsMs[rat][freq][txLvl] = POWER_DATA_UNAVAILABLE;
                }
            }
        }

        final ModemAndBatteryState state = new ModemAndBatteryState(bi, null, null);

        IntConsumer incrementTime = inc -> {
            state.currentTimeMs += inc;
            clock.realtime = clock.uptime = state.currentTimeMs;

            // If the device is not on battery, no timers should increment.
            if (!state.onBattery) return;
            // If the modem is not active, no timers should increment.
            if (!state.modemActive) return;

            final int currentRat = state.currentRat;
            final int currentFrequencyRange =
                    currentRat == RADIO_ACCESS_TECHNOLOGY_NR ? state.currentFrequencyRange : 0;
            int currentSignalStrength = state.currentSignalStrengths.get(currentRat);
            expectedDurationsMs[currentRat][currentFrequencyRange][currentSignalStrength] += inc;
        };


        state.setOnBattery(false);
        state.setModemActive(false);
        state.setRatType(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER);
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_UNKNOWN);
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // While not on battery, the timers should not increase.
        state.setModemActive(true);
        incrementTime.accept(100);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR);
        incrementTime.accept(200);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_MMWAVE);
        incrementTime.accept(300);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_LTE,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE);
        incrementTime.accept(400);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // When set on battery, currently active state (RAT:LTE, Signal Strength:Moderate) should
        // start counting up.
        state.setOnBattery(true);
        incrementTime.accept(600);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
        // Changing LTE signal strength should be tracked.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(700);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        incrementTime.accept(800);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(900);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GREAT);
        incrementTime.accept(1000);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Change in the signal strength of nonactive RAT should not affect anything.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(1100);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Changing to OTHER Rat should start tracking the poor signal strength.
        state.setRatType(TelephonyManager.NETWORK_TYPE_CDMA,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER);
        incrementTime.accept(1200);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Noting frequency change should not affect non NR Rat.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_HIGH);
        incrementTime.accept(1300);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Now the NR Rat, HIGH frequency range, good signal strength should start counting.
        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR);
        incrementTime.accept(1400);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Noting frequency change should not affect non NR Rat.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_LOW);
        incrementTime.accept(1500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Modem no longer active, should not be tracking any more.
        state.setModemActive(false);
        incrementTime.accept(1500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
    }

    @SmallTest
    public void testGetPerStateActiveRadioDurationMs_withModemActivity() {
        final MockClock clock = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clock);
        bi.setPowerProfile(mock(PowerProfile.class));
        final int ratCount = RADIO_ACCESS_TECHNOLOGY_COUNT;
        final int frequencyCount = ServiceState.FREQUENCY_RANGE_MMWAVE + 1;
        final int txLevelCount = CellSignalStrength.getNumSignalStrengthLevels();

        final long[][][] expectedDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        final long[][] expectedRxDurationsMs = new long[ratCount][frequencyCount];
        final long[][][] expectedTxDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        for (int rat = 0; rat < ratCount; rat++) {
            for (int freq = 0; freq < frequencyCount; freq++) {
                expectedRxDurationsMs[rat][freq] = POWER_DATA_UNAVAILABLE;

                for (int txLvl = 0; txLvl < txLevelCount; txLvl++) {
                    expectedTxDurationsMs[rat][freq][txLvl] = POWER_DATA_UNAVAILABLE;
                }
            }
        }

        final ModemActivityInfo mai = new ModemActivityInfo(0L, 0L, 0L, new int[txLevelCount], 0L);
        final ModemAndBatteryState state = new ModemAndBatteryState(bi, mai, null);

        IntConsumer incrementTime = inc -> {
            state.currentTimeMs += inc;
            clock.realtime = clock.uptime = state.currentTimeMs;

            // If the device is not on battery, no timers should increment.
            if (!state.onBattery) return;
            // If the modem is not active, no timers should increment.
            if (!state.modemActive) return;

            final int currRat = state.currentRat;
            final int currFreqRange =
                    currRat == RADIO_ACCESS_TECHNOLOGY_NR ? state.currentFrequencyRange : 0;
            int currSignalStrength = state.currentSignalStrengths.get(currRat);

            expectedDurationsMs[currRat][currFreqRange][currSignalStrength] += inc;

            // Evaluate the HAL provided time in states.
            switch (state.modemState) {
                case SLEEP:
                    long sleepMs = state.modemActivityInfo.getSleepTimeMillis();
                    state.modemActivityInfo.setSleepTimeMillis(sleepMs + inc);
                    break;
                case IDLE:
                    long idleMs = state.modemActivityInfo.getIdleTimeMillis();
                    state.modemActivityInfo.setIdleTimeMillis(idleMs + inc);
                    break;
                case RECEIVING:
                    long rxMs = state.modemActivityInfo.getReceiveTimeMillis();
                    state.modemActivityInfo.setReceiveTimeMillis(rxMs + inc);
                    expectedRxDurationsMs[currRat][currFreqRange] += inc;
                    break;
                case TRANSMITTING:
                    int[] txMs = state.modemActivityInfo.getTransmitTimeMillis();
                    txMs[currSignalStrength] += inc;
                    state.modemActivityInfo.setTransmitTimeMillis(txMs);
                    expectedTxDurationsMs[currRat][currFreqRange][currSignalStrength] += inc;
                    break;
            }
        };

        state.setOnBattery(false);
        state.setModemActive(false);
        state.setRatType(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER);
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_UNKNOWN);
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // While not on battery, the timers should not increase.
        state.setModemActive(true);
        incrementTime.accept(100);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR);
        incrementTime.accept(200);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_MMWAVE);
        incrementTime.accept(300);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_LTE,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE);
        incrementTime.accept(400);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Data will now be available.
        for (int rat = 0; rat < ratCount; rat++) {
            for (int freq = 0; freq < frequencyCount; freq++) {
                if (rat == RADIO_ACCESS_TECHNOLOGY_NR
                        || freq == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                    // Only the NR RAT should have per frequency data.
                    expectedRxDurationsMs[rat][freq] = 0;
                }
                for (int txLvl = 0; txLvl < txLevelCount; txLvl++) {
                    if (rat == RADIO_ACCESS_TECHNOLOGY_NR
                            || freq == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                        // Only the NR RAT should have per frequency data.
                        expectedTxDurationsMs[rat][freq][txLvl] = 0;
                    }
                }
            }
        }

        // When set on battery, currently active state (RAT:LTE, Signal Strength:Moderate) should
        // start counting up.
        state.setOnBattery(true);
        incrementTime.accept(300);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(500);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(600);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
        // Changing LTE signal strength should be tracked.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(300);
        state.setModemState(ModemState.SLEEP);
        incrementTime.accept(1000);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(700);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        incrementTime.accept(800);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(222);
        state.setModemState(ModemState.IDLE);
        incrementTime.accept(111);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(7777);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(88);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(900);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GREAT);
        incrementTime.accept(123);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(333);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1000);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(555);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Change in the signal strength of nonactive RAT should not affect anything.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(631);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(321);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(99);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Changing to OTHER Rat should start tracking the poor signal strength.
        state.setRatType(TelephonyManager.NETWORK_TYPE_CDMA,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER);
        incrementTime.accept(1200);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Noting frequency change should not affect non NR Rat.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_HIGH);
        incrementTime.accept(444);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1300);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Now the NR Rat, HIGH frequency range, good signal strength should start counting.
        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR);
        incrementTime.accept(1400);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Frequency changed to low.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_LOW);
        incrementTime.accept(852);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(157);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1500);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Modem no longer active, should not be tracking any more.
        state.setModemActive(false);
        incrementTime.accept(1500);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
    }

    @SmallTest
    public void testGetPerStateActiveRadioDurationMs_withSpecificInfoModemActivity() {
        final MockClock clock = new MockClock(); // holds realtime and uptime in ms
        final MockBatteryStatsImpl bi = new MockBatteryStatsImpl(clock);
        bi.setPowerProfile(mock(PowerProfile.class));
        final int ratCount = RADIO_ACCESS_TECHNOLOGY_COUNT;
        final int frequencyCount = ServiceState.FREQUENCY_RANGE_MMWAVE + 1;
        final int txLevelCount = CellSignalStrength.getNumSignalStrengthLevels();

        List<ActivityStatsTechSpecificInfo> specificInfoList = new ArrayList();

        final long[][][] expectedDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        final long[][] expectedRxDurationsMs = new long[ratCount][frequencyCount];
        final long[][][] expectedTxDurationsMs = new long[ratCount][frequencyCount][txLevelCount];
        for (int rat = 0; rat < ratCount; rat++) {
            for (int freq = 0; freq < frequencyCount; freq++) {
                if (rat == RADIO_ACCESS_TECHNOLOGY_NR
                        || freq == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                    // Initialize available specific Modem info
                    specificInfoList.add(
                            new ActivityStatsTechSpecificInfo(rat, freq, new int[txLevelCount], 0));
                }
                expectedRxDurationsMs[rat][freq] = POWER_DATA_UNAVAILABLE;

                for (int txLvl = 0; txLvl < txLevelCount; txLvl++) {
                    expectedTxDurationsMs[rat][freq][txLvl] = POWER_DATA_UNAVAILABLE;
                }
            }
        }

        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.UNKNOWN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.GERAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.UTRAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.EUTRAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.CDMA2000,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.IWLAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.NGRAN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.NGRAN,
                ServiceState.FREQUENCY_RANGE_LOW, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.NGRAN,
                ServiceState.FREQUENCY_RANGE_MID, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.NGRAN,
                ServiceState.FREQUENCY_RANGE_HIGH, new int[txLevelCount], 0));
        specificInfoList.add(new ActivityStatsTechSpecificInfo(AccessNetwork.NGRAN,
                ServiceState.FREQUENCY_RANGE_MMWAVE, new int[txLevelCount], 0));

        final ActivityStatsTechSpecificInfo[] specificInfos = specificInfoList.toArray(
                new ActivityStatsTechSpecificInfo[specificInfoList.size()]);
        final ModemActivityInfo mai = new ModemActivityInfo(0L, 0L, 0L, specificInfos);
        final ModemAndBatteryState state = new ModemAndBatteryState(bi, mai, specificInfos);

        IntConsumer incrementTime = inc -> {
            state.currentTimeMs += inc;
            clock.realtime = clock.uptime = state.currentTimeMs;

            // If the device is not on battery, no timers should increment.
            if (!state.onBattery) return;
            // If the modem is not active, no timers should increment.
            if (!state.modemActive) return;

            final int currRat = state.currentRat;
            final int currRant = state.currentRadioAccessNetworkType;
            final int currFreqRange =
                    currRat == RADIO_ACCESS_TECHNOLOGY_NR ? state.currentFrequencyRange : 0;
            int currSignalStrength = state.currentSignalStrengths.get(currRat);

            expectedDurationsMs[currRat][currFreqRange][currSignalStrength] += inc;

            // Evaluate the HAL provided time in states.
            final ActivityStatsTechSpecificInfo info = state.getSpecificInfo(currRant,
                    currFreqRange);
            switch (state.modemState) {
                case SLEEP:
                    long sleepMs = state.modemActivityInfo.getSleepTimeMillis();
                    state.modemActivityInfo.setSleepTimeMillis(sleepMs + inc);
                    break;
                case IDLE:
                    long idleMs = state.modemActivityInfo.getIdleTimeMillis();
                    state.modemActivityInfo.setIdleTimeMillis(idleMs + inc);
                    break;
                case RECEIVING:
                    long rxMs = info.getReceiveTimeMillis();
                    info.setReceiveTimeMillis(rxMs + inc);
                    expectedRxDurationsMs[currRat][currFreqRange] += inc;
                    break;
                case TRANSMITTING:
                    int[] txMs = info.getTransmitTimeMillis().clone();
                    txMs[currSignalStrength] += inc;
                    info.setTransmitTimeMillis(txMs);
                    expectedTxDurationsMs[currRat][currFreqRange][currSignalStrength] += inc;
                    break;
            }
        };

        state.setOnBattery(false);
        state.setModemActive(false);
        state.setRatType(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                AccessNetworkConstants.AccessNetworkType.UNKNOWN);
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_UNKNOWN);
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // While not on battery, the timers should not increase.
        state.setModemActive(true);
        incrementTime.accept(100);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                AccessNetworkConstants.AccessNetworkType.NGRAN);
        incrementTime.accept(200);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_MMWAVE);
        incrementTime.accept(300);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setRatType(TelephonyManager.NETWORK_TYPE_LTE,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                AccessNetworkConstants.AccessNetworkType.EUTRAN);
        incrementTime.accept(400);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE);
        incrementTime.accept(500);
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Data will now be available.
        for (int rat = 0; rat < ratCount; rat++) {
            for (int freq = 0; freq < frequencyCount; freq++) {
                if (rat == RADIO_ACCESS_TECHNOLOGY_NR
                        || freq == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                    // Only the NR RAT should have per frequency data.
                    expectedRxDurationsMs[rat][freq] = 0;
                }
                for (int txLvl = 0; txLvl < txLevelCount; txLvl++) {
                    if (rat == RADIO_ACCESS_TECHNOLOGY_NR
                            || freq == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
                        // Only the NR RAT should have per frequency data.
                        expectedTxDurationsMs[rat][freq][txLvl] = 0;
                    }
                }
            }
        }

        // When set on battery, currently active state (RAT:LTE, Signal Strength:Moderate) should
        // start counting up.
        state.setOnBattery(true);
        state.noteModemControllerActivity();
        incrementTime.accept(300);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(500);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(600);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
        // Changing LTE signal strength should be tracked.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(300);
        state.setModemState(ModemState.SLEEP);
        incrementTime.accept(1000);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(700);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        incrementTime.accept(800);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(222);
        state.setModemState(ModemState.IDLE);
        incrementTime.accept(111);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(7777);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        incrementTime.accept(88);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(900);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                CellSignalStrength.SIGNAL_STRENGTH_GREAT);
        incrementTime.accept(123);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(333);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1000);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(555);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Change in the signal strength of nonactive RAT should not affect anything.
        state.setSignalStrength(BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        incrementTime.accept(631);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(321);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(99);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Changing to OTHER Rat should start tracking the poor signal strength.
        state.setRatType(TelephonyManager.NETWORK_TYPE_CDMA,
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER,
                AccessNetworkConstants.AccessNetworkType.CDMA2000);
        incrementTime.accept(1200);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Noting frequency change should not affect non NR Rat.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_HIGH);
        incrementTime.accept(444);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1300);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Now the NR Rat, HIGH frequency range, good signal strength should start counting.
        state.setRatType(TelephonyManager.NETWORK_TYPE_NR, BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                AccessNetworkConstants.AccessNetworkType.NGRAN);
        incrementTime.accept(1400);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Frequency changed to low.
        state.setFrequencyRange(ServiceState.FREQUENCY_RANGE_LOW);
        incrementTime.accept(852);
        state.setModemState(ModemState.RECEIVING);
        incrementTime.accept(157);
        state.setModemState(ModemState.TRANSMITTING);
        incrementTime.accept(1500);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);

        // Modem no longer active, should not be tracking any more.
        state.setModemActive(false);
        incrementTime.accept(1500);
        state.noteModemControllerActivity();
        checkPerStateActiveRadioDurations(expectedDurationsMs, expectedRxDurationsMs,
                expectedTxDurationsMs, bi, state.currentTimeMs);
    }

    private void setFgState(int uid, boolean fgOn, MockBatteryStatsImpl bi) {
        // Note that noteUidProcessStateLocked uses ActivityManager process states.
        if (fgOn) {
            bi.noteActivityResumedLocked(uid);
            bi.noteUidProcessStateLocked(uid, ActivityManager.PROCESS_STATE_TOP);
        } else {
            bi.noteActivityPausedLocked(uid);
            bi.noteUidProcessStateLocked(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        }
    }

    private void checkMeasuredCharge(String caseName, int uid1, long blame1, int uid2, long blame2,
            long globalDoze, MockBatteryStatsImpl bi) {
        final int bucket = MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON;

        assertEquals("Wrong uid1 blame for Case " + caseName, blame1,
                bi.getUidStatsLocked(uid1).getMeasuredBatteryConsumptionUC(bucket));

        assertEquals("Wrong uid2 blame for Case " + caseName, blame2,
                bi.getUidStatsLocked(uid2).getMeasuredBatteryConsumptionUC(bucket));

        assertEquals("Wrong total blame for Case " + caseName, blame1 + blame2,
                bi.getScreenOnMeasuredBatteryConsumptionUC());

        assertEquals("Wrong doze for Case " + caseName, globalDoze,
                bi.getScreenDozeMeasuredBatteryConsumptionUC());
    }

    private void checkCustomBatteryConsumption(String caseName,
            long totalBlameA, long totalBlameB,
            int uid1, long blame1A, long blame1B,
            int uid2, long blame2A, long blame2B,
            MockBatteryStatsImpl bi) {

        final long[] actualTotal = bi.getCustomConsumerMeasuredBatteryConsumptionUC();
        final long[] actualUid1 =
                bi.getUidStatsLocked(uid1).getCustomConsumerMeasuredBatteryConsumptionUC();
        final long[] actualUid2 =
                bi.getUidStatsLocked(uid2).getCustomConsumerMeasuredBatteryConsumptionUC();

        assertNotNull(actualTotal);
        assertNotNull(actualUid1);
        assertNotNull(actualUid2);

        assertEquals("Wrong total blame in bucket 0 for Case " + caseName, totalBlameA,
                actualTotal[0]);

        assertEquals("Wrong total blame in bucket 1 for Case " + caseName, totalBlameB,
                actualTotal[1]);

        assertEquals("Wrong uid1 blame in bucket 0 for Case " + caseName, blame1A, actualUid1[0]);

        assertEquals("Wrong uid1 blame in bucket 1 for Case " + caseName, blame1B, actualUid1[1]);

        assertEquals("Wrong uid2 blame in bucket 0 for Case " + caseName, blame2A, actualUid2[0]);

        assertEquals("Wrong uid2 blame in bucket 1 for Case " + caseName, blame2B, actualUid2[1]);
    }

    private void checkScreenBrightnesses(long[] overallExpected, long[][] perDisplayExpected,
            BatteryStatsImpl bi, long currentTimeMs) {
        final int numDisplay = bi.getDisplayCount();
        for (int bin = 0; bin < NUM_SCREEN_BRIGHTNESS_BINS; bin++) {
            for (int display = 0; display < numDisplay; display++) {
                assertEquals("Failure for display " + display + " screen brightness bin " + bin,
                        perDisplayExpected[display][bin] * 1000,
                        bi.getDisplayScreenBrightnessTime(display, bin, currentTimeMs * 1000));
            }
            assertEquals("Failure for overall screen brightness bin " + bin,
                    overallExpected[bin] * 1000,
                    bi.getScreenBrightnessTime(bin, currentTimeMs * 1000, STATS_SINCE_CHARGED));
        }
    }

    private void checkPerStateActiveRadioDurations(long[][][] expectedDurationsMs,
            long[][] expectedRxDurationsMs, long[][][] expectedTxDurationsMs,
            BatteryStatsImpl bi, long currentTimeMs) {
        for (int rat = 0; rat < expectedDurationsMs.length; rat++) {
            final long[][] expectedRatDurationsMs = expectedDurationsMs[rat];
            for (int freq = 0; freq < expectedRatDurationsMs.length; freq++) {
                final long expectedRxDurationMs = expectedRxDurationsMs[rat][freq];

                // Build a verbose fail message, just in case.
                final StringBuilder rxFailSb = new StringBuilder();
                rxFailSb.append("Wrong time in Rx state for RAT:");
                rxFailSb.append(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NAMES[rat]);
                rxFailSb.append(", frequency:");
                rxFailSb.append(ServiceState.frequencyRangeToString(freq));
                assertEquals(rxFailSb.toString(), expectedRxDurationMs,
                        bi.getActiveRxRadioDurationMs(rat, freq, currentTimeMs));

                final long[] expectedFreqDurationsMs = expectedRatDurationsMs[freq];
                for (int strength = 0; strength < expectedFreqDurationsMs.length; strength++) {
                    final long expectedSignalStrengthDurationMs = expectedFreqDurationsMs[strength];
                    final long expectedTxDurationMs = expectedTxDurationsMs[rat][freq][strength];
                    final long actualDurationMs = bi.getActiveRadioDurationMs(rat, freq,
                            strength, currentTimeMs);

                    final StringBuilder failSb = new StringBuilder();
                    failSb.append("Wrong time in state for RAT:");
                    failSb.append(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NAMES[rat]);
                    failSb.append(", frequency:");
                    failSb.append(ServiceState.frequencyRangeToString(freq));
                    failSb.append(", strength:");
                    failSb.append(strength);
                    assertEquals(failSb.toString(), expectedSignalStrengthDurationMs,
                            actualDurationMs);

                    final StringBuilder txFailSb = new StringBuilder();
                    txFailSb.append("Wrong time in Tx state for RAT:");
                    txFailSb.append(BatteryStats.RADIO_ACCESS_TECHNOLOGY_NAMES[rat]);
                    txFailSb.append(", frequency:");
                    txFailSb.append(ServiceState.frequencyRangeToString(freq));
                    txFailSb.append(", strength:");
                    txFailSb.append(strength);
                    assertEquals(txFailSb.toString(), expectedTxDurationMs,
                            bi.getActiveTxRadioDurationMs(rat, freq, strength, currentTimeMs));
                }
            }
        }
    }

    private class ModemAndBatteryState {
        public long currentTimeMs = 100;
        public boolean onBattery = false;
        public boolean modemActive = false;
        @Annotation.NetworkType
        public int currentNetworkDataType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        @BatteryStats.RadioAccessTechnology
        public int currentRat = BatteryStats.RADIO_ACCESS_TECHNOLOGY_OTHER;
        @AccessNetworkConstants.RadioAccessNetworkType
        public int currentRadioAccessNetworkType = AccessNetworkConstants.AccessNetworkType.UNKNOWN;
        @ServiceState.FrequencyRange
        public int currentFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;
        public SparseIntArray currentSignalStrengths = new SparseIntArray();
        public ModemState modemState = ModemState.SLEEP;
        public ModemActivityInfo modemActivityInfo;
        public ActivityStatsTechSpecificInfo[] specificInfo;

        private final MockBatteryStatsImpl mBsi;

        ModemAndBatteryState(MockBatteryStatsImpl bsi, ModemActivityInfo mai,
                ActivityStatsTechSpecificInfo[] astsi) {
            mBsi = bsi;
            modemActivityInfo = mai;
            specificInfo = astsi;
        }

        void setOnBattery(boolean onBattery) {
            this.onBattery = onBattery;
            mBsi.updateTimeBasesLocked(onBattery, Display.STATE_OFF, currentTimeMs * 1000,
                    currentTimeMs * 1000);
            mBsi.setOnBatteryInternal(onBattery);
            noteModemControllerActivity();
        }

        void setModemActive(boolean active) {
            modemActive = active;
            final int state = active ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                    : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
            mBsi.noteMobileRadioPowerStateLocked(state, currentTimeMs * 1000_000L, UID);
            noteModemControllerActivity();
        }

        void setRatType(@Annotation.NetworkType int dataType,
                @BatteryStats.RadioAccessTechnology int rat,
                @AccessNetworkConstants.RadioAccessNetworkType int halDataType) {
            currentRadioAccessNetworkType = halDataType;
            setRatType(dataType, rat);
        }

        void setRatType(@Annotation.NetworkType int dataType,
                @BatteryStats.RadioAccessTechnology int rat) {
            currentNetworkDataType = dataType;
            currentRat = rat;
            mBsi.notePhoneDataConnectionStateLocked(dataType, true, ServiceState.STATE_IN_SERVICE,
                    currentFrequencyRange);
        }

        void setFrequencyRange(@ServiceState.FrequencyRange int frequency) {
            currentFrequencyRange = frequency;
            mBsi.notePhoneDataConnectionStateLocked(currentNetworkDataType, true,
                    ServiceState.STATE_IN_SERVICE, frequency);
        }

        void setSignalStrength(@BatteryStats.RadioAccessTechnology int rat, int strength) {
            currentSignalStrengths.put(rat, strength);
            final int size = currentSignalStrengths.size();
            final int newestGenSignalStrength = currentSignalStrengths.valueAt(size - 1);
            mBsi.notePhoneSignalStrengthLocked(newestGenSignalStrength, currentSignalStrengths);
        }

        void setModemState(ModemState state) {
            modemState = state;
        }

        ActivityStatsTechSpecificInfo getSpecificInfo(@BatteryStats.RadioAccessTechnology int rat,
                @ServiceState.FrequencyRange int frequency) {
            if (specificInfo == null) return null;
            for (ActivityStatsTechSpecificInfo info : specificInfo) {
                if (info.getRat() == rat && info.getFrequencyRange() == frequency) {
                    return info;
                }
            }
            return null;
        }

        void noteModemControllerActivity() {
            if (modemActivityInfo == null) return;
            modemActivityInfo.setTimestamp(currentTimeMs);
            final ModemActivityInfo copy;
            if (specificInfo == null) {
                copy = new ModemActivityInfo(
                        modemActivityInfo.getTimestampMillis(),
                        modemActivityInfo.getSleepTimeMillis(),
                        modemActivityInfo.getIdleTimeMillis(),
                        modemActivityInfo.getTransmitTimeMillis().clone(),
                        modemActivityInfo.getReceiveTimeMillis());
            } else {
                // Deep copy specificInfo
                final ActivityStatsTechSpecificInfo[] infoCopies =
                        new ActivityStatsTechSpecificInfo[specificInfo.length];
                for (int i = 0; i < specificInfo.length; i++) {
                    final ActivityStatsTechSpecificInfo info = specificInfo[i];
                    infoCopies[i] = new ActivityStatsTechSpecificInfo(info.getRat(),
                            info.getFrequencyRange(), info.getTransmitTimeMillis().clone(),
                            (int) info.getReceiveTimeMillis());
                }

                copy = new ModemActivityInfo(
                        modemActivityInfo.getTimestampMillis(),
                        modemActivityInfo.getSleepTimeMillis(),
                        modemActivityInfo.getIdleTimeMillis(),
                        infoCopies);
            }
            mBsi.noteModemControllerActivity(copy, POWER_DATA_UNAVAILABLE,
                    currentTimeMs, currentTimeMs, mNetworkStatsManager);
        }
    }
}
