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
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppManager;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.devicepolicy.MockUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NetworkScoreService}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class NetworkScoreServiceTest {
    private static final ScoredNetwork SCORED_NETWORK =
            new ScoredNetwork(new NetworkKey(new WifiKey("\"ssid\"", "00:00:00:00:00:00")),
                    null /* rssiCurve*/);
    private static final NetworkScorerAppData PREV_SCORER = new NetworkScorerAppData(
            "prevPackageName", 0, "prevScorerName", null /* configurationActivityClassName */,
            "prevScoringServiceClass");
    private static final NetworkScorerAppData NEW_SCORER = new NetworkScorerAppData(
            "newPackageName", 1, "newScorerName", null /* configurationActivityClassName */,
            "newScoringServiceClass");

    @Mock private PackageManager mPackageManager;
    @Mock private NetworkScorerAppManager mNetworkScorerAppManager;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private INetworkScoreCache.Stub mNetworkScoreCache, mNetworkScoreCache2;
    @Mock private IBinder mIBinder, mIBinder2;
    @Captor private ArgumentCaptor<List<ScoredNetwork>> mScoredNetworkCaptor;

    private ContentResolver mContentResolver;
    private NetworkScoreService mNetworkScoreService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mNetworkScoreCache.asBinder()).thenReturn(mIBinder);
        when(mNetworkScoreCache2.asBinder()).thenReturn(mIBinder2);
        mContentResolver = InstrumentationRegistry.getContext().getContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getResources()).thenReturn(mResources);
        mNetworkScoreService = new NetworkScoreService(mContext, mNetworkScorerAppManager);
    }

    @Test
    public void testSystemReady_networkScorerProvisioned() throws Exception {
        Settings.Global.putInt(mContentResolver, Global.NETWORK_SCORING_PROVISIONED, 1);

        mNetworkScoreService.systemReady();

        verify(mNetworkScorerAppManager, never()).setActiveScorer(anyString());
    }

    @Test
    public void testSystemReady_networkScorerNotProvisioned_defaultScorer() throws Exception {
        Settings.Global.putInt(mContentResolver, Global.NETWORK_SCORING_PROVISIONED, 0);

        when(mResources.getString(R.string.config_defaultNetworkScorerPackageName))
                .thenReturn(NEW_SCORER.mPackageName);

        mNetworkScoreService.systemReady();

        verify(mNetworkScorerAppManager).setActiveScorer(NEW_SCORER.mPackageName);
        assertEquals(1,
                Settings.Global.getInt(mContentResolver, Global.NETWORK_SCORING_PROVISIONED));

    }

    @Test
    public void testSystemReady_networkScorerNotProvisioned_noDefaultScorer() throws Exception {
        Settings.Global.putInt(mContentResolver, Global.NETWORK_SCORING_PROVISIONED, 0);

        when(mResources.getString(R.string.config_defaultNetworkScorerPackageName))
                .thenReturn(null);

        mNetworkScoreService.systemReady();

        verify(mNetworkScorerAppManager, never()).setActiveScorer(anyString());
        assertEquals(1,
                Settings.Global.getInt(mContentResolver, Global.NETWORK_SCORING_PROVISIONED));
    }

    @Test
    public void testSystemRunning() {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(NEW_SCORER);

        mNetworkScoreService.systemRunning();

        verify(mContext).bindServiceAsUser(MockUtils.checkIntent(new Intent().setComponent(
                new ComponentName(NEW_SCORER.mPackageName, NEW_SCORER.mScoringServiceClassName))),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
    }

    @Test
    public void testUpdateScores_notActiveScorer() {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(false);

        try {
            mNetworkScoreService.updateScores(new ScoredNetwork[0]);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testUpdateScores_oneRegisteredCache() throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(true);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                mNetworkScoreCache, CACHE_FILTER_NONE);

        mNetworkScoreService.updateScores(new ScoredNetwork[]{SCORED_NETWORK});

        verify(mNetworkScoreCache).updateScores(mScoredNetworkCaptor.capture());

        assertEquals(1, mScoredNetworkCaptor.getValue().size());
        assertEquals(SCORED_NETWORK, mScoredNetworkCaptor.getValue().get(0));
    }

    @Test
    public void testUpdateScores_twoRegisteredCaches() throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(true);

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
    public void testClearScores_notActiveScorer_noBroadcastNetworkPermission() {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(false);
        when(mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED))
            .thenReturn(PackageManager.PERMISSION_DENIED);
        try {
            mNetworkScoreService.clearScores();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testClearScores_activeScorer_noBroadcastNetworkPermission() {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(true);
        when(mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED))
            .thenReturn(PackageManager.PERMISSION_DENIED);

        mNetworkScoreService.clearScores();
    }

    @Test
    public void testClearScores_activeScorer() throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(true);

        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);
        mNetworkScoreService.clearScores();

        verify(mNetworkScoreCache).clearScores();
    }

    @Test
    public void testClearScores_notActiveScorer_hasBroadcastNetworkPermission()
            throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(false);
        when(mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED))
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
    public void testSetActiveScorer_failure() throws RemoteException {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(PREV_SCORER);
        when(mNetworkScorerAppManager.setActiveScorer(NEW_SCORER.mPackageName)).thenReturn(false);
        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);

        boolean success = mNetworkScoreService.setActiveScorer(NEW_SCORER.mPackageName);

        assertFalse(success);
        verify(mNetworkScoreCache).clearScores();
        verify(mContext).bindServiceAsUser(MockUtils.checkIntent(new Intent().setComponent(
                new ComponentName(PREV_SCORER.mPackageName, PREV_SCORER.mScoringServiceClassName))),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
    }

    @Test
    public void testSetActiveScorer_success() throws RemoteException {
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(PREV_SCORER, NEW_SCORER);
        when(mNetworkScorerAppManager.setActiveScorer(NEW_SCORER.mPackageName)).thenReturn(true);
        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);

        boolean success = mNetworkScoreService.setActiveScorer(NEW_SCORER.mPackageName);

        assertTrue(success);
        verify(mNetworkScoreCache).clearScores();
        verify(mContext).bindServiceAsUser(MockUtils.checkIntent(new Intent().setComponent(
                new ComponentName(NEW_SCORER.mPackageName, NEW_SCORER.mScoringServiceClassName))),
                any(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(UserHandle.SYSTEM));
        verify(mContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(NetworkScoreManager.ACTION_SCORER_CHANGED),
                eq(UserHandle.SYSTEM));
    }

    @Test
    public void testDisableScoring_notActiveScorer_noBroadcastNetworkPermission() {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(false);
        when(mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            mNetworkScoreService.disableScoring();
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }

    }

    @Test
    public void testDisableScoring_activeScorer() throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(true);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(PREV_SCORER, null);
        when(mNetworkScorerAppManager.setActiveScorer(null)).thenReturn(true);
        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);

        mNetworkScoreService.disableScoring();

        verify(mNetworkScoreCache).clearScores();
        verify(mContext).sendBroadcastAsUser(
                MockUtils.checkIntent(new Intent(NetworkScoreManager.ACTION_SCORER_CHANGED)
                        .setPackage(PREV_SCORER.mPackageName)),
                eq(UserHandle.SYSTEM));
        verify(mContext, never()).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
    }

    @Test
    public void testDisableScoring_notActiveScorer_hasBroadcastNetworkPermission()
            throws RemoteException {
        when(mNetworkScorerAppManager.isCallerActiveScorer(anyInt())).thenReturn(false);
        when(mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mNetworkScorerAppManager.getActiveScorer()).thenReturn(PREV_SCORER, null);
        when(mNetworkScorerAppManager.setActiveScorer(null)).thenReturn(true);
        mNetworkScoreService.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache,
                CACHE_FILTER_NONE);

        mNetworkScoreService.disableScoring();

        verify(mNetworkScoreCache).clearScores();
        verify(mContext).sendBroadcastAsUser(
                MockUtils.checkIntent(new Intent(NetworkScoreManager.ACTION_SCORER_CHANGED)
                        .setPackage(PREV_SCORER.mPackageName)),
                eq(UserHandle.SYSTEM));
        verify(mContext, never()).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
    }

    @Test
    public void testRegisterNetworkScoreCache_noBroadcastNetworkPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(permission.BROADCAST_NETWORK_PRIVILEGED), anyString());

        try {
            mNetworkScoreService.registerNetworkScoreCache(
                NetworkKey.TYPE_WIFI, mNetworkScoreCache, CACHE_FILTER_NONE);
            fail("SecurityException expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testUnregisterNetworkScoreCache_noBroadcastNetworkPermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(permission.BROADCAST_NETWORK_PRIVILEGED), anyString());

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
}
