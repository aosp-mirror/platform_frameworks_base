/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.os.BatteryStats.UID_TIMES_TYPE_ALL;
import static android.os.BatteryStats.Uid.NUM_PROCESS_STATE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryStats.Uid.PROCESS_STATE_CACHED;
import static android.os.BatteryStats.Uid.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.os.BatteryStats.Uid.PROCESS_STATE_TOP;
import static android.os.BatteryStats.Uid.PROCESS_STATE_TOP_SLEEPING;
import static android.os.BatteryStats.Uid.UID_PROCESS_TYPES;

import static com.android.internal.os.BatteryStatsImpl.Constants.KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS;
import static com.android.internal.os.BatteryStatsImpl.Constants.KEY_TRACK_CPU_TIMES_BY_PROC_STATE;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.KeyValueListParser;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.aidl.ICmdCallback;
import com.android.frameworks.coretests.aidl.ICmdReceiver;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class BstatsCpuTimesValidationTest {
    private static final String TAG = BstatsCpuTimesValidationTest.class.getSimpleName();

    private static final String TEST_PKG = "com.android.coretests.apps.bstatstestapp";
    private static final String TEST_ACTIVITY = TEST_PKG + ".TestActivity";
    private static final String TEST_SERVICE = TEST_PKG + ".TestService";
    private static final String ISOLATED_TEST_SERVICE = TEST_PKG + ".IsolatedTestService";

    private static final String EXTRA_KEY_CMD_RECEIVER = "cmd_receiver";
    private static final int FLAG_START_FOREGROUND = 1;

    private static final int BATTERY_STATE_TIMEOUT_MS = 2000;
    private static final int BATTERY_STATE_CHECK_INTERVAL_MS = 200;

    private static final int START_ACTIVITY_TIMEOUT_MS = 2000;
    private static final int START_FG_SERVICE_TIMEOUT_MS = 2000;
    private static final int START_SERVICE_TIMEOUT_MS = 2000;
    private static final int START_ISOLATED_SERVICE_TIMEOUT_MS = 2000;

    private static final int SETTING_UPDATE_TIMEOUT_MS = 2000;
    private static final int SETTING_UPDATE_CHECK_INTERVAL_MS = 200;

    private static final int GENERAL_TIMEOUT_MS = 4000;
    private static final int GENERAL_INTERVAL_MS = 200;

    private static final int WORK_DURATION_MS = 2000;

    private static boolean sBatteryStatsConstsUpdated;
    private static String sOriginalBatteryStatsConsts;

    private static Context sContext;
    private static UiDevice sUiDevice;
    private static int sTestPkgUid;
    private static boolean sCpuFreqTimesAvailable;
    private static boolean sPerProcStateTimesAvailable;

    @Rule public TestName testName = new TestName();

    @BeforeClass
    public static void setupOnce() throws Exception {
        sContext = InstrumentationRegistry.getContext();
        sUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        sContext.getPackageManager().setApplicationEnabledSetting(TEST_PKG,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        sTestPkgUid = sContext.getPackageManager().getPackageUid(TEST_PKG, 0);
        executeCmd("cmd deviceidle whitelist +" + TEST_PKG);

        final ArrayMap<String, String> desiredConstants = new ArrayMap<>();
        desiredConstants.put(KEY_TRACK_CPU_TIMES_BY_PROC_STATE, Boolean.toString(true));
        desiredConstants.put(KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS, Integer.toString(0));
        updateBatteryStatsConstants(desiredConstants);
        checkCpuTimesAvailability();
    }

    @AfterClass
    public static void tearDownOnce() throws Exception {
        executeCmd("cmd deviceidle whitelist -" + TEST_PKG);
        if (sBatteryStatsConstsUpdated) {
            Settings.Global.putString(sContext.getContentResolver(),
                    Settings.Global.BATTERY_STATS_CONSTANTS, sOriginalBatteryStatsConsts);
        }
        batteryReset();
    }

    private static void updateBatteryStatsConstants(ArrayMap<String, String> desiredConstants) {
        sOriginalBatteryStatsConsts = Settings.Global.getString(sContext.getContentResolver(),
                Settings.Global.BATTERY_STATS_CONSTANTS);
        final char delimiter = ',';
        final KeyValueListParser parser = new KeyValueListParser(delimiter);
        parser.setString(sOriginalBatteryStatsConsts);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0, size = parser.size(); i < size; ++i) {
            final String key = parser.keyAt(i);
            final String value = desiredConstants.getOrDefault(key,
                    parser.getString(key, null));
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(key + "=" + value);
            desiredConstants.remove(key);
        }
        desiredConstants.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(key + '=' + value);
        });
        Settings.Global.putString(sContext.getContentResolver(),
                Settings.Global.BATTERY_STATS_CONSTANTS, sb.toString());
        Log.d(TAG, "Updated value of '" + Settings.Global.BATTERY_STATS_CONSTANTS + "': "
                + sb.toString());
        sBatteryStatsConstsUpdated = true;
    }

    // Checks cpu freq times of system uid as an indication of whether /proc/uid_time_in_state
    // and /proc/uid/<uid>/time_in_state kernel nodes are available.
    private static void checkCpuTimesAvailability() throws Exception {
        batteryOn();
        SystemClock.sleep(GENERAL_TIMEOUT_MS);
        batteryOff();
        final long[] totalCpuTimes = getAllCpuFreqTimes(Process.SYSTEM_UID);
        sCpuFreqTimesAvailable = totalCpuTimes != null;
        final long[] fgCpuTimes = getAllCpuFreqTimes(Process.SYSTEM_UID,
                PROCESS_STATE_FOREGROUND);
        final long[] topCpuTimes = getAllCpuFreqTimes(Process.SYSTEM_UID,
                PROCESS_STATE_TOP);
        sPerProcStateTimesAvailable = fgCpuTimes != null || topCpuTimes != null;
    }

    @Test
    public void testCpuFreqTimes() throws Exception {
        if (!sCpuFreqTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        doSomeWork();
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid);
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time", WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_screenOff() throws Exception {
        if (!sCpuFreqTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOff();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        doSomeWork();
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid);
        assertCpuTimesValid(cpuTimesMs);
        long actualTotalCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualTotalCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time", WORK_DURATION_MS, actualTotalCpuTimeMs);
        long actualScreenOffCpuTimeMs = 0;
        for (int i = cpuTimesMs.length / 2; i < cpuTimesMs.length; ++i) {
            actualScreenOffCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect screen-off cpu time",
                WORK_DURATION_MS, actualScreenOffCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_isolatedProcess() throws Exception {
        if (!sCpuFreqTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        doSomeWorkInIsolatedProcess();
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid);
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time", WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_stateTop() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP));

        doSomeWork(PROCESS_STATE_TOP);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testIsolatedCpuFreqTimes_stateService() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP));

        final ICmdReceiver activityReceiver = ICmdReceiver.Stub.asInterface(startActivity());
        final ICmdReceiver isolatedReceiver = ICmdReceiver.Stub.asInterface(startIsolatedService());
        try {
            assertProcState(PROCESS_STATE_TOP);
            isolatedReceiver.doSomeWork(WORK_DURATION_MS);
        } finally {
            activityReceiver.finishHost();
            isolatedReceiver.finishHost();
        }
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_stateTopSleeping() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOff();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP_SLEEPING));

        doSomeWork(PROCESS_STATE_TOP_SLEEPING);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP_SLEEPING);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = cpuTimesMs.length / 2; i < cpuTimesMs.length; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_stateFgService() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOff();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_FOREGROUND_SERVICE));

        doSomeWork(PROCESS_STATE_FOREGROUND_SERVICE);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_FOREGROUND_SERVICE);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_stateFg() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_FOREGROUND));

        doSomeWork(PROCESS_STATE_FOREGROUND);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_FOREGROUND);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOff();
    }

    @Test
    public void testCpuFreqTimes_stateBg() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOff();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_BACKGROUND));

        doSomeWork(PROCESS_STATE_BACKGROUND);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_BACKGROUND);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_stateCached() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null, initial=" + Arrays.toString(initialSnapshot),
                initialSnapshot);
        assertNull("Initial top state snapshot should be null",
                getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_CACHED));

        doSomeWork(PROCESS_STATE_CACHED);
        forceStop();

        final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_CACHED);
        final String msgCpuTimes = getAllCpuTimesMsg();
        assertCpuTimesValid(cpuTimesMs);
        long actualCpuTimeMs = 0;
        for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
            actualCpuTimeMs += cpuTimesMs[i];
        }
        assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                WORK_DURATION_MS, actualCpuTimeMs);
        batteryOffScreenOn();
    }

    @Test
    public void testCpuFreqTimes_trackingDisabled() throws Exception {
        if (!sCpuFreqTimesAvailable || !sPerProcStateTimesAvailable) {
            Log.w(TAG, "Skipping " + testName.getMethodName()
                    + "; freqTimesAvailable=" + sCpuFreqTimesAvailable
                    + ", procStateTimesAvailable=" + sPerProcStateTimesAvailable);
            return;
        }

        final String bstatsConstants = Settings.Global.getString(sContext.getContentResolver(),
                Settings.Global.BATTERY_STATS_CONSTANTS);
        try {
            batteryOnScreenOn();
            forceStop();
            resetBatteryStats();
            final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
            assertNull("Initial snapshot should be null, initial="
                    + Arrays.toString(initialSnapshot), initialSnapshot);
            assertNull("Initial top state snapshot should be null",
                    getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP));

            doSomeWork(PROCESS_STATE_TOP);
            forceStop();

            final long[] cpuTimesMs = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
            final String msgCpuTimes = getAllCpuTimesMsg();
            assertCpuTimesValid(cpuTimesMs);
            long actualCpuTimeMs = 0;
            for (int i = 0; i < cpuTimesMs.length / 2; ++i) {
                actualCpuTimeMs += cpuTimesMs[i];
            }
            assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                    WORK_DURATION_MS, actualCpuTimeMs);

            updateTrackPerProcStateCpuTimesSetting(bstatsConstants, false);

            doSomeWork(PROCESS_STATE_TOP);
            forceStop();

            final long[] cpuTimesMs2 = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
            assertCpuTimesValid(cpuTimesMs2);
            assertCpuTimesEqual(cpuTimesMs2, cpuTimesMs, 20,
                    "Unexpected cpu times with tracking off");

            updateTrackPerProcStateCpuTimesSetting(bstatsConstants, true);

            final long[] cpuTimesMs3 = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
            assertCpuTimesValid(cpuTimesMs3);
            assertCpuTimesEqual(cpuTimesMs3, cpuTimesMs, 500,
                    "Unexpected cpu times after turning on tracking");

            doSomeWork(PROCESS_STATE_TOP);
            forceStop();

            final long[] cpuTimesMs4 = getAllCpuFreqTimes(sTestPkgUid, PROCESS_STATE_TOP);
            assertCpuTimesValid(cpuTimesMs4);
            actualCpuTimeMs = 0;
            for (int i = 0; i < cpuTimesMs4.length / 2; ++i) {
                actualCpuTimeMs += cpuTimesMs4[i];
            }
            assertApproximateValue("Incorrect total cpu time, " + msgCpuTimes,
                    2 * WORK_DURATION_MS, actualCpuTimeMs);

            batteryOffScreenOn();
        } finally {
            Settings.Global.putString(sContext.getContentResolver(),
                    Settings.Global.BATTERY_STATS_CONSTANTS, bstatsConstants);
        }
    }

    private void assertCpuTimesEqual(long[] actual, long[] expected, long delta, String errMsg) {
        for (int i = actual.length - 1; i >= 0; --i) {
            if (actual[i] > expected[i] + delta || actual[i] < expected[i]) {
                fail(errMsg + ", actual=" + Arrays.toString(actual)
                        + ", expected=" + Arrays.toString(expected) + ", delta=" + delta);
            }
        }
    }

    private void updateTrackPerProcStateCpuTimesSetting(String originalConstants, boolean enabled)
            throws Exception {
        final String newConstants;
        final String setting = KEY_TRACK_CPU_TIMES_BY_PROC_STATE + "=" + enabled;
        if (originalConstants == null || "null".equals(originalConstants)) {
            newConstants = setting;
        } else if (originalConstants.contains(KEY_TRACK_CPU_TIMES_BY_PROC_STATE)) {
            newConstants = originalConstants.replaceAll(
                    KEY_TRACK_CPU_TIMES_BY_PROC_STATE + "=(true|false)", setting);
        } else {
            newConstants = originalConstants + "," + setting;
        }
        Settings.Global.putString(sContext.getContentResolver(),
                Settings.Global.BATTERY_STATS_CONSTANTS, newConstants);
        assertTrackPerProcStateCpuTimesSetting(enabled);
    }

    private void assertTrackPerProcStateCpuTimesSetting(boolean enabled) throws Exception {
        final String expectedValue = Boolean.toString(enabled);
        assertDelayedCondition("Unexpected value for " + KEY_TRACK_CPU_TIMES_BY_PROC_STATE, () -> {
            final String actualValue = getSettingValueFromDump(KEY_TRACK_CPU_TIMES_BY_PROC_STATE);
            return expectedValue.equals(actualValue)
                    ? null : "expected=" + expectedValue + ", actual=" + actualValue;
        }, SETTING_UPDATE_TIMEOUT_MS, SETTING_UPDATE_CHECK_INTERVAL_MS);
    }

    private String getSettingValueFromDump(String key) throws Exception {
        final String settingsDump = executeCmdSilent("dumpsys batterystats --settings");
        final TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter('\n');
        splitter.setString(settingsDump);
        String next;
        while (splitter.hasNext()) {
            next = splitter.next().trim();
            if (next.startsWith(key)) {
                return next.split("=")[1];
            }
        }
        return null;
    }

    private void assertCpuTimesValid(long[] cpuTimes) {
        assertNotNull(cpuTimes);
        for (int i = 0; i < cpuTimes.length; ++i) {
            if (cpuTimes[i] < 0) {
                fail("Malformed cpu times data (-ve values): " + Arrays.toString(cpuTimes));
            }
        }
        final int numFreqs = cpuTimes.length / 2;
        for (int i = 0; i < numFreqs; ++i) {
            if (cpuTimes[i] < cpuTimes[numFreqs + i]) {
                fail("Malformed cpu times data (screen-off > total)" + Arrays.toString(cpuTimes));
            }
        }
    }

    private void assertApproximateValue(String errorPrefix, long expectedValueMs,
            long actualValueMs) {
        // Allow the actual value to be 1 second smaller than the expected.
        // Also allow it to be up to 5 seconds larger, to accommodate the arbitrary
        // latency introduced by BatteryExternalStatsWorker.scheduleReadProcStateCpuTimes
        assertValueRange(errorPrefix, actualValueMs,
                expectedValueMs - 1000,
                expectedValueMs + 5000);
    }

    private void assertValueRange(String errorPrefix,
            long actualvalue, double minValue, double maxValue) {
        final String errorMsg = String.format(errorPrefix + "; actual=%s; min=%s; max=%s",
                actualvalue, minValue, maxValue);
        assertTrue(errorMsg, actualvalue < maxValue);
        assertTrue(errorMsg, actualvalue > minValue);
    }

    private void doSomeWork() throws Exception {
        final ICmdReceiver receiver = ICmdReceiver.Stub.asInterface(startActivity());
        receiver.doSomeWork(WORK_DURATION_MS);
        receiver.finishHost();
    }

    private void doSomeWorkInIsolatedProcess() throws Exception {
        final ICmdReceiver receiver = ICmdReceiver.Stub.asInterface(startIsolatedService());
        receiver.doSomeWork(WORK_DURATION_MS);
        receiver.finishHost();
    }

    private void doSomeWork(int procState) throws Exception {
        final ICmdReceiver receiver;
        switch (procState) {
            case PROCESS_STATE_TOP:
                receiver = ICmdReceiver.Stub.asInterface(startActivity());
                break;
            case PROCESS_STATE_TOP_SLEEPING:
                receiver = ICmdReceiver.Stub.asInterface(startActivity());
                break;
            case PROCESS_STATE_FOREGROUND_SERVICE:
                receiver = ICmdReceiver.Stub.asInterface(startForegroundService());
                break;
            case PROCESS_STATE_FOREGROUND:
                receiver = ICmdReceiver.Stub.asInterface(startService());
                receiver.showApplicationOverlay();
                break;
            case PROCESS_STATE_BACKGROUND:
                receiver = ICmdReceiver.Stub.asInterface(startService());
                break;
            case PROCESS_STATE_CACHED:
                receiver = ICmdReceiver.Stub.asInterface(startActivity());
                receiver.finishHost();
                break;
            default:
                throw new IllegalArgumentException("Unknown state: " + procState);
        }
        try {
            assertProcState(procState);
            receiver.doSomeWork(WORK_DURATION_MS);
        } finally {
            receiver.finishHost();
        }
    }

    private void assertProcState(String state) throws Exception {
        final String expectedState = "(" + state + ")";
        assertDelayedCondition("", () -> {
            final String uidStateStr = executeCmd("cmd activity get-uid-state " + sTestPkgUid);
            final String actualState = uidStateStr.split(" ")[1];
            return expectedState.equals(actualState) ? null
                    : "expected=" + expectedState + ", actual" + actualState;
        });
    }

    private void assertProcState(int expectedState) throws Exception {
        assertDelayedCondition("Unexpected proc state", () -> {
            final String uidStateStr = executeCmd("cmd activity get-uid-state " + sTestPkgUid);
            final int amProcState = Integer.parseInt(uidStateStr.split(" ")[0]);
            final int actualState = BatteryStats.mapToInternalProcessState(amProcState);
            return (actualState == expectedState) ? null
                    : "expected=" + getStateName(BatteryStats.Uid.class, expectedState)
                            + ", actual=" + getStateName(BatteryStats.Uid.class, actualState)
                            + ", amState=" + getStateName(ActivityManager.class, amProcState);
        });
    }

    private String getStateName(Class clazz, int procState) {
        return DebugUtils.valueToString(clazz, "PROCESS_STATE_", procState);
    }

    private IBinder startIsolatedService() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] binders = new IBinder[1];
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binders[0] = service;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        final Intent launchIntent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, ISOLATED_TEST_SERVICE));
        sContext.bindService(launchIntent, connection, Context.BIND_AUTO_CREATE
                | Context.BIND_ALLOW_OOM_MANAGEMENT | Context.BIND_NOT_FOREGROUND);
        if (latch.await(START_ISOLATED_SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (binders[0] == null) {
                fail("Receiver binder should not be null");
            }
            return binders[0];
        } else {
            fail("Timed out waiting for the isolated test service to start");
        }
        return null;
    }

    private IBinder startForegroundService() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, TEST_SERVICE))
                .setFlags(FLAG_START_FOREGROUND);
        final Bundle extras = new Bundle();
        final IBinder[] binders = new IBinder[1];
        extras.putBinder(EXTRA_KEY_CMD_RECEIVER, new ICmdCallback.Stub() {
            @Override
            public void onLaunched(IBinder receiver) {
                binders[0] = receiver;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras);
        sContext.startForegroundService(launchIntent);
        if (latch.await(START_FG_SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (binders[0] == null) {
                fail("Receiver binder should not be null");
            }
            return binders[0];
        } else {
            fail("Timed out waiting for the test fg service to start; testUid=" + sTestPkgUid);
        }
        return null;
    }

    private IBinder startService() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, TEST_SERVICE));
        final Bundle extras = new Bundle();
        final IBinder[] binders = new IBinder[1];
        extras.putBinder(EXTRA_KEY_CMD_RECEIVER, new ICmdCallback.Stub() {
            @Override
            public void onLaunched(IBinder receiver) {
                binders[0] = receiver;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras);
        sContext.startService(launchIntent);
        if (latch.await(START_SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (binders[0] == null) {
                fail("Receiver binder should not be null");
            }
            return binders[0];
        } else {
            fail("Timed out waiting for the test service to start; testUid=" + sTestPkgUid);
        }
        return null;
    }

    private IBinder startActivity() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, TEST_ACTIVITY));
        final Bundle extras = new Bundle();
        final IBinder[] binders = new IBinder[1];
        extras.putBinder(EXTRA_KEY_CMD_RECEIVER, new ICmdCallback.Stub() {
            @Override
            public void onLaunched(IBinder receiver) {
                binders[0] = receiver;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sContext.startActivity(launchIntent);
        if (latch.await(START_ACTIVITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (binders[0] == null) {
                fail("Receiver binder should not be null");
            }
            return binders[0];
        } else {
            fail("Timed out waiting for the test activity to start; testUid=" + sTestPkgUid);
        }
        return null;
    }

    private static String getAllCpuTimesMsg() throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append("uid=" + sTestPkgUid + ";");
        sb.append(UID_TIMES_TYPE_ALL + "=" + getMsgCpuTimesSum(getAllCpuFreqTimes(sTestPkgUid)));
        for (int i = 0; i < NUM_PROCESS_STATE; ++i) {
            sb.append("|");
            sb.append(UID_PROCESS_TYPES[i] + "="
                    + getMsgCpuTimesSum(getAllCpuFreqTimes(sTestPkgUid, i)));
        }
        return sb.toString();
    }

    private static String getMsgCpuTimesSum(long[] cpuTimes) throws Exception {
        if (cpuTimes == null) {
            return "(0,0)";
        }
        long totalTime = 0;
        for (int i = 0; i < cpuTimes.length / 2; ++i) {
            totalTime += cpuTimes[i];
        }
        long screenOffTime = 0;
        for (int i = cpuTimes.length / 2; i < cpuTimes.length; ++i) {
            screenOffTime += cpuTimes[i];
        }
        return "(" + totalTime + "," + screenOffTime + ")";
    }

    private static long[] getAllCpuFreqTimes(int uid) throws Exception {
        final String checkinDump = executeCmdSilent("dumpsys batterystats --checkin");
        final Pattern pattern = Pattern.compile(uid + ",l,ctf," + UID_TIMES_TYPE_ALL + ",(.*?)\n");
        final Matcher matcher = pattern.matcher(checkinDump);
        if (!matcher.find()) {
            return null;
        }
        return parseCpuTimesStr(matcher.group(1));
    }

    private static long[] getAllCpuFreqTimes(int uid, int procState) throws Exception {
        final String checkinDump = executeCmdSilent("dumpsys batterystats --checkin");
        final Pattern pattern = Pattern.compile(
                uid + ",l,ctf," + UID_PROCESS_TYPES[procState] + ",(.*?)\n");
        final Matcher matcher = pattern.matcher(checkinDump);
        if (!matcher.find()) {
            return null;
        }
        return parseCpuTimesStr(matcher.group(1));
    }

    private static long[] parseCpuTimesStr(String str) {
        final String[] cpuTimesStr = str.split(",");
        final int freqCount = Integer.parseInt(cpuTimesStr[0]);
        if (cpuTimesStr.length != (2 * freqCount + 1)) {
            fail("Malformed data: " + Arrays.toString(cpuTimesStr));
        }
        final long[] cpuTimes = new long[freqCount * 2];
        for (int i = 0; i < cpuTimes.length; ++i) {
            cpuTimes[i] = Long.parseLong(cpuTimesStr[i + 1]);
        }
        return cpuTimes;
    }

    private void resetBatteryStats() throws Exception {
        executeCmd("dumpsys batterystats --reset");
    }

    private void batteryOnScreenOn() throws Exception {
        batteryOn();
        screenOn();
    }

    private void batteryOnScreenOff() throws Exception {
        batteryOn();
        screenoff();
    }

    private void batteryOffScreenOn() throws Exception {
        batteryOff();
        screenOn();
    }

    private static void batteryOn() throws Exception {
        executeCmd("dumpsys battery unplug");
        assertBatteryState(false /* pluggedIn */);
    }

    private static void batteryOff() throws Exception {
        executeCmd("dumpsys battery set ac " + BatteryManager.BATTERY_PLUGGED_AC);
        assertBatteryState(true /* pluggedIn */);
    }

    private static void batteryReset() throws Exception {
        executeCmd("dumpsys battery reset");
    }

    private void screenOn() throws Exception {
        executeCmd("input keyevent KEYCODE_WAKEUP");
        executeCmd("wm dismiss-keyguard");
        assertKeyguardUnLocked();
        assertScreenInteractive(true);
    }

    private void screenoff() throws Exception {
        executeCmd("input keyevent KEYCODE_SLEEP");
        assertScreenInteractive(false);
    }

    private void forceStop() throws Exception {
        executeCmd("cmd activity force-stop " + TEST_PKG);
        assertProcState("NONEXISTENT");
    }

    private void assertKeyguardUnLocked() throws Exception {
        final KeyguardManager keyguardManager =
                (KeyguardManager) sContext.getSystemService(Context.KEYGUARD_SERVICE);
        assertDelayedCondition("Unexpected Keyguard state", () ->
                keyguardManager.isKeyguardLocked() ? "expected=unlocked" : null
        );
    }

    private void assertScreenInteractive(boolean interactive) throws Exception {
        final PowerManager powerManager =
                (PowerManager) sContext.getSystemService(Context.POWER_SERVICE);
        assertDelayedCondition("Unexpected screen interactive state", () ->
                interactive == powerManager.isInteractive() ? null : "expected=" + interactive
        );
    }

    private void assertDelayedCondition(String errMsgPrefix, ExpectedCondition condition)
        throws Exception {
        assertDelayedCondition(errMsgPrefix, condition, GENERAL_TIMEOUT_MS, GENERAL_INTERVAL_MS);
    }

    private void assertDelayedCondition(String errMsgPrefix, ExpectedCondition condition,
            long timeoutMs, long checkIntervalMs) throws Exception {
        final long endTime = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() <= endTime) {
            if (condition.getErrIfNotTrue() == null) {
                return;
            }
            SystemClock.sleep(checkIntervalMs);
        }
        final String errMsg = condition.getErrIfNotTrue();
        if (errMsg != null) {
            fail(errMsgPrefix + ": " + errMsg);
        }
    }

    private static void assertBatteryState(boolean pluggedIn) throws Exception {
        final long endTime = SystemClock.uptimeMillis() + BATTERY_STATE_TIMEOUT_MS;
        while (isDevicePluggedIn() != pluggedIn && SystemClock.uptimeMillis() <= endTime) {
            Thread.sleep(BATTERY_STATE_CHECK_INTERVAL_MS);
        }
        if (isDevicePluggedIn() != pluggedIn) {
            fail("Timed out waiting for the plugged-in state to change,"
                    + " expected pluggedIn: " + pluggedIn);
        }
    }

    private static boolean isDevicePluggedIn() {
        final Intent batteryIntent = sContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0;
    }

    private static String executeCmd(String cmd) throws Exception {
        final String result = sUiDevice.executeShellCommand(cmd).trim();
        Log.d(TAG, String.format("Result for '%s': %s", cmd, result));
        return result;
    }

    private static String executeCmdSilent(String cmd) throws Exception {
        return sUiDevice.executeShellCommand(cmd).trim();
    }

    private interface ExpectedCondition {
        String getErrIfNotTrue() throws Exception;
    }
}
