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

import static android.util.DebugUtils.valueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.frameworks.servicestests.R;
import com.android.servicestests.aidl.INetworkStateObserver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import libcore.io.IoUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
@Ignore
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ConnOnActivityStartTest {
    private static final String TAG = ConnOnActivityStartTest.class.getSimpleName();

    private static final String ACTION_INSTALL_COMPLETE = "com.android.server.net.INSTALL_COMPLETE";

    private static final String TEST_APP_URI =
            "android.resource://com.android.frameworks.servicestests/raw/conntestapp";
    private static final String TEST_PKG = "com.android.servicestests.apps.conntestapp";
    private static final String TEST_ACTIVITY_CLASS = TEST_PKG + ".ConnTestActivity";

    private static final String ACTION_FINISH_ACTIVITY = TEST_PKG + ".FINISH";

    private static final String EXTRA_NETWORK_STATE_OBSERVER = TEST_PKG + ".observer";

    private static final long BATTERY_OFF_TIMEOUT_MS = 2000; // 2 sec
    private static final long BATTERY_OFF_CHECK_INTERVAL_MS = 200; // 0.2 sec

    private static final long WAIT_FOR_INSTALL_TIMEOUT_MS = 2000; // 2 sec

    private static final long NETWORK_CHECK_TIMEOUT_MS = 6000; // 6 sec

    private static final long SCREEN_ON_DELAY_MS = 500; // 0.5 sec

    private static final String NETWORK_STATUS_SEPARATOR = "\\|";

    private static final int REPEAT_TEST_COUNT = 5;

    private static Context mContext;
    private static UiDevice mUiDevice;
    private static int mTestPkgUid;
    private static BatteryManager mBatteryManager;
    private static ConnectivityManager mConnectivityManager;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        installAppAndAssertInstalled();
        mContext.getPackageManager().setApplicationEnabledSetting(TEST_PKG,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        mTestPkgUid = mContext.getPackageManager().getPackageUid(TEST_PKG, 0);

        mBatteryManager = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
    }

    @AfterClass
    public static void tearDownOnce() {
        mContext.getPackageManager().deletePackage(TEST_PKG,
                new IPackageDeleteObserver.Stub() {
                    @Override
                    public void packageDeleted(String packageName, int returnCode)
                            throws RemoteException {
                        Log.e(TAG, packageName + " deleted, returnCode: " + returnCode);
                    }
                }, 0);
    }

    @Test
    public void testStartActivity_batterySaver() throws Exception {
        if (!isNetworkAvailable()) {
            fail("Device doesn't have network connectivity");
        }
        setBatterySaverMode(true);
        try {
            testConnOnActivityStart("testStartActivity_batterySaver");
        } finally {
            setBatterySaverMode(false);
        }
    }

    @Test
    public void testStartActivity_dataSaver() throws Exception {
        if (!isNetworkAvailable()) {
            fail("Device doesn't have network connectivity");
        }
        setDataSaverMode(true);
        try {
            testConnOnActivityStart("testStartActivity_dataSaver");
        } finally {
            setDataSaverMode(false);
        }
    }

    @Test
    public void testStartActivity_dozeMode() throws Exception {
        if (!isNetworkAvailable()) {
            fail("Device doesn't have network connectivity");
        }
        setDozeMode(true);
        try {
            testConnOnActivityStart("testStartActivity_dozeMode");
        } finally {
            setDozeMode(false);
        }
    }

    @Test
    public void testStartActivity_appStandby() throws Exception {
        if (!isNetworkAvailable()) {
            fail("Device doesn't have network connectivity");
        }
        try{
            turnBatteryOff();
            setAppIdle(true);
            SystemClock.sleep(30000);
            turnScreenOn();
            startActivityAndCheckNetworkAccess();
        } finally {
            turnBatteryOn();
            setAppIdle(false);
        }
    }

    @Test
    public void testStartActivity_backgroundRestrict() throws Exception {
        if (!isNetworkAvailable()) {
            fail("Device doesn't have network connectivity");
        }
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
                SystemClock.sleep(SCREEN_ON_DELAY_MS);
                startActivityAndCheckNetworkAccess();
                Log.d(TAG, testName + " end #" + i);
            } finally {
                finishActivity();
            }
        }
    }

    // TODO: Some of these methods are also used in CTS, so instead of duplicating code,
    // create a static library which can be used by both servicestests and cts.
    private void setBatterySaverMode(boolean enabled) throws Exception {
        if (enabled) {
            turnBatteryOff();
            executeCommand("settings put global low_power 1");
        } else {
            executeCommand("settings put global low_power 0");
            turnBatteryOn();
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
            turnBatteryOff();
            turnScreenOff();
            executeCommand("dumpsys deviceidle force-idle deep");
        } else {
            turnScreenOn();
            turnBatteryOn();
            executeCommand("dumpsys deviceidle unforce");
        }
        assertDelayedCommandResult("dumpsys deviceidle get deep", enabled ? "IDLE" : "ACTIVE",
                5 /* maxTries */, 500 /* napTimeMs */);
    }

    private void setAppIdle(boolean enabled) throws Exception {
        executeCommand("am set-inactive " + TEST_PKG + " " + enabled);
        assertDelayedCommandResult("am get-inactive " + TEST_PKG, "Idle=" + enabled,
                10 /* maxTries */, 2000 /* napTimeMs */);
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

    private void turnBatteryOff() throws Exception {
        executeCommand("cmd battery unplug");
        assertBatteryOff();
    }

    private void assertBatteryOff() throws Exception {
        final long endTime = SystemClock.uptimeMillis() + BATTERY_OFF_TIMEOUT_MS;
        while (mBatteryManager.isCharging() && SystemClock.uptimeMillis() < endTime) {
            SystemClock.sleep(BATTERY_OFF_CHECK_INTERVAL_MS);
        }
        assertFalse("Power should be disconnected", mBatteryManager.isCharging());
    }

    private void turnBatteryOn() throws Exception {
        executeCommand("cmd battery reset");
    }

    private void turnScreenOff() throws Exception {
        executeCommand("input keyevent KEYCODE_SLEEP");
    }

    private void turnScreenOn() throws Exception {
        executeCommand("input keyevent KEYCODE_WAKEUP");
        executeCommand("wm dismiss-keyguard");
    }

    private String executeCommand(String cmd) throws IOException {
        final String result = mUiDevice.executeShellCommand(cmd).trim();
        Log.d(TAG, String.format("Result for '%s': %s", cmd, result));
        return result;
    }

    private void assertDelayedCommandResult(String cmd, String expectedResult,
            int maxTries, int napTimeMs) throws IOException {
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

    private boolean isNetworkAvailable() throws Exception {
        final NetworkInfo networkInfo = mConnectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
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
                errors[0] = checkForAvailability(resultData);
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras);
        mContext.startActivity(launchIntent);
        if (latch.await(NETWORK_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (!errors[0].isEmpty()) {
                fail("Network not available for test app " + mTestPkgUid);
            }
        } else {
            fail("Timed out waiting for network availability status from test app " + mTestPkgUid);
        }
    }

    private void finishActivity() {
        final Intent finishIntent = new Intent(ACTION_FINISH_ACTIVITY)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcast(finishIntent);
    }

    private String checkForAvailability(String resultData) {
        if (resultData == null) {
            assertNotNull("Network status from app2 is null, Uid: " + mTestPkgUid, resultData);
        }
        // Network status format is described on MyBroadcastReceiver.checkNetworkStatus()
        final String[] parts = resultData.split(NETWORK_STATUS_SEPARATOR);
        assertEquals("Wrong network status: " + resultData + ", Uid: " + mTestPkgUid,
                5, parts.length); // Sanity check
        final NetworkInfo.State state = parts[0].equals("null")
                ? null : NetworkInfo.State.valueOf(parts[0]);
        final NetworkInfo.DetailedState detailedState = parts[1].equals("null")
                ? null : NetworkInfo.DetailedState.valueOf(parts[1]);
        final boolean connected = Boolean.valueOf(parts[2]);
        final String connectionCheckDetails = parts[3];
        final String networkInfo = parts[4];

        final StringBuilder errors = new StringBuilder();
        final NetworkInfo.State expectedState = NetworkInfo.State.CONNECTED;
        final NetworkInfo.DetailedState expectedDetailedState = NetworkInfo.DetailedState.CONNECTED;

        if (true != connected) {
            errors.append(String.format("External site connection failed: expected %s, got %s\n",
                    true, connected));
        }
        if (expectedState != state || expectedDetailedState != detailedState) {
            errors.append(String.format("Connection state mismatch: expected %s/%s, got %s/%s\n",
                    expectedState, expectedDetailedState, state, detailedState));
        }

        if (errors.length() > 0) {
            errors.append("\tnetworkInfo: " + networkInfo + "\n");
            errors.append("\tconnectionCheckDetails: " + connectionCheckDetails + "\n");
        }
        return errors.toString();
    }

    private static void installAppAndAssertInstalled() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] result = {PackageInstaller.STATUS_SUCCESS};
        final BroadcastReceiver installStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                if (!TEST_PKG.equals(pkgName)) {
                    return;
                }
                result[0] = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                latch.countDown();
            }
        };
        mContext.registerReceiver(installStatusReceiver, new IntentFilter(ACTION_INSTALL_COMPLETE));
        try {
            installApp();
            if (latch.await(WAIT_FOR_INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (result[0] != PackageInstaller.STATUS_SUCCESS) {
                    fail("Couldn't install test app, result: "
                            + valueToString(PackageInstaller.class, "STATUS_", result[0]));
                }
            } else {
                fail("Timed out waiting for the test app to install");
            }
        } finally {
            mContext.unregisterReceiver(installStatusReceiver);
        }
    }

    private static void installApp() throws Exception {
        final Uri packageUri = Uri.parse(TEST_APP_URI);
        final InputStream in = mContext.getContentResolver().openInputStream(packageUri);

        final PackageInstaller packageInstaller
                = mContext.getPackageManager().getPackageInstaller();
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(TEST_PKG);

        final int sessionId = packageInstaller.createSession(params);
        final PackageInstaller.Session session = packageInstaller.openSession(sessionId);

        OutputStream out = null;
        try {
            out = session.openWrite(TAG, 0, -1);
            final byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                out.write(buffer, 0, c);
            }
            session.fsync(out);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
        session.commit(createIntentSender(mContext, sessionId));
    }

    private static IntentSender createIntentSender(Context context, int sessionId) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, new Intent(ACTION_INSTALL_COMPLETE), 0);
        return pendingIntent.getIntentSender();
    }
}