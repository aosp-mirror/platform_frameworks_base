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
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.Message;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UpstreamNetworkMonitorTest {
    private static final int EVENT_UNM_UPDATE = 1;

    private static final boolean INCLUDES = true;
    private static final boolean EXCLUDES = false;

    // Actual contents of the request don't matter for this test. The lack of
    // any specific TRANSPORT_* is sufficient to identify this request.
    private static final NetworkRequest mDefaultRequest = new NetworkRequest.Builder().build();

    @Mock private Context mContext;
    @Mock private EntitlementManager mEntitleMgr;
    @Mock private IConnectivityManager mCS;
    @Mock private SharedLog mLog;

    private TestStateMachine mSM;
    private TestConnectivityManager mCM;
    private UpstreamNetworkMonitor mUNM;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        reset(mContext);
        reset(mCS);
        reset(mLog);
        when(mLog.forSubComponent(anyString())).thenReturn(mLog);
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(true);

        mCM = spy(new TestConnectivityManager(mContext, mCS));
        mSM = new TestStateMachine();
        mUNM = new UpstreamNetworkMonitor(
                (ConnectivityManager) mCM, mSM, mLog, EVENT_UNM_UPDATE);
    }

    @After public void tearDown() throws Exception {
        if (mSM != null) {
            mSM.quit();
            mSM = null;
        }
    }

    @Test
    public void testStopWithoutStartIsNonFatal() {
        mUNM.stop();
        mUNM.stop();
        mUNM.stop();
    }

    @Test
    public void testDoesNothingBeforeTrackDefaultAndStarted() throws Exception {
        assertTrue(mCM.hasNoCallbacks());
        assertFalse(mUNM.mobileNetworkRequested());

        mUNM.updateMobileRequiresDun(true);
        assertTrue(mCM.hasNoCallbacks());
        mUNM.updateMobileRequiresDun(false);
        assertTrue(mCM.hasNoCallbacks());
    }

    @Test
    public void testDefaultNetworkIsTracked() throws Exception {
        assertTrue(mCM.hasNoCallbacks());
        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);

        mUNM.startObserveAllNetworks();
        assertEquals(1, mCM.trackingDefault.size());

        mUNM.stop();
        assertTrue(mCM.onlyHasDefaultCallbacks());
    }

    @Test
    public void testListensForAllNetworks() throws Exception {
        assertTrue(mCM.listening.isEmpty());

        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        mUNM.startObserveAllNetworks();
        assertFalse(mCM.listening.isEmpty());
        assertTrue(mCM.isListeningForAll());

        mUNM.stop();
        assertTrue(mCM.onlyHasDefaultCallbacks());
    }

    @Test
    public void testCallbacksRegistered() {
        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        verify(mCM, times(1)).requestNetwork(
                eq(mDefaultRequest), any(NetworkCallback.class), any(Handler.class));
        mUNM.startObserveAllNetworks();
        verify(mCM, times(1)).registerNetworkCallback(
                any(NetworkRequest.class), any(NetworkCallback.class), any(Handler.class));

        mUNM.stop();
        verify(mCM, times(1)).unregisterNetworkCallback(any(NetworkCallback.class));
    }

    @Test
    public void testRequestsMobileNetwork() throws Exception {
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.startObserveAllNetworks();
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.updateMobileRequiresDun(false);
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.registerMobileNetworkRequest();
        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_HIPRI);
        assertFalse(mCM.isDunRequested());

        mUNM.stop();
        assertFalse(mUNM.mobileNetworkRequested());
        assertTrue(mCM.hasNoCallbacks());
    }

    @Test
    public void testDuplicateMobileRequestsIgnored() throws Exception {
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.startObserveAllNetworks();
        verify(mCM, times(1)).registerNetworkCallback(
                any(NetworkRequest.class), any(NetworkCallback.class), any(Handler.class));
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.updateMobileRequiresDun(true);
        mUNM.registerMobileNetworkRequest();
        verify(mCM, times(1)).requestNetwork(
                any(NetworkRequest.class), any(NetworkCallback.class), anyInt(), anyInt(),
                any(Handler.class));

        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_DUN);
        assertTrue(mCM.isDunRequested());

        // Try a few things that must not result in any state change.
        mUNM.registerMobileNetworkRequest();
        mUNM.updateMobileRequiresDun(true);
        mUNM.registerMobileNetworkRequest();

        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_DUN);
        assertTrue(mCM.isDunRequested());

        mUNM.stop();
        verify(mCM, times(2)).unregisterNetworkCallback(any(NetworkCallback.class));

        verifyNoMoreInteractions(mCM);
    }

    @Test
    public void testRequestsDunNetwork() throws Exception {
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.startObserveAllNetworks();
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.updateMobileRequiresDun(true);
        assertFalse(mUNM.mobileNetworkRequested());
        assertEquals(0, mCM.requested.size());

        mUNM.registerMobileNetworkRequest();
        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_DUN);
        assertTrue(mCM.isDunRequested());

        mUNM.stop();
        assertFalse(mUNM.mobileNetworkRequested());
        assertTrue(mCM.hasNoCallbacks());
    }

    @Test
    public void testUpdateMobileRequiresDun() throws Exception {
        mUNM.startObserveAllNetworks();

        // Test going from no-DUN to DUN correctly re-registers callbacks.
        mUNM.updateMobileRequiresDun(false);
        mUNM.registerMobileNetworkRequest();
        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_HIPRI);
        assertFalse(mCM.isDunRequested());
        mUNM.updateMobileRequiresDun(true);
        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_DUN);
        assertTrue(mCM.isDunRequested());

        // Test going from DUN to no-DUN correctly re-registers callbacks.
        mUNM.updateMobileRequiresDun(false);
        assertTrue(mUNM.mobileNetworkRequested());
        assertUpstreamTypeRequested(TYPE_MOBILE_HIPRI);
        assertFalse(mCM.isDunRequested());

        mUNM.stop();
        assertFalse(mUNM.mobileNetworkRequested());
    }

    @Test
    public void testSelectPreferredUpstreamType() throws Exception {
        final Collection<Integer> preferredTypes = new ArrayList<>();
        preferredTypes.add(TYPE_WIFI);

        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        mUNM.startObserveAllNetworks();
        // There are no networks, so there is nothing to select.
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));

        final TestNetworkAgent wifiAgent = new TestNetworkAgent(mCM, TRANSPORT_WIFI);
        wifiAgent.fakeConnect();
        // WiFi is up, we should prefer it.
        assertSatisfiesLegacyType(TYPE_WIFI, mUNM.selectPreferredUpstreamType(preferredTypes));
        wifiAgent.fakeDisconnect();
        // There are no networks, so there is nothing to select.
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));

        final TestNetworkAgent cellAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        cellAgent.fakeConnect();
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));

        preferredTypes.add(TYPE_MOBILE_DUN);
        // This is coupled with preferred types in TetheringConfiguration.
        mUNM.updateMobileRequiresDun(true);
        // DUN is available, but only use regular cell: no upstream selected.
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));
        preferredTypes.remove(TYPE_MOBILE_DUN);
        // No WiFi, but our preferred flavour of cell is up.
        preferredTypes.add(TYPE_MOBILE_HIPRI);
        // This is coupled with preferred types in TetheringConfiguration.
        mUNM.updateMobileRequiresDun(false);
        assertSatisfiesLegacyType(TYPE_MOBILE_HIPRI,
                mUNM.selectPreferredUpstreamType(preferredTypes));
        // Check to see we filed an explicit request.
        assertEquals(1, mCM.requested.size());
        NetworkRequest netReq = (NetworkRequest) mCM.requested.values().toArray()[0];
        assertTrue(netReq.networkCapabilities.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(netReq.networkCapabilities.hasCapability(NET_CAPABILITY_DUN));
        // mobile is not permitted, we should not use HIPRI.
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(false);
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));
        assertEquals(0, mCM.requested.size());
        // mobile change back to permitted, HIRPI should come back
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(true);
        assertSatisfiesLegacyType(TYPE_MOBILE_HIPRI,
                mUNM.selectPreferredUpstreamType(preferredTypes));

        wifiAgent.fakeConnect();
        // WiFi is up, and we should prefer it over cell.
        assertSatisfiesLegacyType(TYPE_WIFI, mUNM.selectPreferredUpstreamType(preferredTypes));
        assertEquals(0, mCM.requested.size());

        preferredTypes.remove(TYPE_MOBILE_HIPRI);
        preferredTypes.add(TYPE_MOBILE_DUN);
        // This is coupled with preferred types in TetheringConfiguration.
        mUNM.updateMobileRequiresDun(true);
        assertSatisfiesLegacyType(TYPE_WIFI, mUNM.selectPreferredUpstreamType(preferredTypes));

        final TestNetworkAgent dunAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        dunAgent.networkCapabilities.addCapability(NET_CAPABILITY_DUN);
        dunAgent.fakeConnect();

        // WiFi is still preferred.
        assertSatisfiesLegacyType(TYPE_WIFI, mUNM.selectPreferredUpstreamType(preferredTypes));

        // WiFi goes down, cell and DUN are still up but only DUN is preferred.
        wifiAgent.fakeDisconnect();
        assertSatisfiesLegacyType(TYPE_MOBILE_DUN,
                mUNM.selectPreferredUpstreamType(preferredTypes));
        // Check to see we filed an explicit request.
        assertEquals(1, mCM.requested.size());
        netReq = (NetworkRequest) mCM.requested.values().toArray()[0];
        assertTrue(netReq.networkCapabilities.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(netReq.networkCapabilities.hasCapability(NET_CAPABILITY_DUN));
        // mobile is not permitted, we should not use DUN.
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(false);
        assertSatisfiesLegacyType(TYPE_NONE, mUNM.selectPreferredUpstreamType(preferredTypes));
        assertEquals(0, mCM.requested.size());
        // mobile change back to permitted, DUN should come back
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(true);
        assertSatisfiesLegacyType(TYPE_MOBILE_DUN,
                mUNM.selectPreferredUpstreamType(preferredTypes));
    }

    @Test
    public void testGetCurrentPreferredUpstream() throws Exception {
        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        mUNM.startObserveAllNetworks();
        mUNM.updateMobileRequiresDun(false);

        // [0] Mobile connects, DUN not required -> mobile selected.
        final TestNetworkAgent cellAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        cellAgent.fakeConnect();
        mCM.makeDefaultNetwork(cellAgent);
        assertEquals(cellAgent.networkId, mUNM.getCurrentPreferredUpstream().network);

        // [1] Mobile connects but not permitted -> null selected
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(false);
        assertEquals(null, mUNM.getCurrentPreferredUpstream());
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(true);

        // [2] WiFi connects but not validated/promoted to default -> mobile selected.
        final TestNetworkAgent wifiAgent = new TestNetworkAgent(mCM, TRANSPORT_WIFI);
        wifiAgent.fakeConnect();
        assertEquals(cellAgent.networkId, mUNM.getCurrentPreferredUpstream().network);

        // [3] WiFi validates and is promoted to the default network -> WiFi selected.
        mCM.makeDefaultNetwork(wifiAgent);
        assertEquals(wifiAgent.networkId, mUNM.getCurrentPreferredUpstream().network);

        // [4] DUN required, no other changes -> WiFi still selected
        mUNM.updateMobileRequiresDun(true);
        assertEquals(wifiAgent.networkId, mUNM.getCurrentPreferredUpstream().network);

        // [5] WiFi no longer validated, mobile becomes default, DUN required -> null selected.
        mCM.makeDefaultNetwork(cellAgent);
        assertEquals(null, mUNM.getCurrentPreferredUpstream());
        // TODO: make sure that a DUN request has been filed. This is currently
        // triggered by code over in Tethering, but once that has been moved
        // into UNM we should test for this here.

        // [6] DUN network arrives -> DUN selected
        final TestNetworkAgent dunAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        dunAgent.networkCapabilities.addCapability(NET_CAPABILITY_DUN);
        dunAgent.networkCapabilities.removeCapability(NET_CAPABILITY_INTERNET);
        dunAgent.fakeConnect();
        assertEquals(dunAgent.networkId, mUNM.getCurrentPreferredUpstream().network);

        // [7] Mobile is not permitted -> null selected
        when(mEntitleMgr.isCellularUpstreamPermitted()).thenReturn(false);
        assertEquals(null, mUNM.getCurrentPreferredUpstream());
    }

    @Test
    public void testLocalPrefixes() throws Exception {
        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        mUNM.startObserveAllNetworks();

        // [0] Test minimum set of local prefixes.
        Set<IpPrefix> local = mUNM.getLocalPrefixes();
        assertTrue(local.isEmpty());

        final Set<String> alreadySeen = new HashSet<>();

        // [1] Pretend Wi-Fi connects.
        final TestNetworkAgent wifiAgent = new TestNetworkAgent(mCM, TRANSPORT_WIFI);
        final LinkProperties wifiLp = wifiAgent.linkProperties;
        wifiLp.setInterfaceName("wlan0");
        final String[] WIFI_ADDRS = {
                "fe80::827a:bfff:fe6f:374d", "100.112.103.18",
                "2001:db8:4:fd00:827a:bfff:fe6f:374d",
                "2001:db8:4:fd00:6dea:325a:fdae:4ef4",
                "fd6a:a640:60bf:e985::123",  // ULA address for good measure.
        };
        for (String addrStr : WIFI_ADDRS) {
            final String cidr = addrStr.contains(":") ? "/64" : "/20";
            wifiLp.addLinkAddress(new LinkAddress(addrStr + cidr));
        }
        wifiAgent.fakeConnect();
        wifiAgent.sendLinkProperties();

        local = mUNM.getLocalPrefixes();
        assertPrefixSet(local, INCLUDES, alreadySeen);
        final String[] wifiLinkPrefixes = {
                // Link-local prefixes are excluded and dealt with elsewhere.
                "100.112.96.0/20", "2001:db8:4:fd00::/64", "fd6a:a640:60bf:e985::/64",
        };
        assertPrefixSet(local, INCLUDES, wifiLinkPrefixes);
        Collections.addAll(alreadySeen, wifiLinkPrefixes);
        assertEquals(alreadySeen.size(), local.size());

        // [2] Pretend mobile connects.
        final TestNetworkAgent cellAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        final LinkProperties cellLp = cellAgent.linkProperties;
        cellLp.setInterfaceName("rmnet_data0");
        final String[] CELL_ADDRS = {
                "10.102.211.48", "2001:db8:0:1:b50e:70d9:10c9:433d",
        };
        for (String addrStr : CELL_ADDRS) {
            final String cidr = addrStr.contains(":") ? "/64" : "/27";
            cellLp.addLinkAddress(new LinkAddress(addrStr + cidr));
        }
        cellAgent.fakeConnect();
        cellAgent.sendLinkProperties();

        local = mUNM.getLocalPrefixes();
        assertPrefixSet(local, INCLUDES, alreadySeen);
        final String[] cellLinkPrefixes = { "10.102.211.32/27", "2001:db8:0:1::/64" };
        assertPrefixSet(local, INCLUDES, cellLinkPrefixes);
        Collections.addAll(alreadySeen, cellLinkPrefixes);
        assertEquals(alreadySeen.size(), local.size());

        // [3] Pretend DUN connects.
        final TestNetworkAgent dunAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        dunAgent.networkCapabilities.addCapability(NET_CAPABILITY_DUN);
        dunAgent.networkCapabilities.removeCapability(NET_CAPABILITY_INTERNET);
        final LinkProperties dunLp = dunAgent.linkProperties;
        dunLp.setInterfaceName("rmnet_data1");
        final String[] DUN_ADDRS = {
                "192.0.2.48", "2001:db8:1:2:b50e:70d9:10c9:433d",
        };
        for (String addrStr : DUN_ADDRS) {
            final String cidr = addrStr.contains(":") ? "/64" : "/27";
            dunLp.addLinkAddress(new LinkAddress(addrStr + cidr));
        }
        dunAgent.fakeConnect();
        dunAgent.sendLinkProperties();

        local = mUNM.getLocalPrefixes();
        assertPrefixSet(local, INCLUDES, alreadySeen);
        final String[] dunLinkPrefixes = { "192.0.2.32/27", "2001:db8:1:2::/64" };
        assertPrefixSet(local, INCLUDES, dunLinkPrefixes);
        Collections.addAll(alreadySeen, dunLinkPrefixes);
        assertEquals(alreadySeen.size(), local.size());

        // [4] Pretend Wi-Fi disconnected.  It's addresses/prefixes should no
        // longer be included (should be properly removed).
        wifiAgent.fakeDisconnect();
        local = mUNM.getLocalPrefixes();
        assertPrefixSet(local, EXCLUDES, wifiLinkPrefixes);
        assertPrefixSet(local, INCLUDES, cellLinkPrefixes);
        assertPrefixSet(local, INCLUDES, dunLinkPrefixes);

        // [5] Pretend mobile disconnected.
        cellAgent.fakeDisconnect();
        local = mUNM.getLocalPrefixes();
        assertPrefixSet(local, EXCLUDES, wifiLinkPrefixes);
        assertPrefixSet(local, EXCLUDES, cellLinkPrefixes);
        assertPrefixSet(local, INCLUDES, dunLinkPrefixes);

        // [6] Pretend DUN disconnected.
        dunAgent.fakeDisconnect();
        local = mUNM.getLocalPrefixes();
        assertTrue(local.isEmpty());
    }

    @Test
    public void testSelectMobileWhenMobileIsNotDefault() {
        final Collection<Integer> preferredTypes = new ArrayList<>();
        // Mobile has higher pirority than wifi.
        preferredTypes.add(TYPE_MOBILE_HIPRI);
        preferredTypes.add(TYPE_WIFI);
        mUNM.startTrackDefaultNetwork(mDefaultRequest, mEntitleMgr);
        mUNM.startObserveAllNetworks();
        // Setup wifi and make wifi as default network.
        final TestNetworkAgent wifiAgent = new TestNetworkAgent(mCM, TRANSPORT_WIFI);
        wifiAgent.fakeConnect();
        mCM.makeDefaultNetwork(wifiAgent);
        // Setup mobile network.
        final TestNetworkAgent cellAgent = new TestNetworkAgent(mCM, TRANSPORT_CELLULAR);
        cellAgent.fakeConnect();

        assertSatisfiesLegacyType(TYPE_MOBILE_HIPRI,
                mUNM.selectPreferredUpstreamType(preferredTypes));
        verify(mEntitleMgr, times(1)).maybeRunProvisioning();
    }
    private void assertSatisfiesLegacyType(int legacyType, NetworkState ns) {
        if (legacyType == TYPE_NONE) {
            assertTrue(ns == null);
            return;
        }

        final NetworkCapabilities nc = ConnectivityManager.networkCapabilitiesForType(legacyType);
        assertTrue(nc.satisfiedByNetworkCapabilities(ns.networkCapabilities));
    }

    private void assertUpstreamTypeRequested(int upstreamType) throws Exception {
        assertEquals(1, mCM.requested.size());
        assertEquals(1, mCM.legacyTypeMap.size());
        assertEquals(Integer.valueOf(upstreamType),
                mCM.legacyTypeMap.values().iterator().next());
    }

    public static class TestConnectivityManager extends ConnectivityManager {
        public Map<NetworkCallback, Handler> allCallbacks = new HashMap<>();
        public Set<NetworkCallback> trackingDefault = new HashSet<>();
        public TestNetworkAgent defaultNetwork = null;
        public Map<NetworkCallback, NetworkRequest> listening = new HashMap<>();
        public Map<NetworkCallback, NetworkRequest> requested = new HashMap<>();
        public Map<NetworkCallback, Integer> legacyTypeMap = new HashMap<>();

        private int mNetworkId = 100;

        public TestConnectivityManager(Context ctx, IConnectivityManager svc) {
            super(ctx, svc);
        }

        boolean hasNoCallbacks() {
            return allCallbacks.isEmpty()
                    && trackingDefault.isEmpty()
                    && listening.isEmpty()
                    && requested.isEmpty()
                    && legacyTypeMap.isEmpty();
        }

        boolean onlyHasDefaultCallbacks() {
            return (allCallbacks.size() == 1)
                    && (trackingDefault.size() == 1)
                    && listening.isEmpty()
                    && requested.isEmpty()
                    && legacyTypeMap.isEmpty();
        }

        boolean isListeningForAll() {
            final NetworkCapabilities empty = new NetworkCapabilities();
            empty.clearAll();

            for (NetworkRequest req : listening.values()) {
                if (req.networkCapabilities.equalRequestableCapabilities(empty)) {
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

        int getNetworkId() { return ++mNetworkId; }

        void makeDefaultNetwork(TestNetworkAgent agent) {
            if (Objects.equals(defaultNetwork, agent)) return;

            final TestNetworkAgent formerDefault = defaultNetwork;
            defaultNetwork = agent;

            for (NetworkCallback cb : trackingDefault) {
                if (defaultNetwork != null) {
                    cb.onAvailable(defaultNetwork.networkId);
                    cb.onCapabilitiesChanged(
                            defaultNetwork.networkId, defaultNetwork.networkCapabilities);
                    cb.onLinkPropertiesChanged(
                            defaultNetwork.networkId, defaultNetwork.linkProperties);
                }
            }
        }

        @Override
        public void requestNetwork(NetworkRequest req, NetworkCallback cb, Handler h) {
            assertFalse(allCallbacks.containsKey(cb));
            allCallbacks.put(cb, h);
            if (mDefaultRequest.equals(req)) {
                assertFalse(trackingDefault.contains(cb));
                trackingDefault.add(cb);
            } else {
                assertFalse(requested.containsKey(cb));
                requested.put(cb, req);
            }
        }

        @Override
        public void requestNetwork(NetworkRequest req, NetworkCallback cb) {
            fail("Should never be called.");
        }

        @Override
        public void requestNetwork(NetworkRequest req, NetworkCallback cb,
                int timeoutMs, int legacyType, Handler h) {
            assertFalse(allCallbacks.containsKey(cb));
            allCallbacks.put(cb, h);
            assertFalse(requested.containsKey(cb));
            requested.put(cb, req);
            assertFalse(legacyTypeMap.containsKey(cb));
            if (legacyType != ConnectivityManager.TYPE_NONE) {
                legacyTypeMap.put(cb, legacyType);
            }
        }

        @Override
        public void registerNetworkCallback(NetworkRequest req, NetworkCallback cb, Handler h) {
            assertFalse(allCallbacks.containsKey(cb));
            allCallbacks.put(cb, h);
            assertFalse(listening.containsKey(cb));
            listening.put(cb, req);
        }

        @Override
        public void registerNetworkCallback(NetworkRequest req, NetworkCallback cb) {
            fail("Should never be called.");
        }

        @Override
        public void registerDefaultNetworkCallback(NetworkCallback cb, Handler h) {
            fail("Should never be called.");
        }

        @Override
        public void registerDefaultNetworkCallback(NetworkCallback cb) {
            fail("Should never be called.");
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
            } else {
                fail("Unexpected callback removed");
            }
            allCallbacks.remove(cb);

            assertFalse(allCallbacks.containsKey(cb));
            assertFalse(trackingDefault.contains(cb));
            assertFalse(listening.containsKey(cb));
            assertFalse(requested.containsKey(cb));
        }
    }

    public static class TestNetworkAgent {
        public final TestConnectivityManager cm;
        public final Network networkId;
        public final int transportType;
        public final NetworkCapabilities networkCapabilities;
        public final LinkProperties linkProperties;

        public TestNetworkAgent(TestConnectivityManager cm, int transportType) {
            this.cm = cm;
            this.networkId = new Network(cm.getNetworkId());
            this.transportType = transportType;
            networkCapabilities = new NetworkCapabilities();
            networkCapabilities.addTransportType(transportType);
            networkCapabilities.addCapability(NET_CAPABILITY_INTERNET);
            linkProperties = new LinkProperties();
        }

        public void fakeConnect() {
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onAvailable(networkId);
                cb.onCapabilitiesChanged(networkId, copy(networkCapabilities));
                cb.onLinkPropertiesChanged(networkId, copy(linkProperties));
            }
        }

        public void fakeDisconnect() {
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onLost(networkId);
            }
        }

        public void sendLinkProperties() {
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onLinkPropertiesChanged(networkId, copy(linkProperties));
            }
        }

        @Override
        public String toString() {
            return String.format("TestNetworkAgent: %s %s", networkId, networkCapabilities);
        }
    }

    public static class TestStateMachine extends StateMachine {
        public final ArrayList<Message> messages = new ArrayList<>();
        private final State mLoggingState = new LoggingState();

        class LoggingState extends State {
            @Override public void enter() { messages.clear(); }

            @Override public void exit() { messages.clear(); }

            @Override public boolean processMessage(Message msg) {
                messages.add(msg);
                return true;
            }
        }

        public TestStateMachine() {
            super("UpstreamNetworkMonitor.TestStateMachine");
            addState(mLoggingState);
            setInitialState(mLoggingState);
            super.start();
        }
    }

    static NetworkCapabilities copy(NetworkCapabilities nc) {
        return new NetworkCapabilities(nc);
    }

    static LinkProperties copy(LinkProperties lp) {
        return new LinkProperties(lp);
    }

    static void assertPrefixSet(Set<IpPrefix> prefixes, boolean expectation, String... expected) {
        final Set<String> expectedSet = new HashSet<>();
        Collections.addAll(expectedSet, expected);
        assertPrefixSet(prefixes, expectation, expectedSet);
    }

    static void assertPrefixSet(Set<IpPrefix> prefixes, boolean expectation, Set<String> expected) {
        for (String expectedPrefix : expected) {
            final String errStr = expectation ? "did not find" : "found";
            assertEquals(
                    String.format("Failed expectation: %s prefix: %s", errStr, expectedPrefix),
                    expectation, prefixes.contains(new IpPrefix(expectedPrefix)));
        }
    }
}
