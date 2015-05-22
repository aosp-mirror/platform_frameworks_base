/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib.wifi;

import android.content.Intent;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.android.settingslib.BaseTest;
import com.android.settingslib.wifi.WifiTracker.Scanner;
import com.android.settingslib.wifi.WifiTracker.WifiListener;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class WifiTrackerTest extends BaseTest {

    private static final String TAG = "WifiTrackerTest";

    private static final String[] TEST_SSIDS = new String[] {
        "TEST_SSID_1",
        "TEST_SSID_2",
        "TEST_SSID_3",
        "TEST_SSID_4",
        "TEST_SSID_5",
    };
    private static final int NUM_NETWORKS = 5;

    private WifiManager mWifiManager;
    private WifiListener mWifiListener;

    private WifiTracker mWifiTracker;

    private HandlerThread mWorkerThread;
    private Looper mLooper;
    private HandlerThread mMainThread;
    private Looper mMainLooper;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mWifiManager = Mockito.mock(WifiManager.class);
        mWifiListener = Mockito.mock(WifiListener.class);
        mWorkerThread = new HandlerThread("TestHandlerThread");
        mWorkerThread.start();
        mLooper = mWorkerThread.getLooper();
        mMainThread = new HandlerThread("TestHandlerThread");
        mMainThread.start();
        mMainLooper = mMainThread.getLooper();
        mWifiTracker = new WifiTracker(mContext, mWifiListener, mLooper, true, true, true,
                mWifiManager, mMainLooper);
        mWifiTracker.mScanner = mWifiTracker.new Scanner();
        Mockito.when(mWifiManager.isWifiEnabled()).thenReturn(true);
    }

    @Override
    protected void tearDown() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiTracker.dump(pw);
        pw.flush();
        Log.d(TAG, sw.toString());
        super.tearDown();
    }

    public void testAccessPointsCallback() {
        sendScanResultsAndProcess(false);

        Mockito.verify(mWifiListener, Mockito.atLeastOnce()).onAccessPointsChanged();
    }

    public void testConnectedCallback() {
        sendConnected();
        waitForThreads();

        Mockito.verify(mWifiListener, Mockito.atLeastOnce()).onConnectedChanged();
        assertEquals(true, mWifiTracker.isConnected());
    }

    public void testWifiStateCallback() {
        final int TEST_WIFI_STATE = WifiManager.WIFI_STATE_ENABLED;

        Intent i = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        i.putExtra(WifiManager.EXTRA_WIFI_STATE, TEST_WIFI_STATE);
        mWifiTracker.mReceiver.onReceive(mContext, i);
        waitForThreads();

        ArgumentCaptor<Integer> wifiState = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(mWifiListener, Mockito.atLeastOnce())
                .onWifiStateChanged(wifiState.capture());
        assertEquals(TEST_WIFI_STATE, (int) wifiState.getValue());
    }

    public void testScanner() {
        // TODO: Figure out how to verify more of the Scanner functionality.
        // Make scans be successful.
        Mockito.when(mWifiManager.startScan()).thenReturn(true);

        mWifiTracker.mScanner.handleMessage(mWifiTracker.mScanner.obtainMessage(Scanner.MSG_SCAN));
        Mockito.verify(mWifiManager, Mockito.atLeastOnce()).startScan();
    }

    public void testNetworkSorting() {
        List<WifiConfiguration> wifiConfigs = new ArrayList<WifiConfiguration>();
        List<ScanResult> scanResults = new ArrayList<ScanResult>();
        String[] expectedSsids = generateTestNetworks(wifiConfigs, scanResults, true);

        // Tell WifiTracker we are connected now.
        sendConnected();

        // Send all of the configs and scan results to the tracker.
        Mockito.when(mWifiManager.getConfiguredNetworks()).thenReturn(wifiConfigs);
        Mockito.when(mWifiManager.getScanResults()).thenReturn(scanResults);
        sendScanResultsAndProcess(false);

        List<AccessPoint> accessPoints = mWifiTracker.getAccessPoints();
        assertEquals("Expected number of results", NUM_NETWORKS, accessPoints.size());
        for (int i = 0; i < NUM_NETWORKS; i++) {
            assertEquals("Verifying slot " + i, expectedSsids[i], accessPoints.get(i).getSsid());
        }
    }

    public void testSavedOnly() {
        mWifiTracker = new WifiTracker(mContext, mWifiListener, mLooper, true, false, true,
                mWifiManager, mMainLooper);
        mWifiTracker.mScanner = mWifiTracker.new Scanner();

        List<WifiConfiguration> wifiConfigs = new ArrayList<WifiConfiguration>();
        List<ScanResult> scanResults = new ArrayList<ScanResult>();
        generateTestNetworks(wifiConfigs, scanResults, true);

        // Tell WifiTracker we are connected now.
        sendConnected();

        // Send all of the configs and scan results to the tracker.
        Mockito.when(mWifiManager.getConfiguredNetworks()).thenReturn(wifiConfigs);
        Mockito.when(mWifiManager.getScanResults()).thenReturn(scanResults);
        sendScanResultsAndProcess(false);

        List<AccessPoint> accessPoints = mWifiTracker.getAccessPoints();
        // Only expect the first two to come back in the results.
        assertEquals("Expected number of results", 2, accessPoints.size());
        assertEquals(TEST_SSIDS[1], accessPoints.get(0).getSsid());
        assertEquals(TEST_SSIDS[0], accessPoints.get(1).getSsid());
    }

    /**
     * This tests the case where Settings runs this on a non-looper thread for indexing.
     */
    public void testSavedOnlyNoLooper() {
        mWifiTracker = new WifiTracker(mContext, mWifiListener, mLooper, true, false, true,
                mWifiManager,  null);
        mWifiTracker.mScanner = mWifiTracker.new Scanner();

        List<WifiConfiguration> wifiConfigs = new ArrayList<WifiConfiguration>();
        List<ScanResult> scanResults = new ArrayList<ScanResult>();
        generateTestNetworks(wifiConfigs, scanResults, true);

        // Send all of the configs and scan results to the tracker.
        Mockito.when(mWifiManager.getConfiguredNetworks()).thenReturn(wifiConfigs);
        Mockito.when(mWifiManager.getScanResults()).thenReturn(scanResults);
        mWifiTracker.forceUpdate();

        List<AccessPoint> accessPoints = mWifiTracker.getAccessPoints();
        // Only expect the first two to come back in the results.
        assertEquals("Expected number of results", 2, accessPoints.size());
        assertEquals(TEST_SSIDS[1], accessPoints.get(0).getSsid());
        assertEquals(TEST_SSIDS[0], accessPoints.get(1).getSsid());
    }

    public void testAvailableOnly() {
        mWifiTracker = new WifiTracker(mContext, mWifiListener, mLooper, false, true, true,
                mWifiManager, mMainLooper);
        mWifiTracker.mScanner = mWifiTracker.new Scanner();

        List<WifiConfiguration> wifiConfigs = new ArrayList<WifiConfiguration>();
        List<ScanResult> scanResults = new ArrayList<ScanResult>();
        String[] expectedSsids = generateTestNetworks(wifiConfigs, scanResults, true);

        // Tell WifiTracker we are connected now.
        sendConnected();

        // Send all of the configs and scan results to the tracker.
        Mockito.when(mWifiManager.getConfiguredNetworks()).thenReturn(wifiConfigs);
        Mockito.when(mWifiManager.getScanResults()).thenReturn(scanResults);
        sendScanResultsAndProcess(false);

        // Expect the last one (sorted order) to be left off since its only saved.
        List<AccessPoint> accessPoints = mWifiTracker.getAccessPoints();
        assertEquals("Expected number of results", NUM_NETWORKS - 1, accessPoints.size());
        for (int i = 0; i < NUM_NETWORKS - 1; i++) {
            assertEquals("Verifying slot " + i, expectedSsids[i], accessPoints.get(i).getSsid());
        }
    }

    public void testNonEphemeralConnected() {
        mWifiTracker = new WifiTracker(mContext, mWifiListener, mLooper, false, true, true,
                mWifiManager, mMainLooper);
        mWifiTracker.mScanner = mWifiTracker.new Scanner();

        List<WifiConfiguration> wifiConfigs = new ArrayList<WifiConfiguration>();
        List<ScanResult> scanResults = new ArrayList<ScanResult>();
        generateTestNetworks(wifiConfigs, scanResults, false);

        // Tell WifiTracker we are connected now.
        sendConnected();

        // Send all of the configs and scan results to the tracker.
        Mockito.when(mWifiManager.getConfiguredNetworks()).thenReturn(wifiConfigs);
        Mockito.when(mWifiManager.getScanResults()).thenReturn(scanResults);
        // Do this twice to catch a bug that was happening in the caching, making things ephemeral.
        sendScanResultsAndProcess(true);

        List<AccessPoint> accessPoints = mWifiTracker.getAccessPoints();
        assertEquals("Expected number of results", NUM_NETWORKS - 1, accessPoints.size());
        assertFalse("Connection is not ephemeral", accessPoints.get(0).isEphemeral());
        assertTrue("Connected to wifi", accessPoints.get(0).isActive());
    }

    public void testEnableResumeScanning() {
        mWifiTracker.mScanner = null;

        Intent i = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        // Make sure disable/enable cycle works with no scanner (no crashing).
        i.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED);
        mWifiTracker.mReceiver.onReceive(mContext, i);
        i.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED);
        mWifiTracker.mReceiver.onReceive(mContext, i);

        Mockito.when(mWifiManager.isWifiEnabled()).thenReturn(false);
        i.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED);
        mWifiTracker.mReceiver.onReceive(mContext, i);
        // Now enable scanning while wifi is off, it shouldn't start.
        mWifiTracker.resumeScanning();
        assertFalse(mWifiTracker.mScanner.isScanning());

        // Turn on wifi and make sure scanning starts.
        i.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED);
        mWifiTracker.mReceiver.onReceive(mContext, i);
        assertTrue(mWifiTracker.mScanner.isScanning());
    }

    private String[] generateTestNetworks(List<WifiConfiguration> wifiConfigs,
            List<ScanResult> scanResults, boolean connectedIsEphemeral) {
        String[] expectedSsids = new String[NUM_NETWORKS];

        // First is just saved;
        addConfig(wifiConfigs, TEST_SSIDS[0]);
        // This should come last since its not available.
        expectedSsids[4] = TEST_SSIDS[0];

        // Second is saved and available.
        addConfig(wifiConfigs, TEST_SSIDS[1]);
        addResult(scanResults, TEST_SSIDS[1], 0);
        // This one is going to have a couple extra results, to verify de-duplication.
        addResult(scanResults, TEST_SSIDS[1], 2);
        addResult(scanResults, TEST_SSIDS[1], 1);
        // This should come second since it is available and saved but not connected.
        expectedSsids[1] = TEST_SSIDS[1];

        // Third is just available, but higher rssi.
        addResult(scanResults, TEST_SSIDS[2], 3);
        // This comes after the next one since it has a lower rssi.
        expectedSsids[3] = TEST_SSIDS[2];

        // Fourth also just available but with even higher rssi.
        addResult(scanResults, TEST_SSIDS[3], 4);
        // This is the highest rssi but not saved so it should be after the saved+availables.
        expectedSsids[2] = TEST_SSIDS[3];

        // Last is going to be connected.
        int netId = WifiConfiguration.INVALID_NETWORK_ID;
        if (!connectedIsEphemeral) {
            netId = addConfig(wifiConfigs, TEST_SSIDS[4]);
        }
        addResult(scanResults, TEST_SSIDS[4], 2);
        // Setup wifi connection to be this one.
        WifiInfo wifiInfo = Mockito.mock(WifiInfo.class);
        Mockito.when(wifiInfo.getSSID()).thenReturn(TEST_SSIDS[4]);
        Mockito.when(wifiInfo.getNetworkId()).thenReturn(netId);
        Mockito.when(mWifiManager.getConnectionInfo()).thenReturn(wifiInfo);
        // This should come first since it is connected.
        expectedSsids[0] = TEST_SSIDS[4];

        return expectedSsids;
    }

    private void addResult(List<ScanResult> results, String ssid, int level) {
        results.add(new ScanResult(WifiSsid.createFromAsciiEncoded(ssid),
                ssid, ssid, levelToRssi(level), AccessPoint.LOWER_FREQ_24GHZ, 0));
    }

    public static int levelToRssi(int level) {
        // Reverse level to rssi calculation based off from WifiManager.calculateSignalLevel.
        final int MAX_RSSI = -55;
        final int MIN_RSSI = -100;
        final int NUM_LEVELS = 4;
        return level * (MAX_RSSI - MIN_RSSI) / (NUM_LEVELS - 1) + MIN_RSSI;
    }

    private int addConfig(List<WifiConfiguration> configs, String ssid) {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = configs.size();
        config.SSID = '"' + ssid + '"';
        configs.add(config);
        return config.networkId;
    }

    private void sendConnected() {
        NetworkInfo networkInfo = Mockito.mock(NetworkInfo.class);
        Mockito.when(networkInfo.isConnected()).thenReturn(true);
        Mockito.when(networkInfo.getState()).thenReturn(State.CONNECTED);
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        mWifiTracker.mReceiver.onReceive(mContext, intent);
    }

    private void sendScanResultsAndProcess(boolean sendTwice) {
        Intent i = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mWifiTracker.mReceiver.onReceive(mContext, i);
        if (sendTwice) {
            mWifiTracker.mReceiver.onReceive(mContext, i);
        }
        waitForThreads();
    }

    private void waitForThreads() {
        // Run all processing.
        mWorkerThread.quitSafely();
        try {
            mWorkerThread.join();
        } catch (InterruptedException e) {
        }
        // Send all callbacks.
        mMainThread.quitSafely();
        try {
            mMainThread.join();
        } catch (InterruptedException e) {
        }
    }

}
