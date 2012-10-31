/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.connectivitymanagertest.stress;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.IpAssignment;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.ProxySettings;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.connectivitymanagertest.ConnectivityManagerStressTestRunner;
import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Stress Wi-Fi connection, scanning and reconnection after sleep.
 *
 * To run this stress test suite, type
 * adb shell am instrument -e class com.android.connectivitymanagertest.stress.WifiStressTest
 *                  -w com.android.connectivitymanagertest/.ConnectivityManagerStressTestRunner
 */
public class WifiStressTest
    extends ActivityInstrumentationTestCase2<ConnectivityManagerTestActivity> {
    private final static String TAG = "WifiStressTest";

    /**
     * Wi-Fi idle time for default sleep policy
     */
    private final static long WIFI_IDLE_MS = 60 * 1000;

    /**
     * Delay after issuing wifi shutdown.
     * The framework keep driver up for at leat 2 minutes to avoid problems
     * that a quick shutdown could cause on wext driver and protentially
     * on cfg based driver
     */
    private final static long WIFI_SHUTDOWN_DELAY = 2 * 60 * 1000;

    private final static String OUTPUT_FILE = "WifiStressTestOutput.txt";
    private ConnectivityManagerTestActivity mAct;
    private int mReconnectIterations;
    private int mWifiSleepTime;
    private int mScanIterations;
    private String mSsid;
    private String mPassword;
    private ConnectivityManagerStressTestRunner mRunner;
    private BufferedWriter mOutputWriter = null;
    private boolean mWifiOnlyFlag;

    public WifiStressTest() {
        super(ConnectivityManagerTestActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mAct = getActivity();
        mRunner = (ConnectivityManagerStressTestRunner) getInstrumentation();
        mReconnectIterations = mRunner.mReconnectIterations;
        mSsid = mRunner.mReconnectSsid;
        mPassword = mRunner.mReconnectPassword;
        mScanIterations = mRunner.mScanIterations;
        mWifiSleepTime = mRunner.mSleepTime;
        mWifiOnlyFlag = mRunner.mWifiOnlyFlag;
        log(String.format("mReconnectIterations(%d), mSsid(%s), mPassword(%s),"
            + "mScanIterations(%d), mWifiSleepTime(%d)", mReconnectIterations, mSsid,
            mPassword, mScanIterations, mWifiSleepTime));
        mOutputWriter = new BufferedWriter(new FileWriter(new File(
                Environment.getExternalStorageDirectory(), OUTPUT_FILE), true));
        mAct.turnScreenOn();
        if (!mAct.mWifiManager.isWifiEnabled()) {
            log("Enable wi-fi before stress tests.");
            if (!mAct.enableWifi()) {
                tearDown();
                fail("enable wifi failed.");
            }
            sleep(ConnectivityManagerTestActivity.SHORT_TIMEOUT,
                    "Interruped while waiting for wifi on");
        }
    }

    @Override
    public void tearDown() throws Exception {
        log("tearDown()");
        if (mOutputWriter != null) {
            mOutputWriter.close();
        }
        super.tearDown();
    }

    private void writeOutput(String s) {
        log("write message: " + s);
        if (mOutputWriter == null) {
            log("no writer attached to file " + OUTPUT_FILE);
            return;
        }
        try {
            mOutputWriter.write(s + "\n");
            mOutputWriter.flush();
        } catch (IOException e) {
            log("failed to write output.");
        }
    }

    public void log(String message) {
        Log.v(TAG, message);
    }

    private void sleep(long sometime, String errorMsg) {
        try {
            Thread.sleep(sometime);
        } catch (InterruptedException e) {
            fail(errorMsg);
        }
    }

    /**
     *  Stress Wifi Scanning
     *  TODO: test the scanning quality for each frequency band
     */
    @LargeTest
    public void testWifiScanning() {
        int scanTimeSum = 0;
        int i;
        int ssidAppearInScanResultsCount = 0; // count times of given ssid appear in scan results.
        for (i = 0; i < mScanIterations; i++) {
            log("testWifiScanning: iteration: " + i);
            int averageScanTime = 0;
            if (i > 0) {
                averageScanTime = scanTimeSum/i;
            }
            writeOutput(String.format("iteration %d out of %d",
                    i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", averageScanTime));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, i));
            long startTime = System.currentTimeMillis();
            mAct.scanResultAvailable = false;
            assertTrue("start scan failed", mAct.mWifiManager.startScanActive());
            while (true) {
                if ((System.currentTimeMillis() - startTime) >
                ConnectivityManagerTestActivity.WIFI_SCAN_TIMEOUT) {
                    fail("Wifi scanning takes more than " +
                            ConnectivityManagerTestActivity.WIFI_SCAN_TIMEOUT + " ms");
                }
                synchronized(mAct) {
                    try {
                        mAct.wait(ConnectivityManagerTestActivity.WAIT_FOR_SCAN_RESULT);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mAct.scanResultAvailable) {
                        long scanTime = (System.currentTimeMillis() - startTime);
                        scanTimeSum += scanTime;
                        break;
                    }
                }
            }
            if ((mAct.mWifiManager.getScanResults() == null) ||
                    (mAct.mWifiManager.getScanResults().size() <= 0)) {
                fail("Scan results are empty ");
            }

            List<ScanResult> netList = mAct.mWifiManager.getScanResults();
            if (netList != null) {
                log("size of scan result list: " + netList.size());
                for (int s = 0; s < netList.size(); s++) {
                    ScanResult sr= netList.get(s);
                    log(String.format("scan result for %s is: %s", sr.SSID, sr.toString()));
                    log(String.format("signal level for %s is %d ", sr.SSID, sr.level));
                    if (sr.SSID.equals(mSsid)) {
                        ssidAppearInScanResultsCount += 1;
                        log("Number of times " + mSsid + " appear in the scan list: " +
                                ssidAppearInScanResultsCount);
                        break;
                    }
                }
            }
        }
        if (i == mScanIterations) {
            writeOutput(String.format("iteration %d out of %d",
                    i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", scanTimeSum/mScanIterations));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, mScanIterations));
        }
    }

    // Stress Wifi reconnection to secure net after sleep
    @LargeTest
    public void testWifiReconnectionAfterSleep() {
        int value = Settings.Global.getInt(mRunner.getContext().getContentResolver(),
                Settings.Global.WIFI_SLEEP_POLICY, -1);
        log("wifi sleep policy is: " + value);
        if (value != Settings.Global.WIFI_SLEEP_POLICY_DEFAULT) {
            Settings.Global.putInt(mRunner.getContext().getContentResolver(),
                    Settings.Global.WIFI_SLEEP_POLICY, Settings.Global.WIFI_SLEEP_POLICY_DEFAULT);
            log("set wifi sleep policy to default value");
        }
        Settings.Global.putLong(mRunner.getContext().getContentResolver(),
                Settings.Global.WIFI_IDLE_MS, WIFI_IDLE_MS);

        // Connect to a Wi-Fi network
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = mSsid;
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        if (mPassword.matches("[0-9A-Fa-f]{64}")) {
            config.preSharedKey = mPassword;
        } else {
            config.preSharedKey = '"' + mPassword + '"';
        }
        config.ipAssignment = IpAssignment.DHCP;
        config.proxySettings = ProxySettings.NONE;

        assertTrue("Failed to connect to Wi-Fi network: " + mSsid,
                mAct.connectToWifiWithConfiguration(config));
        assertTrue(mAct.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectivityManagerTestActivity.SHORT_TIMEOUT));
        assertTrue(mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                ConnectivityManagerTestActivity.LONG_TIMEOUT));
        // Run ping test to verify the data connection
        assertTrue("Wi-Fi is connected, but no data connection.", mAct.pingTest(null));

        int i;
        long sum = 0;
        for (i = 0; i < mReconnectIterations; i++) {
            // 1. Put device into sleep mode
            // 2. Wait for the device to sleep for sometime, verify wi-fi is off and mobile is on.
            // 3. Maintain the sleep mode for some time,
            // 4. Verify the Wi-Fi is still off, and data is on
            // 5. Wake up the device, verify Wi-Fi is enabled and connected.
            writeOutput(String.format("iteration %d out of %d",
                    i, mReconnectIterations));
            log("iteration: " + i);
            mAct.turnScreenOff();
            PowerManager pm =
                (PowerManager)mRunner.getContext().getSystemService(Context.POWER_SERVICE);
            assertFalse(pm.isScreenOn());
            sleep(WIFI_IDLE_MS + WIFI_SHUTDOWN_DELAY, "Interruped while wait for wifi to be idle");
            assertTrue("Wait for Wi-Fi to idle timeout",
                    mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                    6 * ConnectivityManagerTestActivity.SHORT_TIMEOUT));
            if (!mWifiOnlyFlag) {
                // use long timeout as the pppd startup may take several retries.
                assertTrue("Wait for cellular connection timeout",
                        mAct.waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                        2 * ConnectivityManagerTestActivity.LONG_TIMEOUT));
            }
            sleep(mWifiSleepTime, "Interrupted while device is in sleep mode");
            // Verify the wi-fi is still off and data connection is on
            assertEquals("Wi-Fi is reconnected", State.DISCONNECTED,
                    mAct.mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState());

            if (!mWifiOnlyFlag) {
                assertEquals("Cellular connection is down", State.CONNECTED,
                             mAct.mCM.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState());
                assertTrue("Mobile is connected, but no data connection.", mAct.pingTest(null));
            }

            // Turn screen on again
            mAct.turnScreenOn();
            // Wait for 2 seconds for the lock screen
            sleep(2 * 1000, "wait 2 seconds for lock screen");
            // Disable lock screen by inject menu key event
            mRunner.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);

            // Measure the time for Wi-Fi to get connected
            long startTime = System.currentTimeMillis();
            assertTrue("Wait for Wi-Fi enable timeout after wake up",
                    mAct.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                    ConnectivityManagerTestActivity.SHORT_TIMEOUT));
            assertTrue("Wait for Wi-Fi connection timeout after wake up",
                    mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                    6 * ConnectivityManagerTestActivity.LONG_TIMEOUT));
            long connectionTime = System.currentTimeMillis() - startTime;
            sum += connectionTime;
            log("average reconnection time is: " + sum/(i+1));

            assertTrue("Reconnect to Wi-Fi network, but no data connection.", mAct.pingTest(null));
        }
        if (i == mReconnectIterations) {
            writeOutput(String.format("iteration %d out of %d",
                    i, mReconnectIterations));
        }
    }
}
