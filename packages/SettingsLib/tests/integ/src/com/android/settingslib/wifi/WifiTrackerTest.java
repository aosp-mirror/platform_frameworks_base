/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.settingslib.utils.ThreadUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// TODO(sghuman): Change these to robolectric tests b/35766684.

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WifiTrackerTest {

    private static final String TAG = "WifiTrackerTest";
    private static final int LATCH_TIMEOUT = 4000;

    private static final String SSID_1 = "ssid1";
    private static final String BSSID_1 = "00:00:00:00:00:00";
    private static final NetworkKey NETWORK_KEY_1 =
            new NetworkKey(new WifiKey('"' + SSID_1 + '"', BSSID_1));
    private static final int RSSI_1 = -30;
    private static final byte SCORE_1 = 10;
    private static final int BADGE_1 = AccessPoint.Speed.MODERATE;

    private static final String SSID_2 = "ssid2";
    private static final String BSSID_2 = "AA:AA:AA:AA:AA:AA";
    private static final NetworkKey NETWORK_KEY_2 =
            new NetworkKey(new WifiKey('"' + SSID_2 + '"', BSSID_2));
    private static final int RSSI_2 = -30;
    private static final byte SCORE_2 = 15;
    private static final int BADGE_2 = AccessPoint.Speed.FAST;

    // TODO(b/65594609): Convert mutable Data objects to instance variables / builder pattern
    private static final int NETWORK_ID_1 = 123;
    private static final int CONNECTED_RSSI = -50;
    private static final WifiInfo CONNECTED_AP_1_INFO = new WifiInfo();
    static {
        CONNECTED_AP_1_INFO.setSSID(WifiSsid.createFromAsciiEncoded(SSID_1));
        CONNECTED_AP_1_INFO.setBSSID(BSSID_1);
        CONNECTED_AP_1_INFO.setNetworkId(NETWORK_ID_1);
        CONNECTED_AP_1_INFO.setRssi(CONNECTED_RSSI);
    }
    private static final WifiConfiguration CONFIGURATION_1 = new WifiConfiguration();
    static {
        CONFIGURATION_1.SSID = SSID_1;
        CONFIGURATION_1.BSSID = BSSID_1;
        CONFIGURATION_1.networkId = NETWORK_ID_1;
    }

    private static final int NETWORK_ID_2 = 2;
    private static final WifiConfiguration CONFIGURATION_2 = new WifiConfiguration();
    static {
        CONFIGURATION_2.SSID = SSID_2;
        CONFIGURATION_2.BSSID = BSSID_2;
        CONFIGURATION_2.networkId = NETWORK_ID_2;
    }

    @Captor ArgumentCaptor<WifiNetworkScoreCache> mScoreCacheCaptor;
    @Mock private ConnectivityManager mockConnectivityManager;
    @Mock private NetworkScoreManager mockNetworkScoreManager;
    @Mock private RssiCurve mockCurve1;
    @Mock private RssiCurve mockCurve2;
    @Mock private RssiCurve mockBadgeCurve1;
    @Mock private RssiCurve mockBadgeCurve2;
    @Mock private WifiManager mockWifiManager;
    @Mock private WifiTracker.WifiListener mockWifiListener;

    private final List<NetworkKey> mRequestedKeys = new ArrayList<>();

    private Context mContext;
    private CountDownLatch mAccessPointsChangedLatch;
    private CountDownLatch mRequestScoresLatch;
    private Handler mScannerHandler;
    private HandlerThread mWorkerThread;

    private int mOriginalScoringUiSettingValue;

    @SuppressWarnings("VisibleForTests")
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getTargetContext();

        mWorkerThread = new HandlerThread("TestHandlerWorkerThread");
        mWorkerThread.start();

        // Make sure the scanner doesn't try to run on the testing thread.
        HandlerThread scannerThread = new HandlerThread("ScannerWorkerThread");
        scannerThread.start();
        mScannerHandler = new Handler(scannerThread.getLooper());

        when(mockWifiManager.isWifiEnabled()).thenReturn(true);
        when(mockWifiManager.getScanResults())
                .thenReturn(Arrays.asList(buildScanResult1(), buildScanResult2()));
        when(mockWifiManager.getConfiguredNetworks())
                .thenReturn(Arrays.asList(CONFIGURATION_1, CONFIGURATION_2));


        when(mockCurve1.lookupScore(RSSI_1)).thenReturn(SCORE_1);
        when(mockCurve2.lookupScore(RSSI_2)).thenReturn(SCORE_2);

        when(mockBadgeCurve1.lookupScore(RSSI_1)).thenReturn((byte) BADGE_1);
        when(mockBadgeCurve2.lookupScore(RSSI_2)).thenReturn((byte) BADGE_2);

        doNothing()
                .when(mockNetworkScoreManager)
                .registerNetworkScoreCache(
                        anyInt(),
                        mScoreCacheCaptor.capture(),
                        Matchers.anyInt());

        // Capture requested keys and count down latch if present
        doAnswer(
                new Answer<Boolean>() {
                    @Override
                    public Boolean answer(InvocationOnMock input) {
                        if (mRequestScoresLatch != null) {
                            mRequestScoresLatch.countDown();
                        }
                        NetworkKey[] keys = (NetworkKey[]) input.getArguments()[0];
                        for (NetworkKey key : keys) {
                            mRequestedKeys.add(key);
                        }
                        return true;
                    }
                }).when(mockNetworkScoreManager).requestScores(Matchers.<NetworkKey[]>any());

        // We use a latch to detect callbacks as Tracker initialization state often invokes
        // callbacks
        doAnswer(invocation -> {
                    if (mAccessPointsChangedLatch != null) {
                      mAccessPointsChangedLatch.countDown();
                    }
                    return null;
                }).when(mockWifiListener).onAccessPointsChanged();

        // Turn on Scoring UI features
        mOriginalScoringUiSettingValue = Settings.Global.getInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                0 /* disabled */);
        Settings.Global.putInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                1 /* enabled */);

    }

    @After
    public void cleanUp() {
        Settings.Global.putInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                mOriginalScoringUiSettingValue);
    }

    private static ScanResult buildScanResult1() {
        return new ScanResult(
                WifiSsid.createFromAsciiEncoded(SSID_1),
                BSSID_1,
                0, // hessid
                0, //anqpDomainId
                null, // osuProviders
                "", // capabilities
                RSSI_1,
                0, // frequency
                SystemClock.elapsedRealtime() * 1000 /* microsecond timestamp */);
    }

    private static ScanResult buildScanResult2() {
        return new ScanResult(
                WifiSsid.createFromAsciiEncoded(SSID_2),
                BSSID_2,
                0, // hessid
                0, //anqpDomainId
                null, // osuProviders
                "", // capabilities
                RSSI_2,
                0, // frequency
                SystemClock.elapsedRealtime() * 1000 /* microsecond timestamp */);
    }

    private WifiTracker createTrackerWithImmediateBroadcastsAndInjectInitialScanResults(
                    Intent ... intents)
            throws InterruptedException {
        WifiTracker tracker = createMockedWifiTracker();

        startTracking(tracker);
        for (Intent intent : intents) {
            tracker.mReceiver.onReceive(mContext, intent);
        }

        sendScanResults(tracker);

        return tracker;
    }

    private WifiTracker createMockedWifiTracker() {
        final WifiTracker wifiTracker = new WifiTracker(
                mContext,
                mockWifiListener,
                mockWifiManager,
                mockConnectivityManager,
                mockNetworkScoreManager,
                new IntentFilter()); // empty filter to ignore system broadcasts
        wifiTracker.setWorkThread(mWorkerThread);
        return wifiTracker;
    }

    private void startTracking(WifiTracker tracker)  throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        mScannerHandler.post(() -> {
                tracker.onStart();
                latch.countDown();
        });
        assertTrue("Latch timed out", latch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private void sendScanResults(WifiTracker tracker) throws InterruptedException {
        Intent i = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        tracker.mReceiver.onReceive(mContext, i);
    }

    private void sendUpdatedScores() throws InterruptedException {
        Bundle attr1 = new Bundle();
        attr1.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, mockBadgeCurve1);
        ScoredNetwork sc1 =
                new ScoredNetwork(
                        NETWORK_KEY_1,
                        mockCurve1,
                        false /* meteredHint */,
                        attr1);

        Bundle attr2 = new Bundle();
        attr2.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, mockBadgeCurve2);
        ScoredNetwork sc2 =
                new ScoredNetwork(
                        NETWORK_KEY_2,
                        mockCurve2,
                        true /* meteredHint */,
                        attr2);

        WifiNetworkScoreCache scoreCache = mScoreCacheCaptor.getValue();
        scoreCache.updateScores(Arrays.asList(sc1, sc2));
    }

    private WifiTracker createTrackerWithScanResultsAndAccessPoint1Connected()
            throws InterruptedException {
        when(mockWifiManager.getConnectionInfo()).thenReturn(CONNECTED_AP_1_INFO);

        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = SSID_1;
        configuration.BSSID = BSSID_1;
        configuration.networkId = NETWORK_ID_1;

        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "connected", "test");

        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        WifiTracker tracker =
                createTrackerWithImmediateBroadcastsAndInjectInitialScanResults(intent);
        assertThat(tracker.isConnected()).isTrue();
        return tracker;
    }

    private void waitForHandlersToProcessCurrentlyEnqueuedMessages(WifiTracker tracker)
            throws InterruptedException {
        CountDownLatch workerLatch = new CountDownLatch(1);
        tracker.mWorkHandler.post(() -> workerLatch.countDown());
        assertTrue("Latch timed out while waiting for WorkerHandler",
                workerLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private void switchToNetwork2(WifiTracker tracker) throws InterruptedException {
        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTING, "connecting", "test");

        WifiInfo info = new WifiInfo();
        info.setSSID(WifiSsid.createFromAsciiEncoded(SSID_2));
        info.setBSSID(BSSID_2);
        info.setRssi(CONNECTED_RSSI);
        info.setNetworkId(NETWORK_ID_2);
        when(mockWifiManager.getConnectionInfo()).thenReturn(info);

        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        tracker.mReceiver.onReceive(mContext, intent);
    }

    @Test
    public void startAndStopTrackingShouldRegisterAndUnregisterScoreCache()
            throws InterruptedException {
        WifiTracker tracker = createMockedWifiTracker();

        // Test register
        startTracking(tracker);
        verify(mockNetworkScoreManager)
                .registerNetworkScoreCache(
                          Matchers.anyInt(),
                          mScoreCacheCaptor.capture(),
                          Matchers.anyInt());

        WifiNetworkScoreCache scoreCache = mScoreCacheCaptor.getValue();

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(
                (invocation) -> {
                        latch.countDown();
                        return null;
                }).when(mockNetworkScoreManager)
                        .unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI, scoreCache);

        // Test unregister
        tracker.onStop();

        assertTrue("Latch timed out", latch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));
        verify(mockNetworkScoreManager)
                .unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI, scoreCache);
    }

    @Test
    public void testGetNumSavedNetworks() throws InterruptedException {
        WifiConfiguration validConfig = new WifiConfiguration();
        validConfig.SSID = SSID_1;
        validConfig.BSSID = BSSID_1;

        WifiConfiguration selfAddedNoAssociation = new WifiConfiguration();
        selfAddedNoAssociation.ephemeral = true;
        selfAddedNoAssociation.selfAdded = true;
        selfAddedNoAssociation.numAssociation = 0;
        selfAddedNoAssociation.SSID = SSID_2;
        selfAddedNoAssociation.BSSID = BSSID_2;

        when(mockWifiManager.getConfiguredNetworks())
                .thenReturn(Arrays.asList(validConfig, selfAddedNoAssociation));

        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();

        assertEquals(1, tracker.getNumSavedNetworks());
    }

    @Test
    public void startTrackingShouldSetConnectedAccessPointAsActive() throws InterruptedException {
        WifiTracker tracker = createTrackerWithScanResultsAndAccessPoint1Connected();

        List<AccessPoint> aps = tracker.getAccessPoints();

        assertThat(aps).hasSize(2);
        assertThat(aps.get(0).isActive()).isTrue();
    }

    @Test
    public void startTrackingAfterStopTracking_shouldRequestNewScores()
            throws InterruptedException {
        // Start the tracker and inject the initial scan results and then stop tracking
        WifiTracker tracker =  createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();

        tracker.onStop();
        mRequestedKeys.clear();

        mRequestScoresLatch = new CountDownLatch(1);
        startTracking(tracker);
        assertTrue("Latch timed out",
                mRequestScoresLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));

        assertTrue(mRequestedKeys.contains(NETWORK_KEY_1));
        assertTrue(mRequestedKeys.contains(NETWORK_KEY_2));
    }

    @Test
    public void stopTracking_shouldNotClearExistingScores()
            throws InterruptedException {
        // Start the tracker and inject the initial scan results and then stop tracking
        WifiTracker tracker =  createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        updateScoresAndWaitForCacheListenerToProcess(tracker);
        tracker.onStop();

        assertThat(mScoreCacheCaptor.getValue().getScoredNetwork(NETWORK_KEY_1)).isNotNull();
    }

    @Test
    public void scoreCacheUpdateScoresShouldTriggerOnAccessPointsChanged()
            throws InterruptedException {
        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);
        sendScanResults(tracker);

        updateScoresAndWaitForCacheListenerToProcess(tracker);
    }

    private void updateScoresAndWaitForCacheListenerToProcess(WifiTracker tracker)
            throws InterruptedException {
        // Scores are updated via the cache listener hence we need to wait for the work handler
        // to finish before proceeding.
        sendUpdatedScores();

        // Ensure the work handler has processed the scores inside the cache listener of WifiTracker
        waitForHandlersToProcessCurrentlyEnqueuedMessages(tracker);
    }

    @Test
    public void scoreCacheUpdateScoresShouldChangeSortOrder() throws InterruptedException {
        WifiTracker tracker =  createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        List<AccessPoint> aps = tracker.getAccessPoints();
        assertTrue(aps.size() == 2);
        assertEquals(aps.get(0).getSsidStr(), SSID_1);
        assertEquals(aps.get(1).getSsidStr(), SSID_2);

        updateScoresAndWaitForCacheListenerToProcess(tracker);

        aps = tracker.getAccessPoints();
        assertTrue(aps.size() == 2);
        assertEquals(aps.get(0).getSsidStr(), SSID_2);
        assertEquals(aps.get(1).getSsidStr(), SSID_1);
    }

    @Test
    public void scoreCacheUpdateScoresShouldNotChangeSortOrderWhenSortingDisabled()
            throws InterruptedException {
        Settings.Global.putInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                0 /* disabled */);

        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        List<AccessPoint> aps = tracker.getAccessPoints();
        assertTrue(aps.size() == 2);
        assertEquals(aps.get(0).getSsidStr(), SSID_1);
        assertEquals(aps.get(1).getSsidStr(), SSID_2);

        updateScoresAndWaitForCacheListenerToProcess(tracker);

        aps = tracker.getAccessPoints();
        assertTrue(aps.size() == 2);
        assertEquals(aps.get(0).getSsidStr(), SSID_1);
        assertEquals(aps.get(1).getSsidStr(), SSID_2);
    }

    @Test
    public void scoreCacheUpdateScoresShouldInsertSpeedIntoAccessPoint()
            throws InterruptedException {
        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        updateScoresAndWaitForCacheListenerToProcess(tracker);

        List<AccessPoint> aps = tracker.getAccessPoints();

        for (AccessPoint ap : aps) {
            if (ap.getSsidStr().equals(SSID_1)) {
                assertEquals(BADGE_1, ap.getSpeed());
            } else if (ap.getSsidStr().equals(SSID_2)) {
                assertEquals(BADGE_2, ap.getSpeed());
            }
        }
    }

    @Test
    public void scoreCacheUpdateMeteredShouldUpdateAccessPointMetering()
            throws InterruptedException {
        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        updateScoresAndWaitForCacheListenerToProcess(tracker);

        List<AccessPoint> aps = tracker.getAccessPoints();

        for (AccessPoint ap : aps) {
            if (ap.getSsidStr().equals(SSID_1)) {
                assertFalse(ap.isMetered());
            } else if (ap.getSsidStr().equals(SSID_2)) {
                assertTrue(ap.isMetered());
            }
        }
    }

    @Test
    public void noSpeedsShouldBeInsertedIntoAccessPointWhenScoringUiDisabled()
            throws InterruptedException {
        Settings.Global.putInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                0 /* disabled */);

        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        updateScoresAndWaitForCacheListenerToProcess(tracker);

        List<AccessPoint> aps = tracker.getAccessPoints();

        for (AccessPoint ap : aps) {
            if (ap.getSsidStr().equals(SSID_1)) {
                assertEquals(AccessPoint.Speed.NONE, ap.getSpeed());
            } else if (ap.getSsidStr().equals(SSID_2)) {
                assertEquals(AccessPoint.Speed.NONE, ap.getSpeed());
            }
        }
    }

    @Test
    public void scoresShouldBeRequestedForNewScanResultOnly()  throws InterruptedException {
        // Scores can be requested together or serially depending on how the scan results are
        // processed.
        mRequestScoresLatch = new CountDownLatch(1);
        WifiTracker tracker = createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        assertTrue(mRequestScoresLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));
        mRequestedKeys.clear();

        String ssid = "ssid3";
        String bssid = "00:00:00:00:00:00";
        ScanResult newResult = new ScanResult(
                WifiSsid.createFromAsciiEncoded(ssid),
                bssid,
                0, // hessid
                0, //anqpDomainId
                null, // osuProviders
                "", // capabilities
                RSSI_1,
                0, // frequency
                SystemClock.elapsedRealtime() * 1000);
        when(mockWifiManager.getScanResults())
                .thenReturn(Arrays.asList(buildScanResult1(), buildScanResult2(), newResult));

        mRequestScoresLatch = new CountDownLatch(1);
        sendScanResults(tracker);
        assertTrue(mRequestScoresLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));

        assertEquals(1, mRequestedKeys.size());
        assertTrue(mRequestedKeys.contains(new NetworkKey(new WifiKey('"' + ssid + '"', bssid))));
    }

    @Test
    public void scoreCacheAndListenerShouldBeUnregisteredWhenStopTrackingIsCalled() throws Exception
    {
        WifiTracker tracker =  createTrackerWithImmediateBroadcastsAndInjectInitialScanResults();
        WifiNetworkScoreCache cache = mScoreCacheCaptor.getValue();

        tracker.onStop();
        verify(mockNetworkScoreManager).unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI, cache);

        // Verify listener is unregistered so updating a score does not throw an error by posting
        // a message to the dead work handler
        mWorkerThread.quit();
        sendUpdatedScores();
    }

    /**
     * Verify that tracking a Passpoint AP on a device with Passpoint disabled doesn't cause
     * any crash.
     *
     * @throws Exception
     */
    @Test
    public void trackPasspointApWithPasspointDisabled() throws Exception {
        // TODO(sghuman): Delete this test and replace with a passpoint test
        WifiTracker tracker = createMockedWifiTracker();

        // Add a Passpoint AP to the scan results.
        List<ScanResult> results = new ArrayList<>();
        ScanResult passpointAp = new ScanResult(
                WifiSsid.createFromAsciiEncoded(SSID_1),
                BSSID_1,
                0, // hessid
                0, //anqpDomainId
                null, // osuProviders
                "", // capabilities
                RSSI_1,
                0, // frequency
                SystemClock.elapsedRealtime() * 1000 /* microsecond timestamp */);
        passpointAp.setFlag(ScanResult.FLAG_PASSPOINT_NETWORK);
        results.add(passpointAp);

        // Update access point and verify UnsupportedOperationException is being caught for
        // call to WifiManager#getMatchingWifiConfig.
        when(mockWifiManager.getConfiguredNetworks())
                .thenReturn(new ArrayList<WifiConfiguration>());
        when(mockWifiManager.getScanResults()).thenReturn(results);

        startTracking(tracker);
    }

    @Test
    public void rssiChangeBroadcastShouldUpdateConnectedAp() throws Exception {
        WifiTracker tracker =  createTrackerWithScanResultsAndAccessPoint1Connected();
        assertThat(tracker.getAccessPoints().get(0).isActive()).isTrue();

        int newRssi = CONNECTED_RSSI + 10;
        WifiInfo info = new WifiInfo(CONNECTED_AP_1_INFO);
        info.setRssi(newRssi);

        // Once the new info has been fetched, we need to wait for the access points to be copied
        mAccessPointsChangedLatch = new CountDownLatch(1);
        doAnswer(invocation -> info).when(mockWifiManager).getConnectionInfo();

        tracker.mReceiver.onReceive(mContext, new Intent(WifiManager.RSSI_CHANGED_ACTION));

        assertTrue("onAccessPointsChanged never called",
                mAccessPointsChangedLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));
        assertThat(tracker.getAccessPoints().get(0).getRssi()).isEqualTo(newRssi);
    }

    @Test
    public void onStartShouldSynchronouslyFetchLatestInformation() throws Exception {
        Network mockNetwork = mock(Network.class);
        when(mockWifiManager.getCurrentNetwork()).thenReturn(mockNetwork);

        when(mockWifiManager.getConnectionInfo()).thenReturn(CONNECTED_AP_1_INFO);

        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "connected", "test");
        when(mockConnectivityManager.getNetworkInfo(any(Network.class))).thenReturn(networkInfo);

        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        verify(mockWifiManager).getConnectionInfo();
        verify(mockWifiManager, times(1)).getConfiguredNetworks();
        verify(mockConnectivityManager).getNetworkInfo(any(Network.class));

        // mStaleAccessPoints is true
        verify(mockWifiListener, never()).onAccessPointsChanged();
        assertThat(tracker.getAccessPoints().size()).isEqualTo(2);
        assertThat(tracker.getAccessPoints().get(0).isActive()).isTrue();
    }

    @Test
    public void onStartShouldDisplayConnectedAccessPointWhenThereAreNoScanResults()
            throws Exception {
        Network mockNetwork = mock(Network.class);
        when(mockWifiManager.getCurrentNetwork()).thenReturn(mockNetwork);

        when(mockWifiManager.getConnectionInfo()).thenReturn(CONNECTED_AP_1_INFO);

        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "connected", "test");
        when(mockConnectivityManager.getNetworkInfo(any(Network.class))).thenReturn(networkInfo);

        // Don't return any scan results
        when(mockWifiManager.getScanResults()).thenReturn(new ArrayList<>());

        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        verify(mockWifiManager).getConnectionInfo();
        verify(mockWifiManager, times(1)).getConfiguredNetworks();
        verify(mockConnectivityManager).getNetworkInfo(any(Network.class));

        // mStaleAccessPoints is true
        verify(mockWifiListener, never()).onAccessPointsChanged();

        assertThat(tracker.getAccessPoints()).hasSize(1);
        assertThat(tracker.getAccessPoints().get(0).isActive()).isTrue();
    }

    @Test
    public void stopTrackingShouldRemoveAllPendingWork() throws Exception {
        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch lock = new CountDownLatch(1);
        tracker.mWorkHandler.post(() -> {
            try {
                ready.countDown();
                lock.await();
                latch.countDown();
            } catch (InterruptedException e) {
                fail("Interrupted Exception while awaiting lock release: " + e);
            }
        });

        // Enqueue messages
        final AtomicBoolean executed = new AtomicBoolean(false);
        tracker.mWorkHandler.post(() -> executed.set(true));

        try {
            ready.await(); // Make sure we have entered the first message handler
        } catch (InterruptedException e) {}
        tracker.onStop();

        lock.countDown();
        assertTrue("Latch timed out", latch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));

        // In case the method was already executing
        assertThat(tracker.mWorkHandler.hasMessagesOrCallbacks()).isFalse();

        assertThat(executed.get()).isFalse();
    }

    @Test
    public void stopTrackingShouldPreventCallbacksFromOngoingWork() throws Exception {
        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch lock = new CountDownLatch(1);
        tracker.mWorkHandler.post(() -> {
            try {
                ready.countDown();
                lock.await();

                tracker.mReceiver.onReceive(
                        mContext, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));

                latch.countDown();
            } catch (InterruptedException e) {
                fail("Interrupted Exception while awaiting lock release: " + e);
            }
        });

        ready.await(); // Make sure we have entered the first message handler
        tracker.onStop();
        lock.countDown();
        assertTrue("Latch timed out", latch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS));

        // Wait for main thread
        final CountDownLatch latch2 = new CountDownLatch(1);
        ThreadUtils.postOnMainThread(latch2::countDown);
        latch2.await();

        verify(mockWifiListener, never()).onWifiStateChanged(anyInt());
    }

    @Test
    public void stopTrackingShouldSetStaleBitWhichPreventsCallbacksUntilNextScanResult()
            throws Exception {
        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        tracker.onStop();

        startTracking(tracker);

        tracker.mReceiver.onReceive(mContext, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
        tracker.mReceiver.onReceive(
                mContext, new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION));
        tracker.mReceiver.onReceive(
                mContext, new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION));


        verify(mockWifiListener, never()).onAccessPointsChanged();

        sendScanResults(tracker); // verifies onAccessPointsChanged is invoked
    }

    @Test
    public void startTrackingShouldNotSendAnyCallbacksUntilScanResultsAreProcessed()
            throws Exception {
        WifiTracker tracker = createMockedWifiTracker();
        startTracking(tracker);

        tracker.mReceiver.onReceive(mContext, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
        tracker.mReceiver.onReceive(
                mContext, new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION));
        tracker.mReceiver.onReceive(
                mContext, new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION));

        verify(mockWifiListener, never()).onAccessPointsChanged();

        sendScanResults(tracker); // verifies onAccessPointsChanged is invoked
    }

    @Test
    public void disablingWifiShouldClearExistingAccessPoints() throws Exception {
        WifiTracker tracker = createTrackerWithScanResultsAndAccessPoint1Connected();

        when(mockWifiManager.isWifiEnabled()).thenReturn(false);

        mAccessPointsChangedLatch = new CountDownLatch(1);
        tracker.mReceiver.onReceive(mContext, new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
        assertThat(mAccessPointsChangedLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();

        assertThat(tracker.getAccessPoints()).isEmpty();
    }

    @Test
    public void onConnectedChangedCallback_shouldNotBeInvokedWhenNoStateChange() throws Exception {
        WifiTracker tracker = createTrackerWithScanResultsAndAccessPoint1Connected();
        verify(mockWifiListener, times(1)).onConnectedChanged();

        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "connected", "test");

        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        tracker.mReceiver.onReceive(mContext, intent);

        verify(mockWifiListener, times(1)).onConnectedChanged();
    }

    @Test
    public void onConnectedChangedCallback_shouldBeInvokedWhenStateChanges() throws Exception {
        WifiTracker tracker = createTrackerWithScanResultsAndAccessPoint1Connected();
        verify(mockWifiListener, times(1)).onConnectedChanged();

        NetworkInfo networkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "Type Wifi", "subtype");
        networkInfo.setDetailedState(
                NetworkInfo.DetailedState.DISCONNECTED, "disconnected", "test");

        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        tracker.mReceiver.onReceive(mContext, intent);

        assertThat(tracker.isConnected()).isFalse();
        verify(mockWifiListener, times(2)).onConnectedChanged();
    }

    @Test
    public void updateNetworkInfoWithNewConnectedNetwork_switchesNetworks() throws Exception {
        WifiTracker tracker = createTrackerWithScanResultsAndAccessPoint1Connected();

        switchToNetwork2(tracker);

        List<AccessPoint> aps = tracker.getAccessPoints();
        assertThat(aps.get(0).getSsidStr()).isEqualTo(SSID_2);

        assertThat(aps.get(0).isReachable()).isTrue();
        assertThat(aps.get(1).isReachable()).isTrue();
    }
}
