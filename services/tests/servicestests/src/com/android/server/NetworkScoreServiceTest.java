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

import static android.net.NetworkRecommendationProvider.EXTRA_RECOMMENDATION_RESULT;
import static android.net.NetworkRecommendationProvider.EXTRA_SEQUENCE;
import static android.net.NetworkScoreManager.CACHE_FILTER_NONE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.INetworkRecommendationProvider;
import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppManager;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.WifiConfiguration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.MockUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Tests for {@link NetworkScoreService}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class NetworkScoreServiceTest {
    private static final ScoredNetwork SCORED_NETWORK =
            new ScoredNetwork(new NetworkKey(new WifiKey("\"ssid\"", "00:00:00:00:00:00")),
                    null /* rssiCurve*/);
    private static final NetworkScorerAppData NEW_SCORER =
        new NetworkScorerAppData("newPackageName", 1, "newScoringServiceClass");

    @Mock private PackageManager mPackageManager;
    @Mock private NetworkScorerAppManager mNetworkScorerAppManager;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private INetworkScoreCache.Stub mNetworkScoreCache, mNetworkScoreCache2;
    @Mock private IBinder mIBinder, mIBinder2;
    @Mock private INetworkRecommendationProvider mRecommendationProvider;
    @Captor private ArgumentCaptor<List<ScoredNetwork>> mScoredNetworkCaptor;

    private ContentResolver mContentResolver;
    private NetworkScoreService mNetworkScoreService;
    private RecommendationRequest mRecommendationRequest;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mNetworkScoreCache.asBinder()).thenReturn(mIBinder);
        when(mNetworkScoreCache2.asBinder()).thenReturn(mIBinder2);
        mContentResolver = InstrumentationRegistry.getContext().getContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getResources()).thenReturn(mResources);
        mNetworkScoreService = new NetworkScoreService(mContext, mNetworkScorerAppManager);
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = "NetworkScoreServiceTest_SSID";
        configuration.BSSID = "NetworkScoreServiceTest_BSSID";
        mRecommendationRequest = new RecommendationRequest.Builder()
            .setCurrentRecommendedWifiConfig(configuration).build();
    }

    @Test
    public void testSystemRunning() {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);

        mNetworkScoreService.systemRunning();

        verify(mContext).bindServiceAsUser(MockUtils.checkIntent(
                new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS)
                        .setComponent(new ComponentName(NEW_SCORER.packageName,
                                NEW_SCORER.recommendationServiceClassName))),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
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
        injectProvider();
        doThrow(new RemoteException()).when(mRecommendationProvider)
            .requestScores(any(NetworkKey[].class));

        assertFalse(mNetworkScoreService.requestScores(new NetworkKey[0]));
    }

    @Test
    public void testRequestScores_providerAvailable() throws Exception {
        injectProvider();

        final NetworkKey[] networks = new NetworkKey[0];
        assertTrue(mNetworkScoreService.requestScores(networks));
        verify(mRecommendationProvider).requestScores(networks);
    }

    @Test
    public void testRequestRecommendation_noPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
            .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES),
                anyString());
        try {
            mNetworkScoreService.requestRecommendation(mRecommendationRequest);
            fail("REQUEST_NETWORK_SCORES not enforced.");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testRequestRecommendation_mainThread() throws Exception {
        when(mContext.getMainLooper()).thenReturn(Looper.myLooper());
        try {
            mNetworkScoreService.requestRecommendation(mRecommendationRequest);
            fail("requestRecommendation run on main thread.");
        } catch (RuntimeException e) {
            // expected
        }
    }

    @Test
    public void testRequestRecommendation_providerNotConnected() throws Exception {
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());

        final RecommendationResult result =
                mNetworkScoreService.requestRecommendation(mRecommendationRequest);
        assertNotNull(result);
        assertEquals(mRecommendationRequest.getCurrentSelectedConfig(),
                result.getWifiConfiguration());
    }

    @Test
    public void testRequestRecommendation_providerThrowsRemoteException() throws Exception {
        injectProvider();
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        doThrow(new RemoteException()).when(mRecommendationProvider)
                .requestRecommendation(eq(mRecommendationRequest), isA(IRemoteCallback.class),
                        anyInt());

        final RecommendationResult result =
                mNetworkScoreService.requestRecommendation(mRecommendationRequest);
        assertNotNull(result);
        assertEquals(mRecommendationRequest.getCurrentSelectedConfig(),
                result.getWifiConfiguration());
    }

    @Test
    public void testRequestRecommendation_resultReturned() throws Exception {
        injectProvider();
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        final WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = "testRequestRecommendation_resultReturned_SSID";
        wifiConfiguration.BSSID = "testRequestRecommendation_resultReturned_BSSID";
        final RecommendationResult providerResult = RecommendationResult
                .createConnectRecommendation(wifiConfiguration);
        final Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_RECOMMENDATION_RESULT, providerResult);
        doAnswer(invocation -> {
            bundle.putInt(EXTRA_SEQUENCE, invocation.getArgumentAt(2, int.class));
            invocation.getArgumentAt(1, IRemoteCallback.class).sendResult(bundle);
            return null;
        }).when(mRecommendationProvider)
                .requestRecommendation(eq(mRecommendationRequest), isA(IRemoteCallback.class),
                        anyInt());

        final RecommendationResult result =
                mNetworkScoreService.requestRecommendation(mRecommendationRequest);
        assertNotNull(result);
        assertEquals(providerResult.getWifiConfiguration().SSID,
                result.getWifiConfiguration().SSID);
        assertEquals(providerResult.getWifiConfiguration().BSSID,
                result.getWifiConfiguration().BSSID);
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
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.SCORE_NETWORKS), anyString());

        try {
            mNetworkScoreService.setActiveScorer(null);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
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
    public void testDump_noDumpPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(permission.DUMP), anyString());

        try {
            mNetworkScoreService.dump(
                    new FileDescriptor(), new PrintWriter(new StringWriter()), new String[0]);
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
        mNetworkScoreService.systemRunning();

        assertFalse(mNetworkScoreService.isCallerActiveScorer(Binder.getCallingUid()));
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
        mNetworkScoreService.systemRunning();

        assertNull(mNetworkScoreService.getActiveScorerPackage());
    }

    @Test
    public void testGetActiveScorerPackage_active() throws Exception {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);
        mNetworkScoreService.systemRunning();

        assertEquals(NEW_SCORER.packageName, mNetworkScoreService.getActiveScorerPackage());
    }

    // "injects" the mock INetworkRecommendationProvider into the NetworkScoreService.
    private void injectProvider() {
        final ComponentName componentName = new ComponentName(NEW_SCORER.packageName,
                NEW_SCORER.recommendationServiceClassName);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);
        when(mContext.bindServiceAsUser(isA(Intent.class), isA(ServiceConnection.class), anyInt(),
                isA(UserHandle.class))).thenAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                IBinder mockBinder = mock(IBinder.class);
                when(mockBinder.queryLocalInterface(anyString()))
                        .thenReturn(mRecommendationProvider);
                invocation.getArgumentAt(1, ServiceConnection.class)
                        .onServiceConnected(componentName, mockBinder);
                return true;
            }
        });
        mNetworkScoreService.systemRunning();
    }

    private void bindToScorer(boolean callerIsScorer) {
        final int callingUid = callerIsScorer ? Binder.getCallingUid() : 0;
        NetworkScorerAppData appData = new NetworkScorerAppData(NEW_SCORER.packageName,
                callingUid, NEW_SCORER.recommendationServiceClassName);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(appData);
        when(mContext.bindServiceAsUser(isA(Intent.class), isA(ServiceConnection.class), anyInt(),
                isA(UserHandle.class))).thenReturn(true);
        mNetworkScoreService.systemRunning();
    }
}
