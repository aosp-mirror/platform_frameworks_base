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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.junit.Assume.assumeTrue;

import com.android.frameworks.coretests.aidl.ICmdCallback;
import com.android.frameworks.coretests.aidl.ICmdReceiver;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class BstatsCpuTimesValidationTest {
    private static final String TAG = BstatsCpuTimesValidationTest.class.getName();

    private static final String TEST_PKG = "com.android.coretests.apps.bstatstestapp";
    private static final String TEST_ACTIVITY = TEST_PKG + ".TestActivity";
    private static final String ISOLATED_TEST_SERVICE = TEST_PKG + ".IsolatedTestService";

    private static final String EXTRA_KEY_CMD_RECEIVER = "cmd_receiver";

    private static final int BATTERY_STATE_TIMEOUT_MS = 2000;
    private static final int BATTERY_STATE_CHECK_INTERVAL_MS = 200;

    private static final int START_ACTIVITY_TIMEOUT_MS = 2000;
    private static final int START_ISOLATED_SERVICE_TIMEOUT_MS = 2000;

    private static final int GENERAL_TIMEOUT_MS = 1000;
    private static final int GENERAL_INTERVAL_MS = 100;

    private static final int WORK_DURATION_MS = 2000;

    private static Context sContext;
    private static UiDevice sUiDevice;
    private static int sTestPkgUid;
    private static boolean sCpuFreqTimesAvailable;

    @BeforeClass
    public static void setupOnce() throws Exception {
        sContext = InstrumentationRegistry.getContext();
        sUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        sContext.getPackageManager().setApplicationEnabledSetting(TEST_PKG,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        sTestPkgUid = sContext.getPackageManager().getPackageUid(TEST_PKG, 0);
        sCpuFreqTimesAvailable = cpuFreqTimesAvailable();
    }

    // Checks cpu freq times of system uid as an indication of whether /proc/uid_time_in_state
    // kernel node is available.
    private static boolean cpuFreqTimesAvailable() throws Exception {
        final long[] cpuTimes = getAllCpuFreqTimes(Process.SYSTEM_UID);
        return cpuTimes != null;
    }

    @Test
    public void testCpuFreqTimes() throws Exception {
        if (!sCpuFreqTimesAvailable) {
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null", initialSnapshot);
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
            return;
        }

        batteryOnScreenOff();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null", initialSnapshot);
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
            return;
        }

        batteryOnScreenOn();
        forceStop();
        resetBatteryStats();
        final long[] initialSnapshot = getAllCpuFreqTimes(sTestPkgUid);
        assertNull("Initial snapshot should be null", initialSnapshot);
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

    private void assertApproximateValue(String errorPrefix, long expectedValue, long actualValue) {
        assertValueRange(errorPrefix, actualValue, expectedValue * 0.5, expectedValue * 1.5);
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

    private IBinder startActivity() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, TEST_ACTIVITY));
        final Bundle extras = new Bundle();
        final IBinder[] binders = new IBinder[1];
        extras.putBinder(EXTRA_KEY_CMD_RECEIVER, new ICmdCallback.Stub() {
            @Override
            public void onActivityLaunched(IBinder receiver) {
                binders[0] = receiver;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras);
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

    private static long[] getAllCpuFreqTimes(int uid) throws Exception {
        final String checkinDump = executeCmdSilent("dumpsys batterystats --checkin");
        final Pattern pattern = Pattern.compile(uid + ",l,ctf,A,(.*?)\n");
        final Matcher matcher = pattern.matcher(checkinDump);
        if (!matcher.find()) {
            return null;
        }
        final String[] uidTimesStr = matcher.group(1).split(",");
        final int freqCount = Integer.parseInt(uidTimesStr[0]);
        if (uidTimesStr.length != (2 * freqCount + 1)) {
            fail("Malformed data: " + Arrays.toString(uidTimesStr));
        }
        final long[] cpuTimes = new long[freqCount * 2];
        for (int i = 0; i < cpuTimes.length; ++i) {
            cpuTimes[i] = Long.parseLong(uidTimesStr[i + 1]);
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

    private void batteryOn() throws Exception {
        executeCmd("dumpsys battery unplug");
        assertBatteryState(false);
    }

    private void batteryOff() throws Exception {
        executeCmd("dumpsys battery reset");
        assertBatteryState(true);
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
        assertUidState(PROCESS_STATE_NONEXISTENT);
    }

    private void assertUidState(int state) throws Exception {
        final String uidStateStr = executeCmd("cmd activity get-uid-state " + sTestPkgUid);
        final int uidState = Integer.parseInt(uidStateStr.split(" ")[0]);
        assertEquals(state, uidState);
    }

    private void assertKeyguardUnLocked() {
        final KeyguardManager keyguardManager =
                (KeyguardManager) sContext.getSystemService(Context.KEYGUARD_SERVICE);
        assertDelayedCondition("Keyguard should be unlocked",
                () -> !keyguardManager.isKeyguardLocked());
    }

    private void assertScreenInteractive(boolean interactive) {
        final PowerManager powerManager =
                (PowerManager) sContext.getSystemService(Context.POWER_SERVICE);
        assertDelayedCondition("Unexpected screen interactive state",
                () -> interactive == powerManager.isInteractive());
    }

    private void assertDelayedCondition(String errorMsg, ExpectedCondition condition) {
        final long endTime = SystemClock.uptimeMillis() + GENERAL_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() <= endTime) {
            if (condition.isTrue()) {
                return;
            }
            SystemClock.sleep(GENERAL_INTERVAL_MS);
        }
        if (!condition.isTrue()) {
            fail(errorMsg);
        }
    }

    private void assertBatteryState(boolean pluggedIn) throws Exception {
        final long endTime = SystemClock.uptimeMillis() + BATTERY_STATE_TIMEOUT_MS;
        while (isDevicePluggedIn() != pluggedIn && SystemClock.uptimeMillis() <= endTime) {
            Thread.sleep(BATTERY_STATE_CHECK_INTERVAL_MS);
        }
        if (isDevicePluggedIn() != pluggedIn) {
            fail("Timed out waiting for the plugged-in state to change,"
                    + " expected pluggedIn: " + pluggedIn);
        }
    }

    private boolean isDevicePluggedIn() {
        final Intent batteryIntent = sContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0;
    }

    private String executeCmd(String cmd) throws Exception {
        final String result = sUiDevice.executeShellCommand(cmd).trim();
        Log.d(TAG, String.format("Result for '%s': %s", cmd, result));
        return result;
    }

    private static String executeCmdSilent(String cmd) throws Exception {
        return sUiDevice.executeShellCommand(cmd).trim();
    }

    private interface ExpectedCondition {
        boolean isTrue();
    }
}
