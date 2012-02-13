/*
 * Copyright (C) 2011, The Android Open Source Project
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

package com.android.bandwidthtest;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.bandwidthtest.util.BandwidthTestUtil;
import com.android.bandwidthtest.util.ConnectionUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Test that downloads files from a test server and reports the bandwidth metrics collected.
 */
public class BandwidthTest extends InstrumentationTestCase {

    private static final String LOG_TAG = "BandwidthTest";
    private final static String PROF_LABEL = "PROF_";
    private final static String PROC_LABEL = "PROC_";
    private final static int INSTRUMENTATION_IN_PROGRESS = 2;

    private final static String BASE_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath();
    private final static String TMP_FILENAME = "tmp.dat";
    // Download 10.486 * 106 bytes (+ headers) from app engine test server.
    private final int FILE_SIZE = 10485613;
    private Context mContext;
    private ConnectionUtil mConnectionUtil;
    private TelephonyManager mTManager;
    private int mUid;
    private String mSsid;
    private String mTestServer;
    private String mDeviceId;
    private BandwidthTestRunner mRunner;


    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mRunner = (BandwidthTestRunner) getInstrumentation();
        mSsid = mRunner.mSsid;
        mTestServer = mRunner.mTestServer;
        mContext = mRunner.getTargetContext();
        mConnectionUtil = new ConnectionUtil(mContext);
        mConnectionUtil.initialize();
        Log.v(LOG_TAG, "Initialized mConnectionUtil");
        mUid = Process.myUid();
        mTManager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mDeviceId = mTManager.getDeviceId();
    }

    @Override
    protected void tearDown() throws Exception {
        mConnectionUtil.cleanUp();
        super.tearDown();
    }

    /**
     * Ensure that downloading on wifi reports reasonable stats.
     */
    @LargeTest
    public void testWifiDownload() throws Exception {
        mConnectionUtil.wifiTestInit();
        assertTrue("Could not connect to wifi!", setDeviceWifiAndAirplaneMode(mSsid));
        downloadFile();
    }

    /**
     * Ensure that downloading on mobile reports reasonable stats.
     */
    @LargeTest
    public void testMobileDownload() throws Exception {
        // As part of the setup we disconnected from wifi; make sure we are connected to mobile and
        // that we have data.
        assertTrue("Do not have mobile data!", hasMobileData());
        downloadFile();
    }

    /**
     * Helper method that downloads a file using http connection from a test server and reports the
     * data usage stats to instrumentation out.
     */
    protected void downloadFile() throws Exception {
        NetworkStats pre_test_stats = fetchDataFromProc(mUid);
        String ts = Long.toString(System.currentTimeMillis());

        String targetUrl = BandwidthTestUtil.buildDownloadUrl(
                mTestServer, FILE_SIZE, mDeviceId, ts);
        TrafficStats.startDataProfiling(mContext);
        File tmpSaveFile = new File(BASE_DIR + File.separator + TMP_FILENAME);
        assertTrue(BandwidthTestUtil.DownloadFromUrl(targetUrl, tmpSaveFile));
        NetworkStats prof_stats = TrafficStats.stopDataProfiling(mContext);
        Log.d(LOG_TAG, prof_stats.toString());

        NetworkStats post_test_stats = fetchDataFromProc(mUid);
        NetworkStats proc_stats = post_test_stats.subtract(pre_test_stats);

        // Output measurements to instrumentation out, so that it can be compared to that of
        // the server.
        Bundle results = new Bundle();
        results.putString("device_id", mDeviceId);
        results.putString("timestamp", ts);
        results.putInt("size", FILE_SIZE);
        AddStatsToResults(PROF_LABEL, prof_stats, results);
        AddStatsToResults(PROC_LABEL, proc_stats, results);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, results);

        // Clean up.
        assertTrue(cleanUpFile(tmpSaveFile));
    }

    /**
     * Ensure that uploading on wifi reports reasonable stats.
     */
    @LargeTest
    public void testWifiUpload() throws Exception {
        mConnectionUtil.wifiTestInit();
        assertTrue(setDeviceWifiAndAirplaneMode(mSsid));
        uploadFile();
    }

    /**
     *  Ensure that uploading on wifi reports reasonable stats.
     */
    @LargeTest
    public void testMobileUpload() throws Exception {
        assertTrue(hasMobileData());
        uploadFile();
    }

    /**
     * Helper method that downloads a test file to upload. The stats reported to instrumentation out
     * only include upload stats.
     */
    protected void uploadFile() throws Exception {
        // Download a file from the server.
        String ts = Long.toString(System.currentTimeMillis());
        String targetUrl = BandwidthTestUtil.buildDownloadUrl(
                mTestServer, FILE_SIZE, mDeviceId, ts);
        File tmpSaveFile = new File(BASE_DIR + File.separator + TMP_FILENAME);
        assertTrue(BandwidthTestUtil.DownloadFromUrl(targetUrl, tmpSaveFile));

        ts = Long.toString(System.currentTimeMillis());
        NetworkStats pre_test_stats = fetchDataFromProc(mUid);
        TrafficStats.startDataProfiling(mContext);
        assertTrue(BandwidthTestUtil.postFileToServer(mTestServer, mDeviceId, ts, tmpSaveFile));
        NetworkStats prof_stats = TrafficStats.stopDataProfiling(mContext);
        Log.d(LOG_TAG, prof_stats.toString());
        NetworkStats post_test_stats = fetchDataFromProc(mUid);
        NetworkStats proc_stats = post_test_stats.subtract(pre_test_stats);

        // Output measurements to instrumentation out, so that it can be compared to that of
        // the server.
        Bundle results = new Bundle();
        results.putString("device_id", mDeviceId);
        results.putString("timestamp", ts);
        results.putInt("size", FILE_SIZE);
        AddStatsToResults(PROF_LABEL, prof_stats, results);
        AddStatsToResults(PROC_LABEL, proc_stats, results);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, results);

        // Clean up.
        assertTrue(cleanUpFile(tmpSaveFile));
    }

    /**
     * We want to make sure that if we use wifi and the  Download Manager to download stuff,
     * accounting still goes to the app making the call and that the numbers still make sense.
     */
    @LargeTest
    public void testWifiDownloadWithDownloadManager() throws Exception {
        mConnectionUtil.wifiTestInit();
        assertTrue(setDeviceWifiAndAirplaneMode(mSsid));
        downloadFileUsingDownloadManager();
    }

    /**
     * We want to make sure that if we use mobile data and the Download Manager to download stuff,
     * accounting still goes to the app making the call and that the numbers still make sense.
     */
    @LargeTest
    public void testMobileDownloadWithDownloadManager() throws Exception {
        assertTrue(hasMobileData());
        downloadFileUsingDownloadManager();
    }

    /**
     * Helper method that downloads a file from a test server using the download manager and reports
     * the stats to instrumentation out.
     */
    protected void downloadFileUsingDownloadManager() throws Exception {
        // If we are using the download manager, then the data that is written to /proc/uid_stat/
        // is accounted against download manager's uid, since it uses pre-ICS API.
        int downloadManagerUid = mConnectionUtil.downloadManagerUid();
        assertTrue(downloadManagerUid >= 0);
        NetworkStats pre_test_stats = fetchDataFromProc(downloadManagerUid);
        // start profiling
        TrafficStats.startDataProfiling(mContext);
        String ts = Long.toString(System.currentTimeMillis());
        String targetUrl = BandwidthTestUtil.buildDownloadUrl(
                mTestServer, FILE_SIZE, mDeviceId, ts);
        Log.v(LOG_TAG, "Download url: " + targetUrl);
        File tmpSaveFile = new File(BASE_DIR + File.separator + TMP_FILENAME);
        assertTrue(mConnectionUtil.startDownloadAndWait(targetUrl, 500000));
        NetworkStats prof_stats = TrafficStats.stopDataProfiling(mContext);
        NetworkStats post_test_stats = fetchDataFromProc(downloadManagerUid);
        NetworkStats proc_stats = post_test_stats.subtract(pre_test_stats);
        Log.d(LOG_TAG, prof_stats.toString());
        // Output measurements to instrumentation out, so that it can be compared to that of
        // the server.
        Bundle results = new Bundle();
        results.putString("device_id", mDeviceId);
        results.putString("timestamp", ts);
        results.putInt("size", FILE_SIZE);
        AddStatsToResults(PROF_LABEL, prof_stats, results);
        AddStatsToResults(PROC_LABEL, proc_stats, results);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, results);

        // Clean up.
        assertTrue(cleanUpFile(tmpSaveFile));
    }

    /**
     * Fetch network data from /proc/uid_stat/uid
     *
     * @return populated {@link NetworkStats}
     */
    public NetworkStats fetchDataFromProc(int uid) {
        String root_filepath = "/proc/uid_stat/" + uid + "/";
        File rcv_stat = new File (root_filepath + "tcp_rcv");
        int rx = BandwidthTestUtil.parseIntValueFromFile(rcv_stat);
        File snd_stat = new File (root_filepath + "tcp_snd");
        int tx = BandwidthTestUtil.parseIntValueFromFile(snd_stat);
        NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        stats.addValues(NetworkStats.IFACE_ALL, uid, NetworkStats.SET_DEFAULT,
                NetworkStats.TAG_NONE, rx, 0, tx, 0, 0);
        return stats;
    }

    /**
     * Turn on Airplane mode and connect to the wifi.
     *
     * @param ssid of the wifi to connect to
     * @return true if we successfully connected to a given network.
     */
    public boolean setDeviceWifiAndAirplaneMode(String ssid) {
        mConnectionUtil.setAirplaneMode(mContext, true);
        assertTrue(mConnectionUtil.connectToWifi(ssid));
        assertTrue(mConnectionUtil.waitForWifiState(WifiManager.WIFI_STATE_ENABLED,
                ConnectionUtil.LONG_TIMEOUT));
        assertTrue(mConnectionUtil.waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, ConnectionUtil.LONG_TIMEOUT));
        return mConnectionUtil.hasData();
    }

    /**
     * Helper method to make sure we are connected to mobile data.
     *
     * @return true if we successfully connect to mobile data.
     */
    public boolean hasMobileData() {
        assertTrue(mConnectionUtil.waitForNetworkState(ConnectivityManager.TYPE_MOBILE,
                State.CONNECTED, ConnectionUtil.LONG_TIMEOUT));
        assertTrue("Not connected to mobile", mConnectionUtil.isConnectedToMobile());
        assertFalse("Still connected to wifi.", mConnectionUtil.isConnectedToWifi());
        return mConnectionUtil.hasData();
    }

    /**
     * Output the {@link NetworkStats} to Instrumentation out.
     *
     * @param label to attach to this given stats.
     * @param stats {@link NetworkStats} to add.
     * @param results {@link Bundle} to be added to.
     */
    public void AddStatsToResults(String label, NetworkStats stats, Bundle results){
        if (results == null || results.isEmpty()) {
            Log.e(LOG_TAG, "Empty bundle provided.");
            return;
        }
        // Merge stats across all sets.
        Map<Integer, Entry> totalStats = new HashMap<Integer, Entry>();
        for (int i = 0; i < stats.size(); ++i) {
            Entry statsEntry = stats.getValues(i, null);
            // We are only interested in the all inclusive stats.
            if (statsEntry.tag != 0) {
                continue;
            }
            Entry mapEntry = null;
            if (totalStats.containsKey(statsEntry.uid)) {
                mapEntry = totalStats.get(statsEntry.uid);
                switch (statsEntry.set) {
                    case NetworkStats.SET_ALL:
                        mapEntry.rxBytes = statsEntry.rxBytes;
                        mapEntry.txBytes = statsEntry.txBytes;
                        break;
                    case NetworkStats.SET_DEFAULT:
                    case NetworkStats.SET_FOREGROUND:
                        mapEntry.rxBytes += statsEntry.rxBytes;
                        mapEntry.txBytes += statsEntry.txBytes;
                        break;
                    default:
                        Log.w(LOG_TAG, "Invalid state found in NetworkStats.");
                }
            } else {
                totalStats.put(statsEntry.uid, statsEntry);
            }
        }
        // Ouput merged stats to bundle.
        for (Entry entry : totalStats.values()) {
            results.putInt(label + "uid", entry.uid);
            results.putLong(label + "tx", entry.txBytes);
            results.putLong(label + "rx", entry.rxBytes);
        }
    }

    /**
     * Remove file if it exists.
     * @param file {@link File} to delete.
     * @return true if successfully deleted the file.
     */
    private boolean cleanUpFile(File file) {
        if (file.exists()) {
            return file.delete();
        }
        return true;
    }
}
