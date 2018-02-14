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

package com.android.server.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.frameworks.servicestests.R;
import com.android.servicestests.aidl.ICmdReceiverService;
import com.android.servicestests.aidl.INetworkStateObserver;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;
import android.text.TextUtils;
import android.util.Log;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for verifying network availability on activity start.
 *
 * To run the tests, use
 *
 * runtest -c com.android.server.net.ConnOnActivityStartTest frameworks-services
 *
 * or the following steps:
 *
 * Build: m FrameworksServicesTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -e class com.android.server.net.ConnOnActivityStartTest -w \
 *     com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ConnOnActivityStartTest {
    private static final String TAG = ConnOnActivityStartTest.class.getSimpleName();

    private static final String TEST_PKG = "com.android.servicestests.apps.conntestapp";
    private static final String TEST_ACTIVITY_CLASS = TEST_PKG + ".ConnTestActivity";
    private static final String TEST_SERVICE_CLASS = TEST_PKG + ".CmdReceiverService";

    private static final String EXTRA_NETWORK_STATE_OBSERVER = TEST_PKG + ".observer";

    private static final long BATTERY_OFF_TIMEOUT_MS = 2000; // 2 sec
    private static final long BATTERY_OFF_CHECK_INTERVAL_MS = 200; // 0.2 sec

    private static final long NETWORK_CHECK_TIMEOUT_MS = 4000; // 4 sec

    private static final long SCREEN_ON_DELAY_MS = 2000; // 2 sec

    private static final long BIND_SERVICE_TIMEOUT_SEC = 4;

    private static final int REPEAT_TEST_COUNT = 5;

    private static final String KEY_PAROLE_DURATION = "parole_duration";
    private static final String DESIRED_PAROLE_DURATION = "0";

    private static Context mContext;
    private static UiDevice mUiDevice;
    private static int mTestPkgUid;
    private static BatteryManager mBatteryManager;

    private static boolean mAppIdleConstsUpdated;
    private static String mOriginalAppIdleConsts;

    private static ServiceConnection mServiceConnection;
    private static ICmdReceiverService mCmdReceiverService;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        setDesiredParoleDuration();
        mContext.getPackageManager().setApplicationEnabledSetting(TEST_PKG,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        mTestPkgUid = mContext.getPackageManager().getPackageUid(TEST_PKG, 0);

        mBatteryManager = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
        bindService();
    }

    @AfterClass
    public static void tearDownOnce() throws Exception {
        batteryReset();
        if (mAppIdleConstsUpdated) {
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.APP_IDLE_CONSTANTS, mOriginalAppIdleConsts);
        }
        unbindService();
    }

    private static void bindService() throws Exception {
        final CountDownLatch bindLatch = new CountDownLatch(1);
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "Service connected");
                mCmdReceiverService = ICmdReceiverService.Stub.asInterface(service);
                bindLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "Service disconnected");
            }
        };
        final Intent intent = new Intent()
                .setComponent(new ComponentName(TEST_PKG, TEST_SERVICE_CLASS));
        // Needs to use BIND_ALLOW_OOM_MANAGEMENT and BIND_NOT_FOREGROUND so that the test app
        // does not run in the same process state as this app.
        mContext.bindService(intent, mServiceConnection,
                Context.BIND_AUTO_CREATE
                        | Context.BIND_ALLOW_OOM_MANAGEMENT
                        | Context.BIND_NOT_FOREGROUND);
        if (!bindLatch.await(BIND_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the service to bind in " + mTestPkgUid);
        }
    }

    private static void unbindService() {
        if (mCmdReceiverService != null) {
            mContext.unbindService(mServiceConnection);
        }
    }

    private static void setDesiredParoleDuration() {
        mOriginalAppIdleConsts = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.APP_IDLE_CONSTANTS);
        String newAppIdleConstants;
        final String newConstant = KEY_PAROLE_DURATION + "=" + DESIRED_PAROLE_DURATION;
        if (mOriginalAppIdleConsts == null || "null".equals(mOriginalAppIdleConsts)) {
            // app_idle_constants is initially empty, so just assign the desired value.
            newAppIdleConstants = newConstant;
        } else if (mOriginalAppIdleConsts.contains(KEY_PAROLE_DURATION)) {
            // app_idle_constants contains parole_duration, so replace it with the desired value.
            newAppIdleConstants = mOriginalAppIdleConsts.replaceAll(
                    KEY_PAROLE_DURATION + "=\\d+", newConstant);
        } else {
            // app_idle_constants didn't have parole_duration, so append the desired value.
            newAppIdleConstants = mOriginalAppIdleConsts + "," + newConstant;
        }
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.APP_IDLE_CONSTANTS, newAppIdleConstants);
        mAppIdleConstsUpdated = true;
    }

    @Test
    public void testStartActivity_batterySaver() throws Exception {
        setBatterySaverMode(true);
        try {
            testConnOnActivityStart("testStartActivity_batterySaver");
        } finally {
            setBatterySaverMode(false);
        }
    }

    @Test
    public void testStartActivity_dataSaver() throws Exception {
        setDataSaverMode(true);
        try {
            testConnOnActivityStart("testStartActivity_dataSaver");
        } finally {
            setDataSaverMode(false);
        }
    }

    @Test
    public void testStartActivity_dozeMode() throws Exception {
        setDozeMode(true);
        try {
            testConnOnActivityStart("testStartActivity_dozeMode");
        } finally {
            setDozeMode(false);
        }
    }

    @Test
    public void testStartActivity_appStandby() throws Exception {
        try{
            turnBatteryOn();
            setAppIdle(true);
            turnScreenOn();
            startActivityAndCheckNetworkAccess();
        } finally {
            turnBatteryOff();
            finishActivity();
            setAppIdle(false);
        }
    }

    @Test
    public void testStartActivity_backgroundRestrict() throws Exception {
        updateRestrictBackgroundBlacklist(true);
        try {
            testConnOnActivityStart("testStartActivity_backgroundRestrict");
        } finally {
            updateRestrictBackgroundBlacklist(false);
        }
    }

    private void testConnOnActivityStart(String testName) throws Exception {
        for (int i = 1; i <= REPEAT_TEST_COUNT; ++i) {
            try {
                Log.d(TAG, testName + " Start #" + i);
                turnScreenOn();
                startActivityAndCheckNetworkAccess();
            } finally {
                finishActivity();
                Log.d(TAG, testName + " end #" + i);
            }
        }
    }

    // TODO: Some of these methods are also used in CTS, so instead of duplicating code,
    // create a static library which can be used by both servicestests and cts.
    private void setBatterySaverMode(boolean enabled) throws Exception {
        if (enabled) {
            turnBatteryOn();
            executeCommand("settings put global low_power 1");
        } else {
            executeCommand("settings put global low_power 0");
            turnBatteryOff();
        }
        final String result = executeCommand("settings get global low_power");
        assertEquals(enabled ? "1" : "0", result);
    }

    private void setDataSaverMode(boolean enabled) throws Exception {
        executeCommand("cmd netpolicy set restrict-background " + enabled);
        final String output = executeCommand("cmd netpolicy get restrict-background");
        final String expectedSuffix = enabled ? "enabled" : "disabled";
        assertTrue("output '" + output + "' should end with '" + expectedSuffix + "'",
                output.endsWith(expectedSuffix));
    }

    private void setDozeMode(boolean enabled) throws Exception {
        if (enabled) {
            turnBatteryOn();
            turnScreenOff();
            executeCommand("dumpsys deviceidle force-idle deep");
        } else {
            turnScreenOn();
            turnBatteryOff();
            executeCommand("dumpsys deviceidle unforce");
        }
        assertDelayedCommandResult("dumpsys deviceidle get deep", enabled ? "IDLE" : "ACTIVE",
                5 /* maxTries */, 500 /* napTimeMs */);
    }

    private void setAppIdle(boolean enabled) throws Exception {
        executeCommand("am set-inactive " + TEST_PKG + " " + enabled);
        assertDelayedCommandResult("am get-inactive " + TEST_PKG, "Idle=" + enabled,
                15 /* maxTries */, 2000 /* napTimeMs */);
    }

    private void updateRestrictBackgroundBlacklist(boolean add) throws Exception {
        if (add) {
            executeCommand("cmd netpolicy add restrict-background-blacklist " + mTestPkgUid);
        } else {
            executeCommand("cmd netpolicy remove restrict-background-blacklist " + mTestPkgUid);
        }
        assertRestrictBackground("restrict-background-blacklist", mTestPkgUid, add);
    }

    private void assertRestrictBackground(String list, int uid, boolean expected) throws Exception {
        final int maxTries = 5;
        boolean actual = false;
        final String expectedUid = Integer.toString(uid);
        String uids = "";
        for (int i = 1; i <= maxTries; i++) {
            final String output = executeCommand("cmd netpolicy list " + list);
            uids = output.split(":")[1];
            for (String candidate : uids.split(" ")) {
                actual = candidate.trim().equals(expectedUid);
                if (expected == actual) {
                    return;
                }
            }
            Log.v(TAG, list + " check for uid " + uid + " doesn't match yet (expected "
                    + expected + ", got " + actual + "); sleeping 1s before polling again");
            SystemClock.sleep(1000);
        }
        fail(list + " check for uid " + uid + " failed: expected " + expected + ", got " + actual
                + ". Full list: " + uids);
    }

    private void turnBatteryOn() throws Exception {
        executeCommand("cmd battery unplug");
        executeCommand("cmd battery set status " + BatteryManager.BATTERY_STATUS_NOT_CHARGING);
        assertBatteryOn();
    }

    private void assertBatteryOn() throws Exception {
        final long endTime = SystemClock.uptimeMillis() + BATTERY_OFF_TIMEOUT_MS;
        while (mBatteryManager.isCharging() && SystemClock.uptimeMillis() < endTime) {
            SystemClock.sleep(BATTERY_OFF_CHECK_INTERVAL_MS);
        }
        assertFalse("Power should be disconnected", mBatteryManager.isCharging());
    }

    private void turnBatteryOff() throws Exception {
        executeCommand("cmd battery set ac " + BatteryManager.BATTERY_PLUGGED_AC);
        executeCommand("cmd battery set status " + BatteryManager.BATTERY_STATUS_CHARGING);
    }

    private static void batteryReset() throws Exception {
        executeCommand("cmd battery reset");
    }

    private void turnScreenOff() throws Exception {
        executeCommand("input keyevent KEYCODE_SLEEP");
    }

    private void turnScreenOn() throws Exception {
        executeCommand("input keyevent KEYCODE_WAKEUP");
        executeCommand("wm dismiss-keyguard");
        // Wait for screen-on state to propagate through the system.
        SystemClock.sleep(SCREEN_ON_DELAY_MS);
    }

    private static String executeCommand(String cmd) throws IOException {
        final String result = executeSilentCommand(cmd);
        Log.d(TAG, String.format("Result for '%s': %s", cmd, result));
        return result;
    }

    private static String executeSilentCommand(String cmd) throws IOException {
        return mUiDevice.executeShellCommand(cmd).trim();
    }

    private void assertDelayedCommandResult(String cmd, String expectedResult,
            int maxTries, int napTimeMs) throws Exception {
        String result = "";
        for (int i = 1; i <= maxTries; ++i) {
            result = executeCommand(cmd);
            if (expectedResult.equals(result)) {
                return;
            }
            Log.v(TAG, "Command '" + cmd + "' returned '" + result + " instead of '"
                    + expectedResult + "' on attempt #" + i
                    + "; sleeping " + napTimeMs + "ms before trying again");
            SystemClock.sleep(napTimeMs);
        }
        fail("Command '" + cmd + "' did not return '" + expectedResult + "' after "
                + maxTries + " attempts. Last result: '" + result + "'");
    }

    private void startActivityAndCheckNetworkAccess() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent().setComponent(
                new ComponentName(TEST_PKG, TEST_ACTIVITY_CLASS));
        final Bundle extras = new Bundle();
        final String[] errors = new String[] {null};
        extras.putBinder(EXTRA_NETWORK_STATE_OBSERVER, new INetworkStateObserver.Stub() {
            @Override
            public void onNetworkStateChecked(String resultData) {
                errors[0] = resultData;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(launchIntent);
        if (latch.await(NETWORK_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (errors[0] != null) {
                fail("Network not available for test app " + mTestPkgUid + ". " + errors[0]);
            }
        } else {
            fail("Timed out waiting for network availability status from test app " + mTestPkgUid);
        }
    }

    private static void fail(String msg) throws Exception {
        dumpOnFailure();
        Assert.fail(msg);
    }

    private static void dumpOnFailure() throws Exception {
        dump("network_management");
        dump("netpolicy");
        dumpUsageStats();
    }

    private static void dumpUsageStats() throws Exception {
        final String output = executeSilentCommand("dumpsys usagestats");
        final StringBuilder sb = new StringBuilder();
        final TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter('\n');
        splitter.setString(output);
        String str;
        while (splitter.hasNext()) {
            str = splitter.next();
            if (str.contains("package=") && !str.contains(TEST_PKG)) {
                continue;
            }
            if (str.trim().startsWith("config=") || str.trim().startsWith("time=")) {
                continue;
            }
            sb.append(str).append('\n');
        }
        dump("usagestats", sb.toString());
    }

    private static void dump(String service) throws Exception {
        dump(service, executeSilentCommand("dumpsys " + service));
    }

    private static void dump(String service, String dump) throws Exception {
        Log.d(TAG, ">>> Begin dump " + service);
        Log.printlns(Log.LOG_ID_MAIN, Log.DEBUG, TAG, dump, null);
        Log.d(TAG, "<<< End dump " + service);
    }

    private void finishActivity() throws Exception {
        mCmdReceiverService.finishActivity();
    }
}