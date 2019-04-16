/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.net.NetworkScoreManager.CACHE_FILTER_NONE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.net.INetworkRecommendationProvider;
import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.net.Uri;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.MockUtils;

import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Tests for {@link NetworkScoreService}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class NetworkScoreServiceTest {
    private static final String SSID = "ssid";
    private static final String SSID_2 = "ssid_2";
    private static final String SSID_3 = "ssid_3";
    private static final String INVALID_BSSID = "invalid_bssid";
    private static final ComponentName RECOMMENDATION_SERVICE_COMP =
            new ComponentName("newPackageName", "newScoringServiceClass");
    private static final String RECOMMENDATION_SERVICE_LABEL = "Test Recommendation Service";
    private static final ComponentName USE_WIFI_ENABLE_ACTIVITY_COMP =
            new ComponentName("useWifiPackageName", "enableUseWifiActivityClass");
    private static final ScoredNetwork SCORED_NETWORK =
            new ScoredNetwork(new NetworkKey(new WifiKey(quote(SSID), "00:00:00:00:00:00")),
                    null /* rssiCurve*/);
    private static final ScoredNetwork SCORED_NETWORK_2 =
            new ScoredNetwork(new NetworkKey(new WifiKey(quote(SSID_2), "00:00:00:00:00:00")),
                    null /* rssiCurve*/);
    private static final String NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID =
            "networkAvailableNotificationChannelId";
    private static final NetworkScorerAppData NEW_SCORER = new NetworkScorerAppData(
            1, RECOMMENDATION_SERVICE_COMP, RECOMMENDATION_SERVICE_LABEL,
            USE_WIFI_ENABLE_ACTIVITY_COMP, NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID);

    @Mock private NetworkScorerAppManager mNetworkScorerAppManager;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private INetworkScoreCache.Stub mNetworkScoreCache, mNetworkScoreCache2;
    @Mock private IBinder mScoreCacheIBinder, mScoreCacheIBinder2;
    @Mock private IBinder mServiceConnectionIBinder;
    @Mock private INetworkRecommendationProvider mRecommendationProvider;
    @Mock private UnaryOperator<List<ScoredNetwork>> mCurrentNetworkFilter;
    @Mock private UnaryOperator<List<ScoredNetwork>> mScanResultsFilter;
    @Mock private WifiInfo mWifiInfo;
    @Mock private NetworkScoreService.ScoringServiceConnection mServiceConnection;
    @Mock private PackageManagerInternal mPackageManagerInternal;
    @Captor private ArgumentCaptor<List<ScoredNetwork>> mScoredNetworkCaptor;
    @Captor private ArgumentCaptor<PackageManagerInternal.PackagesProvider> mPackagesProviderCaptor;

    private ContentResolver mContentResolver;
    private NetworkScoreService mNetworkScoreService;
    private HandlerThread mHandlerThread;
    private List<ScanResult> mScanResults;

    private static String quote(String str) {
        return String.format("\"%s\"", str);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mNetworkScoreCache.asBinder()).thenReturn(mScoreCacheIBinder);
        when(mNetworkScoreCache2.asBinder()).thenReturn(mScoreCacheIBinder2);
        when(mServiceConnectionIBinder.queryLocalInterface(anyString()))
                .thenReturn(mRecommendationProvider);
        mContentResolver = InstrumentationRegistry.getContext().getContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getResources()).thenReturn(mResources);
        when(mWifiInfo.getSSID()).thenReturn(SCORED_NETWORK.networkKey.wifiKey.ssid);
        when(mWifiInfo.getBSSID()).thenReturn(SCORED_NETWORK.networkKey.wifiKey.bssid);
        when(mServiceConnection.getAppData()).thenReturn(NEW_SCORER);
        when(mServiceConnection.getRecommendationProvider()).thenReturn(mRecommendationProvider);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);
        mHandlerThread = new HandlerThread("NetworkScoreServiceTest");
        mHandlerThread.start();
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
        mNetworkScoreService = new NetworkScoreService(mContext, mNetworkScorerAppManager,
                networkScorerAppData -> mServiceConnection, mHandlerThread.getLooper());
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = "NetworkScoreServiceTest_SSID";
        configuration.BSSID = "NetworkScoreServiceTest_BSSID";
        populateScanResults();
    }

    private void populateScanResults() {
        mScanResults = new ArrayList<>();
        mScanResults.add(createScanResult(SSID, SCORED_NETWORK.networkKey.wifiKey.bssid));
        mScanResults.add(createScanResult(SSID_2, SCORED_NETWORK_2.networkKey.wifiKey.bssid));
        mScanResults.add(createScanResult(SSID_3, "10:10:00:00:10:10"));
    }

    private ScanResult createScanResult(String ssid, String bssid) {
        ScanResult result = new ScanResult();
        result.wifiSsid = WifiSsid.createFromAsciiEncoded(ssid);
        result.BSSID = bssid;
        return result;
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quitSafely();
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Test
    public void testConstructor_setsUseOpenWifiPackagesProvider() {
        Settings.Global.putString(mContentResolver,
                Settings.Global.USE_OPEN_WIFI_PACKAGE, "com.some.app");

        verify(mPackageManagerInternal)
                .setUseOpenWifiAppPackagesProvider(mPackagesProviderCaptor.capture());

        String[] packages = mPackagesProviderCaptor.getValue().getPackages(0);
        assertEquals(1, packages.length);
        assertEquals("com.some.app", packages[0]);
    }

    @Test
    public void testConstructor_registersUseOpenWifiPackageContentObserver() {
        Settings.Global.putString(mContentResolver,
                Settings.Global.USE_OPEN_WIFI_PACKAGE, "com.some.other.app");

        verify(mPackageManagerInternal, timeout(500))
                .grantDefaultPermissionsToDefaultUseOpenWifiApp("com.some.other.app", 0);
    }

    @Test
    public void testOnUserUnlocked() {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);

        mNetworkScoreService.onUserUnlocked(0);

        verify(mNetworkScorerAppManager).updateState();
        verify(mNetworkScorerAppManager).migrateNetworkScorerAppSettingIfNeeded();
        verify(mServiceConnection).bind(mContext);
    }

    @Test
    public void testRequestScores_noPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
            .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES),
                anyString());
        try {
            mNetworkScoreService.requestScores(null);
            fail("REQUEST_NETWORK_SCORES not enforced.");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testRequestScores_providerNotConnected() throws Exception {
        assertFalse(mNetworkScoreService.requestScores(new NetworkKey[0]));
        verifyZeroInteractions(mRecommendationProvider);
    }

    @Test
    public void testRequestScores_providerThrowsRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mRecommendationProvider)
            .requestScores(any(NetworkKey[].class));
        mNetworkScoreService.onUserUnlocked(0);

        assertFalse(mNetworkScoreService.requestScores(new NetworkKey[0]));
    }

    @Test
    public void testRequestScores_providerAvailable() throws Exception {
        mNetworkScoreService.onUserUnlocked(0);

        final NetworkKey[] networks = new NetworkKey[0];
        assertTrue(mNetworkScoreService.requestScores(networks));
        verify(mRecommendationProvider).requestScores(networks);
    }

    @Test
    public void dispatchingContentObserver_nullUri() throws Exception {
        NetworkScoreService.DispatchingContentObserver observer =
                new NetworkScoreService.DispatchingContentObserver(mContext, null /*handler*/);

        observer.onChange(false, null);
        // nothing to assert or verify but since we passed in a null handler we'd see a NPE
        // if it were interacted with.
    }

    @Test
    public void dispatchingContentObserver_dispatchUri() throws Exception {
        final CountDownHandler handler = new CountDownHandler(mHandlerThread.getLooper());
        NetworkScoreService.DispatchingContentObserver observer =
                new NetworkScoreService.DispatchingContentObserver(mContext, handler);
        Uri uri = Uri.parse("content://settings/global/network_score_service_test");
        int expectedWhat = 24;
        observer.observe(uri, expectedWhat);

        observer.onChange(false, uri);
        final boolean msgHandled = handler.latch.await(3, TimeUnit.SECONDS);
        assertTrue(msgHandled);
        assertEquals(expectedWhat, handler.receivedWhat);
    }

    @Test
    public void testUpdateScores_notActiveScorer() {
        bindToScorer(false /*callerIsScorer*/);

        try {
            mNetworkScoreService.updateScores(new ScoredNetwork[0]);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testUpdateScores_oneRegisteredCache() throws RemoteException {
        bindToScorer(true /*callerIsScorer*/);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                mNetworkScoreCache, CACHE_FILTER_NONE);

        mNetworkScoreService.updateScores(new ScoredNetwork[]{SCORED_NETWORK});

        verify(mNetworkScoreCache).updateScores(mScoredNetworkCaptor.capture());

        assertEquals(1, mScoredNetworkCaptor.getValue().size());
        assertEquals(SCORED_NETWORK, mScoredNetworkCaptor.getValue().get(0));
    }

    @Test
    public void testUpdateScores_twoRegisteredCaches() throws RemoteException {
        bindToScorer(true /*callerIsScorer*/);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                mNetworkScoreCache, CACHE_FILTER_NONE);
        mNetworkScoreService.registerNetworkScoreCache(
                NetworkKey.TYPE_WIFI, mNetworkScoreCache2, CACHE_FILTER_NONE);

        // updateScores should update both caches
        mNetworkScoreService.updateScores(new ScoredNetwork[]{SCORED_NETWORK});

        verify(mNetworkScoreCache).updateScores(anyListOf(ScoredNetwork.class));
        verify(mNetworkScoreCache2).updateScores(anyListOf(ScoredNetwork.class));

        mNetworkScoreService.unregisterNetworkScoreCache(
                NetworkKey.TYPE_WIFI, mNetworkScoreCache2);

        // updateScores should only update the first cache since the 2nd has been unregistered
        mNetworkScoreService.updateScores(new ScoredNetwork[]{SCORED_NETWORK});

        verify(mNetworkScoreCache, times(2)).updateScores(anyListOf(ScoredNetwork.class));

        mNetworkScoreService.unregisterNetworkScoreCache(
                NetworkKey.TYPE_WIFI, mNetworkScoreCache);

        // updateScores should not update any caches since they are both unregistered
        mNetworkScoreService.updateScores(new ScoredNetwork[]{SCORED_NETWORK});

        // The register and unregister calls grab the binder from the score cache.
        verify(mNetworkScoreCache, atLeastOnce()).asBinder();
        verify(mNetworkScoreCache2, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(mNetworkScoreCache, mNetworkScoreCache2);
    }

    @Test
    public void testClearScores_notActiveScorer_noRequestNetworkScoresPermission() {
        bindToScorer(false /*callerIsScorer*/);
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
            .thenReturn(PackageManager.PERMISSION_DENIED);
        try {
            mNetworkScoreService.clearScores();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testClearScores_activeScorer_noRequestNetworkScoresPermission() {
        bindToScorer(true /*callerIsScorer*/);
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
            .thenReturn(PackageManager.PERMISSION_DENIED);

        mNetworkScoreService.clearScores();
    }

    @Test
    public void testClearScores_activeScorer() throws RemoteException {
        bindToScorer(true /*callerIsScorer*/);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);
        mNetworkScoreService.clearScores();

        verify(mNetworkScoreCache).clearScores();
    }

    @Test
    public void testClearScores_notActiveScorer_hasRequestNetworkScoresPermission()
            throws RemoteException {
        bindToScorer(false /*callerIsScorer*/);
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);
        mNetworkScoreService.clearScores();

        verify(mNetworkScoreCache).clearScores();
    }

    @Test
    public void testSetActiveScorer_noScoreNetworksPermission() {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(permission.SCORE_NETWORKS))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            mNetworkScoreService.setActiveScorer(null);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testSetActiveScorer_requestNetworkScoresPermission() {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mNetworkScoreService.setActiveScorer(null);
    }

    @Test
    public void testDisableScoring_notActiveScorer_noRequestNetworkScoresPermission() {
        bindToScorer(false /*callerIsScorer*/);
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            mNetworkScoreService.disableScoring();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testDisableScoring_activeScorer_noRequestNetworkScoresPermission() {
        bindToScorer(true /*callerIsScorer*/);
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        mNetworkScoreService.disableScoring();
    }

    @Test
    public void testGetAllValidScorer_noRequestNetworkScoresPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mNetworkScoreService.getAllValidScorers();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testGetAllValidScorer_requestNetworkScoresPermission() {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mNetworkScoreService.getAllValidScorers();
    }

    @Test
    public void testRegisterNetworkScoreCache_noRequestNetworkScoresPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mNetworkScoreService.registerNetworkScoreCache(
                NetworkKey.TYPE_WIFI, mNetworkScoreCache, CACHE_FILTER_NONE);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testUnregisterNetworkScoreCache_noRequestNetworkScoresPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mNetworkScoreService.unregisterNetworkScoreCache(
                    NetworkKey.TYPE_WIFI, mNetworkScoreCache);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testDump_doesNotCrash() {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);
        StringWriter stringWriter = new StringWriter();

        mNetworkScoreService.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);

        assertFalse(stringWriter.toString().isEmpty());
    }

    @Test
    public void testIsCallerActiveScorer_noBoundService() throws Exception {
        // No active scorer.
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(null);
        mNetworkScoreService.onUserUnlocked(0);

        mNetworkScoreService.isCallerActiveScorer(Binder.getCallingUid());

        verify(mServiceConnection, never()).getAppData();
    }

    @Test
    public void testIsCallerActiveScorer_boundServiceIsNotCaller() throws Exception {
        bindToScorer(false /*callerIsScorer*/);

        assertFalse(mNetworkScoreService.isCallerActiveScorer(Binder.getCallingUid()));
    }

    @Test
    public void testIsCallerActiveScorer_boundServiceIsCaller() throws Exception {
        bindToScorer(true /*callerIsScorer*/);

        assertTrue(mNetworkScoreService.isCallerActiveScorer(Binder.getCallingUid()));
    }

    @Test
    public void testGetActiveScorerPackage_notActive() throws Exception {
        // No active scorer.
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(null);
        mNetworkScoreService.onUserUnlocked(0);

        mNetworkScoreService.getActiveScorerPackage();

        verify(mServiceConnection, never()).getPackageName();
    }

    @Test
    public void testGetActiveScorerPackage_active() throws Exception {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);
        mNetworkScoreService.onUserUnlocked(0);

        mNetworkScoreService.getActiveScorerPackage();

        verify(mServiceConnection).getPackageName();
    }

    @Test
    public void testGetActiveScorerPackage_missingRequiredPermissions() throws Exception {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(permission.SCORE_NETWORKS))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            mNetworkScoreService.getActiveScorerPackage();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testGetActiveScorerPackage_noRequestScoresPermission() throws Exception {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(permission.SCORE_NETWORKS))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        try {
            mNetworkScoreService.getActiveScorerPackage();
        } catch (SecurityException e) {
            fail("Unexpected SecurityException");
        }
    }

    @Test
    public void testGetActiveScorerPackage_noScoreNetworksPermission() throws Exception {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(permission.SCORE_NETWORKS))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            mNetworkScoreService.getActiveScorerPackage();
        } catch (SecurityException e) {
            fail("Unexpected SecurityException");
        }
    }

    @Test
    public void testCacheUpdatingConsumer_nullFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList = Lists.newArrayList(SCORED_NETWORK);
        NetworkScoreService.FilteringCacheUpdatingConsumer consumer =
                new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                        new ArrayList<>(scoredNetworkList), NetworkKey.TYPE_WIFI,
                        mCurrentNetworkFilter, mScanResultsFilter);

        consumer.accept(mNetworkScoreCache, null /*cookie*/);

        verify(mNetworkScoreCache).updateScores(scoredNetworkList);
        verifyZeroInteractions(mCurrentNetworkFilter, mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_noneFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList = Lists.newArrayList(SCORED_NETWORK);
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                new ArrayList<>(scoredNetworkList),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        consumer.accept(mNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_NONE);

        verify(mNetworkScoreCache).updateScores(scoredNetworkList);
        verifyZeroInteractions(mCurrentNetworkFilter, mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_unknownFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList = Lists.newArrayList(SCORED_NETWORK);
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                new ArrayList<>(scoredNetworkList),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        consumer.accept(mNetworkScoreCache, -1 /*cookie*/);

        verify(mNetworkScoreCache).updateScores(scoredNetworkList);
        verifyZeroInteractions(mCurrentNetworkFilter, mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_nonIntFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList = Lists.newArrayList(SCORED_NETWORK);
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                new ArrayList<>(scoredNetworkList),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        consumer.accept(mNetworkScoreCache, "not an int" /*cookie*/);

        verify(mNetworkScoreCache).updateScores(scoredNetworkList);
        verifyZeroInteractions(mCurrentNetworkFilter, mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_emptyScoreList() throws Exception {
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                Collections.emptyList(),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        consumer.accept(mNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_NONE);

        verifyZeroInteractions(mNetworkScoreCache, mCurrentNetworkFilter, mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_currentNetworkFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList =
                Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2);
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                new ArrayList<>(scoredNetworkList),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        List<ScoredNetwork> filteredList = new ArrayList<>(scoredNetworkList);
        filteredList.remove(SCORED_NETWORK);
        when(mCurrentNetworkFilter.apply(scoredNetworkList)).thenReturn(filteredList);
        consumer.accept(mNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_CURRENT_NETWORK);

        verify(mNetworkScoreCache).updateScores(filteredList);
        verifyZeroInteractions(mScanResultsFilter);
    }

    @Test
    public void testCacheUpdatingConsumer_scanResultsFilter() throws Exception {
        List<ScoredNetwork> scoredNetworkList =
                Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2);
        NetworkScoreService.FilteringCacheUpdatingConsumer
                consumer = new NetworkScoreService.FilteringCacheUpdatingConsumer(mContext,
                new ArrayList<>(scoredNetworkList),
                NetworkKey.TYPE_WIFI, mCurrentNetworkFilter, mScanResultsFilter);

        List<ScoredNetwork> filteredList = new ArrayList<>(scoredNetworkList);
        filteredList.remove(SCORED_NETWORK);
        when(mScanResultsFilter.apply(scoredNetworkList)).thenReturn(filteredList);
        consumer.accept(mNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_SCAN_RESULTS);

        verify(mNetworkScoreCache).updateScores(filteredList);
        verifyZeroInteractions(mCurrentNetworkFilter);
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_nullWifiInfo() throws Exception {
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> null /*WifiInfo*/);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_invalidWifiInfo_nullSsid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(null);
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_invalidWifiInfo_noneSsid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(WifiSsid.NONE);
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_invalidWifiInfo_emptySsid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn("");
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_invalidWifiInfo_invalidBssid() throws Exception {
        when(mWifiInfo.getBSSID()).thenReturn(INVALID_BSSID);
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_scoreFiltered() throws Exception {
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        List<ScoredNetwork> expectedList = Collections.singletonList(SCORED_NETWORK);
        assertEquals(expectedList, actualList);
    }

    @Test
    public void testCurrentNetworkScoreCacheFilter_currentNetworkNotInList() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn("\"notInList\"");
        NetworkScoreService.CurrentNetworkScoreCacheFilter cacheFilter =
                new NetworkScoreService.CurrentNetworkScoreCacheFilter(() -> mWifiInfo);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testScanResultsScoreCacheFilter_emptyScanResults() throws Exception {
        NetworkScoreService.ScanResultsScoreCacheFilter cacheFilter =
                new NetworkScoreService.ScanResultsScoreCacheFilter(Collections::emptyList);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testScanResultsScoreCacheFilter_invalidScanResults() throws Exception {
        List<ScanResult> invalidScanResults = Lists.newArrayList(
                new ScanResult(),
                createScanResult("", SCORED_NETWORK.networkKey.wifiKey.bssid),
                createScanResult(WifiSsid.NONE, SCORED_NETWORK.networkKey.wifiKey.bssid),
                createScanResult(SSID, null),
                createScanResult(SSID, INVALID_BSSID)
        );
        NetworkScoreService.ScanResultsScoreCacheFilter cacheFilter =
                new NetworkScoreService.ScanResultsScoreCacheFilter(() -> invalidScanResults);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2));

        assertTrue(actualList.isEmpty());
    }

    @Test
    public void testScanResultsScoreCacheFilter_scoresFiltered() throws Exception {
        NetworkScoreService.ScanResultsScoreCacheFilter cacheFilter =
                new NetworkScoreService.ScanResultsScoreCacheFilter(() -> mScanResults);

        ScoredNetwork unmatchedScore =
                new ScoredNetwork(new NetworkKey(new WifiKey(quote("newSsid"),
                        "00:00:00:00:00:00")), null /* rssiCurve*/);

        List<ScoredNetwork> actualList =
                cacheFilter.apply(Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2,
                        unmatchedScore));

        List<ScoredNetwork> expectedList = Lists.newArrayList(SCORED_NETWORK, SCORED_NETWORK_2);
        assertEquals(expectedList, actualList);
    }

    @Test
    public void testGetActiveScorer_notConnected_canRequestScores() throws Exception {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(null);
        mNetworkScoreService.onUserUnlocked(0);

        mNetworkScoreService.getActiveScorer();

        verify(mServiceConnection, never()).getAppData();
    }

    @Test
    public void testGetActiveScorer_notConnected_canNotRequestScores() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());
        try {
            mNetworkScoreService.getActiveScorer();
            fail("SecurityException expected.");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testGetActiveScorer_connected_canRequestScores()
            throws Exception {
        when(mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        mNetworkScoreService.onUserUnlocked(0);

        mNetworkScoreService.getActiveScorer();

        verify(mServiceConnection).getAppData();
    }

    @Test
    public void testGetActiveScorer_connected_canNotRequestScores()
            throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());
        bindToScorer(false);
        try {
            mNetworkScoreService.getActiveScorer();
            fail("SecurityException expected.");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testServiceConnection_bind_notBound() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);
        connection.bind(mContext);

        verify(mContext).bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(RECOMMENDATION_SERVICE_COMP)),
                eq(connection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
    }

    @Test
    public void testServiceConnection_bind_alreadyBound() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);

        when(mContext.bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(RECOMMENDATION_SERVICE_COMP)),
                eq(connection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM))).thenReturn(true /*bound*/);

        // Calling bind more than once should only result in 1 bindService call.
        connection.bind(mContext);
        connection.bind(mContext);

        verify(mContext, times(1)).bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(RECOMMENDATION_SERVICE_COMP)),
                eq(connection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
    }

    @Test
    public void testServiceConnection_bindFails() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);

        when(mContext.bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(RECOMMENDATION_SERVICE_COMP)),
                eq(connection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM))).thenReturn(false /*bound*/);

        connection.bind(mContext);

        verify(mContext).unbindService(connection);
    }

    @Test
    public void testServiceConnection_unbind_notBound() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);

        connection.unbind(mContext);

        verify(mContext, never()).unbindService(connection);
    }

    @Test
    public void testServiceConnection_unbind_alreadyBound() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);

        when(mContext.bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(RECOMMENDATION_SERVICE_COMP)),
                eq(connection),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM))).thenReturn(true /*bound*/);

        connection.bind(mContext);
        connection.unbind(mContext);

        verify(mContext).unbindService(connection);
    }

    @Test
    public void testServiceConnection_dump_doesNotCrash() throws Exception {
        NetworkScoreService.ScoringServiceConnection connection = new NetworkScoreService
                .ScoringServiceConnection(NEW_SCORER);
        final StringWriter stringWriter = new StringWriter();

        connection.dump(new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);

        assertFalse(stringWriter.toString().isEmpty());
    }

    private void bindToScorer(boolean callerIsScorer) {
        final int callingUid = callerIsScorer ? Binder.getCallingUid() : Binder.getCallingUid() + 1;
        NetworkScorerAppData appData = new NetworkScorerAppData(callingUid,
                RECOMMENDATION_SERVICE_COMP, RECOMMENDATION_SERVICE_LABEL,
                USE_WIFI_ENABLE_ACTIVITY_COMP, NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID);
        when(mServiceConnection.getAppData()).thenReturn(appData);
        mNetworkScoreService.onUserUnlocked(0);
    }

    private static class CountDownHandler extends Handler {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int receivedWhat;

        CountDownHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            receivedWhat = msg.what;
            latch.countDown();
        }
    }
}
