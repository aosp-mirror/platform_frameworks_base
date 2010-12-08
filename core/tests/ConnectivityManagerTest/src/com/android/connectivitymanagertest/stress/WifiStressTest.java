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

import com.android.connectivitymanagertest.ConnectivityManagerStressTestRunner;
import com.android.connectivitymanagertest.ConnectivityManagerTestActivity;

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
import android.os.SystemClock;
import android.provider.Settings;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;

import android.util.Log;

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
    private final static long WIFI_IDLE_MS = 5 * 1000;

    /**
     * The delay for Wi-Fi to get into idle, after screen off + WIFI_IDEL_MS + WIFI_IDLE_DELAY
     * the Wi-Fi should be in idle mode and device should be in cellular mode.
     */
    private final static long WIFI_IDLE_DELAY = 3 * 1000;

    private final static String OUTPUT_FILE = "WifiStressTestOutput.txt";
    private ConnectivityManagerTestActivity mAct;
    private int mReconnectIterations;
    private int mWifiSleepTime;
    private int mScanIterations;
    private String mSsid;
    private String mPassword;
    private ConnectivityManagerStressTestRunner mRunner;
    private PowerManager.WakeLock wl = null;
    private BufferedWriter mOutputWriter = null;

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
        wl = null;
        mOutputWriter = new BufferedWriter(new FileWriter(new File(
                Environment.getExternalStorageDirectory(), OUTPUT_FILE), true));
        if (!mAct.mWifiManager.isWifiEnabled()) {
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
        if ((wl != null) && wl.isHeld()) {
            wl.release();
        }
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

    private void turnScreenOff() {
        log("Turn screen off");
        if (wl != null) {
            log("release wake lock");
            wl.release();
        }
        PowerManager pm =
            (PowerManager) mRunner.getContext().getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis() + 50);
    }

    private void turnScreenOn() {
        log("Turn screen on");
        PowerManager pm =
            (PowerManager)mRunner.getContext().getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "wifiStressTest");
        wl.acquire();
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
        int averageScanTime = 0;
        int ssidAppearInScanResultsCount = 0; // count times of given ssid appear in scan results.
        for (i = 0; i < mScanIterations; i++) {
            log("testWifiScanning: iteration: " + i);
            writeOutput(String.format("scan iteration %d out of %d",
                    i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", averageScanTime));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, mScanIterations));
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
                        averageScanTime = scanTimeSum/mScanIterations;
                        log("average scanning time: " + averageScanTime);
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
            writeOutput(String.format("scan iteration %d out of %d",
                    i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", averageScanTime));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, mScanIterations));
        }
    }

    // Stress Wifi reconnection to secure net after sleep
    @LargeTest
    public void testWifiReconnectionAfterSleep() {
        int value = Settings.System.getInt(mRunner.getContext().getContentResolver(),
                Settings.System.WIFI_SLEEP_POLICY, -1);
        if (value < 0) {
            Settings.System.putInt(mRunner.getContext().getContentResolver(),
                    Settings.System.WIFI_SLEEP_POLICY, Settings.System.WIFI_SLEEP_POLICY_DEFAULT);
            log("set wifi sleep policy to default value");
        }
        Settings.Secure.putLong(mRunner.getContext().getContentResolver(),
                Settings.Secure.WIFI_IDLE_MS, WIFI_IDLE_MS);

        // Connect to a Wi-Fi network
        turnScreenOn();
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
        int i;
        for (i = 0; i < mReconnectIterations; i++) {
            // 1. Put device into sleep
            // 2. Wait for the device to sleep for sometime, very 3G is connected
            // 3. Wake up the device
            writeOutput(String.format("reconnection after sleep iteration %d out of %d",
                    i, mReconnectIterations));
            log("iteration: " + i);
            turnScreenOff();
            PowerManager pm =
                (PowerManager)mRunner.getContext().getSystemService(Context.POWER_SERVICE);
            assertFalse(pm.isScreenOn());
            sleep(WIFI_IDLE_MS + WIFI_IDLE_DELAY, "Interruped while wait for wifi to be idle");
            assertTrue("Wait for Wi-Fi to idle timeout",
                    mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
                    ConnectivityManagerTestActivity.SHORT_TIMEOUT));
            assertTrue("Wait for cellular connection timeout",
                    mAct.waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                    ConnectivityManagerTestActivity.LONG_TIMEOUT));
            sleep(mWifiSleepTime + WIFI_IDLE_DELAY, "Interrupted while device is in sleep mode");
            // Turn screen on again
            turnScreenOn();
            assertTrue("Wait for Wi-Fi enable timeout after wake up",
                    mAct.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                    ConnectivityManagerTestActivity.SHORT_TIMEOUT));
            assertTrue("Wait for Wi-Fi connection timeout after wake up",
                    mAct.waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                    ConnectivityManagerTestActivity.LONG_TIMEOUT));
        }
        if (i == mReconnectIterations) {
            writeOutput(String.format("reconnection after sleep iteration %d out of %d",
                    i, mReconnectIterations));
        }
    }
}
