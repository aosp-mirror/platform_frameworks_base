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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class UpstreamNetworkMonitorTest {
    private static final int EVENT_UNM_UPDATE = 1;

    @Mock private Context mContext;
    @Mock private IConnectivityManager mCS;

    private TestConnectivityManager mCM;
    private UpstreamNetworkMonitor mUNM;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        reset(mContext);
        reset(mCS);

        mCM = new TestConnectivityManager(mContext, mCS);
        mUNM = new UpstreamNetworkMonitor(null, EVENT_UNM_UPDATE, (ConnectivityManager) mCM);
    }

    @Test
    public void testDoesNothingBeforeStarted() {
        UpstreamNetworkMonitor unm = new UpstreamNetworkMonitor(null, null, EVENT_UNM_UPDATE);
        assertFalse(unm.mobileNetworkRequested());
        // Given a null Context, and therefore a null ConnectivityManager,
        // these would cause an exception, if they actually attempted anything.
        unm.mobileUpstreamRequiresDun(true);
        unm.mobileUpstreamRequiresDun(false);
    }

    @Test
    public void testDefaultNetworkIsTracked() throws Exception {
        assertEquals(0, mCM.trackingDefault.size());

        mUNM.start();
        assertEquals(1, mCM.trackingDefault.size());

        mUNM.stop();
        assertTrue(mCM.isEmpty());
    }

    @Test
    public void testListensForDunNetworks() throws Exception {
        assertTrue(mCM.listening.isEmpty());

        mUNM.start();
        assertFalse(mCM.listening.isEmpty());
        assertTrue(mCM.isListeningForDun());

        mUNM.stop();
        assertTrue(mCM.isEmpty());
    }

    @Test
    public void testCanRequestMobileNetwork() throws Exception {
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.start();
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.mobileUpstreamRequiresDun(false);
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.registerMobileNetworkRequest();
        assertTrue(mUNM.mobileNetworkRequested());
        assertEquals(1, mCM.requested.size());
        assertEquals(1, mCM.legacyTypeMap.size());
        assertEquals(Integer.valueOf(TYPE_MOBILE_HIPRI),
                mCM.legacyTypeMap.values().iterator().next());
        assertFalse(mCM.isDunRequested());

        mUNM.stop();
        assertFalse(mUNM.mobileNetworkRequested());
        assertTrue(mCM.isEmpty());
    }

    @Test
    public void testCanRequestDunNetwork() throws Exception {
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.start();
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.mobileUpstreamRequiresDun(true);
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.registerMobileNetworkRequest();
        assertTrue(mUNM.mobileNetworkRequested());
        assertEquals(1, mCM.requested.size());
        assertEquals(1, mCM.legacyTypeMap.size());
        assertEquals(Integer.valueOf(TYPE_MOBILE_DUN),
                mCM.legacyTypeMap.values().iterator().next());
        assertTrue(mCM.isDunRequested());

        mUNM.stop();
        assertFalse(mUNM.mobileNetworkRequested());
        assertTrue(mCM.isEmpty());
    }

    private static class TestConnectivityManager extends ConnectivityManager {
        public Set<NetworkCallback> trackingDefault = new HashSet<>();
        public Map<NetworkCallback, NetworkRequest> listening = new HashMap<>();
        public Map<NetworkCallback, NetworkRequest> requested = new HashMap<>();
        public Map<NetworkCallback, Integer> legacyTypeMap = new HashMap<>();

        public TestConnectivityManager(Context ctx, IConnectivityManager svc) {
            super(ctx, svc);
        }

        boolean isEmpty() {
            return trackingDefault.isEmpty() &&
                   listening.isEmpty() &&
                   requested.isEmpty() &&
                   legacyTypeMap.isEmpty();
        }

        boolean isListeningForDun() {
            for (NetworkRequest req : listening.values()) {
                if (req.networkCapabilities.hasCapability(NET_CAPABILITY_DUN)) {
                    return true;
                }
            }
            return false;
        }

        boolean isDunRequested() {
            for (NetworkRequest req : requested.values()) {
                if (req.networkCapabilities.hasCapability(NET_CAPABILITY_DUN)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void requestNetwork(NetworkRequest req, NetworkCallback cb) {
            assertFalse(requested.containsKey(cb));
            requested.put(cb, req);
        }

        @Override
        public void requestNetwork(NetworkRequest req, NetworkCallback cb,
                int timeoutMs, int legacyType) {
            assertFalse(requested.containsKey(cb));
            requested.put(cb, req);
            assertFalse(legacyTypeMap.containsKey(cb));
            if (legacyType != ConnectivityManager.TYPE_NONE) {
                legacyTypeMap.put(cb, legacyType);
            }
        }

        @Override
        public void registerNetworkCallback(NetworkRequest req, NetworkCallback cb) {
            assertFalse(listening.containsKey(cb));
            listening.put(cb, req);
        }

        @Override
        public void registerDefaultNetworkCallback(NetworkCallback cb) {
            assertFalse(trackingDefault.contains(cb));
            trackingDefault.add(cb);
        }

        @Override
        public void unregisterNetworkCallback(NetworkCallback cb) {
            if (trackingDefault.contains(cb)) {
                trackingDefault.remove(cb);
            } else if (listening.containsKey(cb)) {
                listening.remove(cb);
            } else if (requested.containsKey(cb)) {
                requested.remove(cb);
                legacyTypeMap.remove(cb);
            }

            assertFalse(trackingDefault.contains(cb));
            assertFalse(listening.containsKey(cb));
            assertFalse(requested.containsKey(cb));
        }
    }
}
