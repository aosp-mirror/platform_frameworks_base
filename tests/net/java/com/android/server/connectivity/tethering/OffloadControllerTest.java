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

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.TrafficStats.UID_TETHERING;
import static android.provider.Settings.Global.TETHER_OFFLOAD_DISABLED;
import static com.android.server.connectivity.tethering.OffloadHardwareInterface.ForwardedStats;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ITetheringStatsProvider;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkStats;
import android.net.RouteInfo;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.Looper;
import android.os.INetworkManagementService;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;
import com.android.internal.util.test.FakeSettingsProvider;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class OffloadControllerTest {

    @Mock private OffloadHardwareInterface mHardware;
    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    private final ArgumentCaptor<ArrayList> mStringArrayCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    private final ArgumentCaptor<ITetheringStatsProvider.Stub> mTetherStatsProviderCaptor =
            ArgumentCaptor.forClass(ITetheringStatsProvider.Stub.class);
    private MockContentResolver mContentResolver;

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getPackageName()).thenReturn("OffloadControllerTest");
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        FakeSettingsProvider.clearSettingsProvider();
    }

    @After public void tearDown() throws Exception {
        FakeSettingsProvider.clearSettingsProvider();
    }

    private void setupFunctioningHardwareInterface() {
        when(mHardware.initOffloadConfig()).thenReturn(true);
        when(mHardware.initOffloadControl(any(OffloadHardwareInterface.ControlCallback.class)))
                .thenReturn(true);
        when(mHardware.getForwardedStats(any())).thenReturn(new ForwardedStats());
    }

    private void enableOffload() {
        Settings.Global.putInt(mContentResolver, TETHER_OFFLOAD_DISABLED, 0);
    }

    private OffloadController makeOffloadController() throws Exception {
        OffloadController offload = new OffloadController(new Handler(Looper.getMainLooper()),
                mHardware, mContentResolver, mNMService, new SharedLog("test"));
        verify(mNMService).registerTetheringStatsProvider(
                mTetherStatsProviderCaptor.capture(), anyString());
        return offload;
    }

    @Test
    public void testNoSettingsValueDefaultDisabledDoesNotStart() throws Exception {
        setupFunctioningHardwareInterface();
        when(mHardware.getDefaultTetherOffloadDisabled()).thenReturn(1);
        try {
            Settings.Global.getInt(mContentResolver, TETHER_OFFLOAD_DISABLED);
            fail();
        } catch (SettingNotFoundException expected) {}

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).getDefaultTetherOffloadDisabled();
        inOrder.verify(mHardware, never()).initOffloadConfig();
        inOrder.verify(mHardware, never()).initOffloadControl(
                any(OffloadHardwareInterface.ControlCallback.class));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testNoSettingsValueDefaultEnabledDoesStart() throws Exception {
        setupFunctioningHardwareInterface();
        when(mHardware.getDefaultTetherOffloadDisabled()).thenReturn(0);
        try {
            Settings.Global.getInt(mContentResolver, TETHER_OFFLOAD_DISABLED);
            fail();
        } catch (SettingNotFoundException expected) {}

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).getDefaultTetherOffloadDisabled();
        inOrder.verify(mHardware, times(1)).initOffloadConfig();
        inOrder.verify(mHardware, times(1)).initOffloadControl(
                any(OffloadHardwareInterface.ControlCallback.class));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSettingsAllowsStart() throws Exception {
        setupFunctioningHardwareInterface();
        Settings.Global.putInt(mContentResolver, TETHER_OFFLOAD_DISABLED, 0);

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).getDefaultTetherOffloadDisabled();
        inOrder.verify(mHardware, times(1)).initOffloadConfig();
        inOrder.verify(mHardware, times(1)).initOffloadControl(
                any(OffloadHardwareInterface.ControlCallback.class));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSettingsDisablesStart() throws Exception {
        setupFunctioningHardwareInterface();
        Settings.Global.putInt(mContentResolver, TETHER_OFFLOAD_DISABLED, 1);

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).getDefaultTetherOffloadDisabled();
        inOrder.verify(mHardware, never()).initOffloadConfig();
        inOrder.verify(mHardware, never()).initOffloadControl(anyObject());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testSetUpstreamLinkPropertiesWorking() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).getDefaultTetherOffloadDisabled();
        inOrder.verify(mHardware, times(1)).initOffloadConfig();
        inOrder.verify(mHardware, times(1)).initOffloadControl(
                any(OffloadHardwareInterface.ControlCallback.class));
        inOrder.verifyNoMoreInteractions();

        // In reality, the UpstreamNetworkMonitor would have passed down to us
        // a covering set of local prefixes representing a minimum essential
        // set plus all the prefixes on networks with network agents.
        //
        // We simulate that there, and then add upstream elements one by one
        // and watch what happens.
        final Set<IpPrefix> minimumLocalPrefixes = new HashSet<>();
        for (String s : new String[]{
                "127.0.0.0/8", "192.0.2.0/24", "fe80::/64", "2001:db8::/64"}) {
            minimumLocalPrefixes.add(new IpPrefix(s));
        }
        offload.setLocalPrefixes(minimumLocalPrefixes);
        inOrder.verify(mHardware, times(1)).setLocalPrefixes(mStringArrayCaptor.capture());
        ArrayList<String> localPrefixes = mStringArrayCaptor.getValue();
        assertEquals(4, localPrefixes.size());
        assertTrue(localPrefixes.contains("127.0.0.0/8"));
        assertTrue(localPrefixes.contains("192.0.2.0/24"));
        assertTrue(localPrefixes.contains("fe80::/64"));
        assertTrue(localPrefixes.contains("2001:db8::/64"));
        inOrder.verifyNoMoreInteractions();

        offload.setUpstreamLinkProperties(null);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        // This LinkProperties value does not differ from the default upstream.
        // There should be no extraneous call to setUpstreamParameters().
        inOrder.verify(mHardware, never()).setUpstreamParameters(
                anyObject(), anyObject(), anyObject(), anyObject());
        inOrder.verifyNoMoreInteractions();

        final LinkProperties lp = new LinkProperties();

        final String testIfName = "rmnet_data17";
        lp.setInterfaceName(testIfName);
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(null), eq(null), eq(null));
        inOrder.verifyNoMoreInteractions();

        final String ipv4Addr = "192.0.2.5";
        final String linkAddr = ipv4Addr + "/24";
        lp.addLinkAddress(new LinkAddress(linkAddr));
        lp.addRoute(new RouteInfo(new IpPrefix("192.0.2.0/24")));
        offload.setUpstreamLinkProperties(lp);
        // IPv4 prefixes and addresses on the upstream are simply left as whole
        // prefixes (already passed in from UpstreamNetworkMonitor code). If a
        // tethering client sends traffic to the IPv4 default router or other
        // clients on the upstream this will not be hardware-forwarded, and that
        // should be fine for now. Ergo: no change in local addresses, no call
        // to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(null), eq(null));
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        inOrder.verifyNoMoreInteractions();

        final String ipv4Gateway = "192.0.2.1";
        lp.addRoute(new RouteInfo(InetAddress.getByName(ipv4Gateway)));
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), eq(null));
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        inOrder.verifyNoMoreInteractions();

        final String ipv6Gw1 = "fe80::cafe";
        lp.addRoute(new RouteInfo(InetAddress.getByName(ipv6Gw1)));
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        ArrayList<String> v6gws = mStringArrayCaptor.getValue();
        assertEquals(1, v6gws.size());
        assertTrue(v6gws.contains(ipv6Gw1));
        inOrder.verifyNoMoreInteractions();

        final String ipv6Gw2 = "fe80::d00d";
        lp.addRoute(new RouteInfo(InetAddress.getByName(ipv6Gw2)));
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        v6gws = mStringArrayCaptor.getValue();
        assertEquals(2, v6gws.size());
        assertTrue(v6gws.contains(ipv6Gw1));
        assertTrue(v6gws.contains(ipv6Gw2));
        inOrder.verifyNoMoreInteractions();

        final LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName("stacked");
        stacked.addLinkAddress(new LinkAddress("192.0.2.129/25"));
        stacked.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));
        stacked.addRoute(new RouteInfo(InetAddress.getByName("fe80::bad:f00")));
        assertTrue(lp.addStackedLink(stacked));
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        v6gws = mStringArrayCaptor.getValue();
        assertEquals(2, v6gws.size());
        assertTrue(v6gws.contains(ipv6Gw1));
        assertTrue(v6gws.contains(ipv6Gw2));
        inOrder.verifyNoMoreInteractions();

        // Add in some IPv6 upstream info. When there is a tethered downstream
        // making use of the IPv6 prefix we would expect to see the /64 route
        // removed from "local prefixes" and /128s added for the upstream IPv6
        // addresses.  This is not yet implemented, and for now we simply
        // expect to see these /128s.
        lp.addRoute(new RouteInfo(new IpPrefix("2001:db8::/64")));
        // "2001:db8::/64" plus "assigned" ASCII in hex
        lp.addLinkAddress(new LinkAddress("2001:db8::6173:7369:676e:6564/64"));
        // "2001:db8::/64" plus "random" ASCII in hex
        lp.addLinkAddress(new LinkAddress("2001:db8::7261:6e64:6f6d/64"));
        offload.setUpstreamLinkProperties(lp);
        inOrder.verify(mHardware, times(1)).setLocalPrefixes(mStringArrayCaptor.capture());
        localPrefixes = mStringArrayCaptor.getValue();
        assertEquals(6, localPrefixes.size());
        assertTrue(localPrefixes.contains("127.0.0.0/8"));
        assertTrue(localPrefixes.contains("192.0.2.0/24"));
        assertTrue(localPrefixes.contains("fe80::/64"));
        assertTrue(localPrefixes.contains("2001:db8::/64"));
        assertTrue(localPrefixes.contains("2001:db8::6173:7369:676e:6564/128"));
        assertTrue(localPrefixes.contains("2001:db8::7261:6e64:6f6d/128"));
        // The relevant parts of the LinkProperties have not changed, but at the
        // moment we do not de-dup upstream LinkProperties this carefully.
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), mStringArrayCaptor.capture());
        v6gws = mStringArrayCaptor.getValue();
        assertEquals(2, v6gws.size());
        assertTrue(v6gws.contains(ipv6Gw1));
        assertTrue(v6gws.contains(ipv6Gw2));
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        inOrder.verifyNoMoreInteractions();

        // Completely identical LinkProperties updates are de-duped.
        offload.setUpstreamLinkProperties(lp);
        // This LinkProperties value does not differ from the default upstream.
        // There should be no extraneous call to setUpstreamParameters().
        inOrder.verify(mHardware, never()).setUpstreamParameters(
                anyObject(), anyObject(), anyObject(), anyObject());
        inOrder.verifyNoMoreInteractions();
    }

    private void assertNetworkStats(String iface, ForwardedStats stats, NetworkStats.Entry entry) {
        assertEquals(iface, entry.iface);
        assertEquals(stats.rxBytes, entry.rxBytes);
        assertEquals(stats.txBytes, entry.txBytes);
        assertEquals(SET_DEFAULT, entry.set);
        assertEquals(TAG_NONE, entry.tag);
        assertEquals(UID_TETHERING, entry.uid);
    }

    @Test
    public void testGetForwardedStats() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        final String ethernetIface = "eth1";
        final String mobileIface = "rmnet_data0";

        ForwardedStats ethernetStats = new ForwardedStats();
        ethernetStats.rxBytes = 12345;
        ethernetStats.txBytes = 54321;

        ForwardedStats mobileStats = new ForwardedStats();
        mobileStats.rxBytes = 999;
        mobileStats.txBytes = 99999;

        when(mHardware.getForwardedStats(eq(ethernetIface))).thenReturn(ethernetStats);
        when(mHardware.getForwardedStats(eq(mobileIface))).thenReturn(mobileStats);

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);

        lp.setInterfaceName(mobileIface);
        offload.setUpstreamLinkProperties(lp);

        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);

        ethernetStats.rxBytes = 100000;
        ethernetStats.txBytes = 100000;
        offload.setUpstreamLinkProperties(null);

        NetworkStats stats = mTetherStatsProviderCaptor.getValue().getTetherStats();
        assertEquals(2, stats.size());

        NetworkStats.Entry entry = null;
        int ethernetPosition = ethernetIface.equals(stats.getValues(0, entry).iface) ? 0 : 1;
        int mobilePosition = 1 - ethernetPosition;

        entry = stats.getValues(mobilePosition, entry);
        assertNetworkStats(mobileIface, mobileStats, entry);

        ethernetStats.rxBytes = 12345 + 100000;
        ethernetStats.txBytes = 54321 + 100000;
        entry = stats.getValues(ethernetPosition, entry);
        assertNetworkStats(ethernetIface, ethernetStats, entry);
    }
}
