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
import static android.net.NetworkStats.STATS_PER_IFACE;
import static android.net.NetworkStats.STATS_PER_UID;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_TETHERING;
import static android.provider.Settings.Global.TETHER_OFFLOAD_DISABLED;
import static com.android.server.connectivity.tethering.OffloadHardwareInterface.ForwardedStats;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import android.os.ConditionVariable;
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
    private static final String RNDIS0 = "test_rndis0";
    private static final String RMNET0 = "test_rmnet_data0";
    private static final String WLAN0 = "test_wlan0";

    private static final String IPV6_LINKLOCAL = "fe80::/64";
    private static final String IPV6_DOC_PREFIX = "2001:db8::/64";
    private static final String IPV6_DISCARD_PREFIX = "100::/64";
    private static final String USB_PREFIX = "192.168.42.0/24";
    private static final String WIFI_PREFIX = "192.168.43.0/24";

    @Mock private OffloadHardwareInterface mHardware;
    @Mock private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    private final ArgumentCaptor<ArrayList> mStringArrayCaptor =
            ArgumentCaptor.forClass(ArrayList.class);
    private final ArgumentCaptor<ITetheringStatsProvider.Stub> mTetherStatsProviderCaptor =
            ArgumentCaptor.forClass(ITetheringStatsProvider.Stub.class);
    private final ArgumentCaptor<OffloadHardwareInterface.ControlCallback> mControlCallbackCaptor =
            ArgumentCaptor.forClass(OffloadHardwareInterface.ControlCallback.class);
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
        when(mHardware.initOffloadControl(mControlCallbackCaptor.capture()))
                .thenReturn(true);
        when(mHardware.setUpstreamParameters(anyString(), any(), any(), any())).thenReturn(true);
        when(mHardware.getForwardedStats(any())).thenReturn(new ForwardedStats());
        when(mHardware.setDataLimit(anyString(), anyLong())).thenReturn(true);
    }

    private void enableOffload() {
        Settings.Global.putInt(mContentResolver, TETHER_OFFLOAD_DISABLED, 0);
    }

    private void waitForIdle() {
        ConditionVariable cv = new ConditionVariable();
        new Handler(Looper.getMainLooper()).post(() -> { cv.open(); });
        cv.block();
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
        assertArrayListContains(localPrefixes,
                "127.0.0.0/8", "192.0.2.0/24", "fe80::/64", "2001:db8::/64");
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
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
        inOrder.verifyNoMoreInteractions();

        final String ipv4Gateway = "192.0.2.1";
        lp.addRoute(new RouteInfo(InetAddress.getByName(ipv4Gateway)));
        offload.setUpstreamLinkProperties(lp);
        // No change in local addresses means no call to setLocalPrefixes().
        inOrder.verify(mHardware, never()).setLocalPrefixes(mStringArrayCaptor.capture());
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), eq(null));
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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
        assertArrayListContains(localPrefixes,
                "127.0.0.0/8", "192.0.2.0/24", "fe80::/64", "2001:db8::/64",
                "2001:db8::6173:7369:676e:6564/128", "2001:db8::7261:6e64:6f6d/128");
        // The relevant parts of the LinkProperties have not changed, but at the
        // moment we do not de-dup upstream LinkProperties this carefully.
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(testIfName), eq(ipv4Addr), eq(ipv4Gateway), mStringArrayCaptor.capture());
        v6gws = mStringArrayCaptor.getValue();
        assertEquals(2, v6gws.size());
        assertTrue(v6gws.contains(ipv6Gw1));
        assertTrue(v6gws.contains(ipv6Gw2));
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(testIfName));
        inOrder.verify(mHardware, times(1)).setDataLimit(eq(testIfName), eq(Long.MAX_VALUE));
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

        InOrder inOrder = inOrder(mHardware);

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);
        // Previous upstream was null, so no stats are fetched.
        inOrder.verify(mHardware, never()).getForwardedStats(any());

        lp.setInterfaceName(mobileIface);
        offload.setUpstreamLinkProperties(lp);
        // Expect that we fetch stats from the previous upstream.
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(ethernetIface));

        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);
        // Expect that we fetch stats from the previous upstream.
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(mobileIface));

        ethernetStats = new ForwardedStats();
        ethernetStats.rxBytes = 100000;
        ethernetStats.txBytes = 100000;
        when(mHardware.getForwardedStats(eq(ethernetIface))).thenReturn(ethernetStats);
        offload.setUpstreamLinkProperties(null);
        // Expect that we first clear the HAL's upstream parameters.
        inOrder.verify(mHardware, times(1)).setUpstreamParameters(
                eq(""), eq("0.0.0.0"), eq("0.0.0.0"), eq(null));
        // Expect that we fetch stats from the previous upstream.
        inOrder.verify(mHardware, times(1)).getForwardedStats(eq(ethernetIface));

        ITetheringStatsProvider provider = mTetherStatsProviderCaptor.getValue();
        NetworkStats stats = provider.getTetherStats(STATS_PER_IFACE);
        NetworkStats perUidStats = provider.getTetherStats(STATS_PER_UID);
        waitForIdle();
        // There is no current upstream, so no stats are fetched.
        inOrder.verify(mHardware, never()).getForwardedStats(any());
        inOrder.verifyNoMoreInteractions();

        assertEquals(2, stats.size());
        assertEquals(2, perUidStats.size());

        NetworkStats.Entry entry = null;
        for (int i = 0; i < stats.size(); i++) {
            assertEquals(UID_ALL, stats.getValues(i, entry).uid);
            assertEquals(UID_TETHERING, perUidStats.getValues(i, entry).uid);
        }

        int ethernetPosition = ethernetIface.equals(stats.getValues(0, entry).iface) ? 0 : 1;
        int mobilePosition = 1 - ethernetPosition;

        entry = stats.getValues(mobilePosition, entry);
        assertNetworkStats(mobileIface, mobileStats, entry);
        entry = perUidStats.getValues(mobilePosition, entry);
        assertNetworkStats(mobileIface, mobileStats, entry);

        ethernetStats.rxBytes = 12345 + 100000;
        ethernetStats.txBytes = 54321 + 100000;
        entry = stats.getValues(ethernetPosition, entry);
        assertNetworkStats(ethernetIface, ethernetStats, entry);
        entry = perUidStats.getValues(ethernetPosition, entry);
        assertNetworkStats(ethernetIface, ethernetStats, entry);
    }

    @Test
    public void testSetInterfaceQuota() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        final String ethernetIface = "eth1";
        final String mobileIface = "rmnet_data0";
        final long ethernetLimit = 12345;
        final long mobileLimit = 12345678;

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);

        ITetheringStatsProvider provider = mTetherStatsProviderCaptor.getValue();
        final InOrder inOrder = inOrder(mHardware);
        when(mHardware.setUpstreamParameters(any(), any(), any(), any())).thenReturn(true);
        when(mHardware.setDataLimit(anyString(), anyLong())).thenReturn(true);

        // Applying an interface quota to the current upstream immediately sends it to the hardware.
        provider.setInterfaceQuota(ethernetIface, ethernetLimit);
        waitForIdle();
        inOrder.verify(mHardware).setDataLimit(ethernetIface, ethernetLimit);
        inOrder.verifyNoMoreInteractions();

        // Applying an interface quota to another upstream does not take any immediate action.
        provider.setInterfaceQuota(mobileIface, mobileLimit);
        waitForIdle();
        inOrder.verify(mHardware, never()).setDataLimit(anyString(), anyLong());

        // Switching to that upstream causes the quota to be applied if the parameters were applied
        // correctly.
        lp.setInterfaceName(mobileIface);
        offload.setUpstreamLinkProperties(lp);
        waitForIdle();
        inOrder.verify(mHardware).setDataLimit(mobileIface, mobileLimit);

        // Setting a limit of ITetheringStatsProvider.QUOTA_UNLIMITED causes the limit to be set
        // to Long.MAX_VALUE.
        provider.setInterfaceQuota(mobileIface, ITetheringStatsProvider.QUOTA_UNLIMITED);
        waitForIdle();
        inOrder.verify(mHardware).setDataLimit(mobileIface, Long.MAX_VALUE);

        // If setting upstream parameters fails, then the data limit is not set.
        when(mHardware.setUpstreamParameters(any(), any(), any(), any())).thenReturn(false);
        lp.setInterfaceName(ethernetIface);
        offload.setUpstreamLinkProperties(lp);
        provider.setInterfaceQuota(mobileIface, mobileLimit);
        waitForIdle();
        inOrder.verify(mHardware, never()).setDataLimit(anyString(), anyLong());

        // If setting the data limit fails while changing upstreams, offload is stopped.
        when(mHardware.setUpstreamParameters(any(), any(), any(), any())).thenReturn(true);
        when(mHardware.setDataLimit(anyString(), anyLong())).thenReturn(false);
        lp.setInterfaceName(mobileIface);
        offload.setUpstreamLinkProperties(lp);
        provider.setInterfaceQuota(mobileIface, mobileLimit);
        waitForIdle();
        inOrder.verify(mHardware).getForwardedStats(ethernetIface);
        inOrder.verify(mHardware).stopOffloadControl();
    }

    @Test
    public void testDataLimitCallback() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        OffloadHardwareInterface.ControlCallback callback = mControlCallbackCaptor.getValue();
        callback.onStoppedLimitReached();
        verify(mNMService, times(1)).tetherLimitReached(mTetherStatsProviderCaptor.getValue());
    }

    @Test
    public void testAddRemoveDownstreams() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        final InOrder inOrder = inOrder(mHardware);
        inOrder.verify(mHardware, times(1)).initOffloadConfig();
        inOrder.verify(mHardware, times(1)).initOffloadControl(
                any(OffloadHardwareInterface.ControlCallback.class));
        inOrder.verifyNoMoreInteractions();

        // Tethering makes several calls to setLocalPrefixes() before add/remove
        // downstream calls are made. This is not tested here; only the behavior
        // of notifyDownstreamLinkProperties() and removeDownstreamInterface()
        // are tested.

        // [1] USB tethering is started.
        final LinkProperties usbLinkProperties = new LinkProperties();
        usbLinkProperties.setInterfaceName(RNDIS0);
        usbLinkProperties.addLinkAddress(new LinkAddress("192.168.42.1/24"));
        usbLinkProperties.addRoute(new RouteInfo(new IpPrefix(USB_PREFIX)));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);
        inOrder.verify(mHardware, times(1)).addDownstreamPrefix(RNDIS0, USB_PREFIX);
        inOrder.verifyNoMoreInteractions();

        // [2] Routes for IPv6 link-local prefixes should never be added.
        usbLinkProperties.addRoute(new RouteInfo(new IpPrefix(IPV6_LINKLOCAL)));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);
        inOrder.verify(mHardware, never()).addDownstreamPrefix(eq(RNDIS0), anyString());
        inOrder.verifyNoMoreInteractions();

        // [3] Add an IPv6 prefix for good measure. Only new offload-able
        // prefixes should be passed to the HAL.
        usbLinkProperties.addLinkAddress(new LinkAddress("2001:db8::1/64"));
        usbLinkProperties.addRoute(new RouteInfo(new IpPrefix(IPV6_DOC_PREFIX)));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);
        inOrder.verify(mHardware, times(1)).addDownstreamPrefix(RNDIS0, IPV6_DOC_PREFIX);
        inOrder.verifyNoMoreInteractions();

        // [4] Adding addresses doesn't affect notifyDownstreamLinkProperties().
        // The address is passed in by a separate setLocalPrefixes() invocation.
        usbLinkProperties.addLinkAddress(new LinkAddress("2001:db8::2/64"));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);
        inOrder.verify(mHardware, never()).addDownstreamPrefix(eq(RNDIS0), anyString());

        // [5] Differences in local routes are converted into addDownstream()
        // and removeDownstream() invocations accordingly.
        usbLinkProperties.removeRoute(new RouteInfo(new IpPrefix(IPV6_DOC_PREFIX), null, RNDIS0));
        usbLinkProperties.addRoute(new RouteInfo(new IpPrefix(IPV6_DISCARD_PREFIX)));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);
        inOrder.verify(mHardware, times(1)).removeDownstreamPrefix(RNDIS0, IPV6_DOC_PREFIX);
        inOrder.verify(mHardware, times(1)).addDownstreamPrefix(RNDIS0, IPV6_DISCARD_PREFIX);
        inOrder.verifyNoMoreInteractions();

        // [6] Removing a downstream interface which was never added causes no
        // interactions with the HAL.
        offload.removeDownstreamInterface(WLAN0);
        inOrder.verifyNoMoreInteractions();

        // [7] Removing an active downstream removes all remaining prefixes.
        offload.removeDownstreamInterface(RNDIS0);
        inOrder.verify(mHardware, times(1)).removeDownstreamPrefix(RNDIS0, USB_PREFIX);
        inOrder.verify(mHardware, times(1)).removeDownstreamPrefix(RNDIS0, IPV6_DISCARD_PREFIX);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testControlCallbackOnStoppedUnsupportedFetchesAllStats() throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        // Pretend to set a few different upstreams (only the interface name
        // matters for this test; we're ignoring IP and route information).
        final LinkProperties upstreamLp = new LinkProperties();
        for (String ifname : new String[]{RMNET0, WLAN0, RMNET0}) {
            upstreamLp.setInterfaceName(ifname);
            offload.setUpstreamLinkProperties(upstreamLp);
        }

        // Clear invocation history, especially the getForwardedStats() calls
        // that happen with setUpstreamParameters().
        clearInvocations(mHardware);

        OffloadHardwareInterface.ControlCallback callback = mControlCallbackCaptor.getValue();
        callback.onStoppedUnsupported();

        // Verify forwarded stats behaviour.
        verify(mHardware, times(1)).getForwardedStats(eq(RMNET0));
        verify(mHardware, times(1)).getForwardedStats(eq(WLAN0));
        verifyNoMoreInteractions(mHardware);
        verify(mNMService, times(1)).tetherLimitReached(mTetherStatsProviderCaptor.getValue());
        verifyNoMoreInteractions(mNMService);
    }

    @Test
    public void testControlCallbackOnSupportAvailableFetchesAllStatsAndPushesAllParameters()
            throws Exception {
        setupFunctioningHardwareInterface();
        enableOffload();

        final OffloadController offload = makeOffloadController();
        offload.start();

        // Pretend to set a few different upstreams (only the interface name
        // matters for this test; we're ignoring IP and route information).
        final LinkProperties upstreamLp = new LinkProperties();
        for (String ifname : new String[]{RMNET0, WLAN0, RMNET0}) {
            upstreamLp.setInterfaceName(ifname);
            offload.setUpstreamLinkProperties(upstreamLp);
        }

        // Pretend that some local prefixes and downstreams have been added
        // (and removed, for good measure).
        final Set<IpPrefix> minimumLocalPrefixes = new HashSet<>();
        for (String s : new String[]{
                "127.0.0.0/8", "192.0.2.0/24", "fe80::/64", "2001:db8::/64"}) {
            minimumLocalPrefixes.add(new IpPrefix(s));
        }
        offload.setLocalPrefixes(minimumLocalPrefixes);

        final LinkProperties usbLinkProperties = new LinkProperties();
        usbLinkProperties.setInterfaceName(RNDIS0);
        usbLinkProperties.addLinkAddress(new LinkAddress("192.168.42.1/24"));
        usbLinkProperties.addRoute(new RouteInfo(new IpPrefix(USB_PREFIX)));
        offload.notifyDownstreamLinkProperties(usbLinkProperties);

        final LinkProperties wifiLinkProperties = new LinkProperties();
        wifiLinkProperties.setInterfaceName(WLAN0);
        wifiLinkProperties.addLinkAddress(new LinkAddress("192.168.43.1/24"));
        wifiLinkProperties.addRoute(new RouteInfo(new IpPrefix(WIFI_PREFIX)));
        wifiLinkProperties.addRoute(new RouteInfo(new IpPrefix(IPV6_LINKLOCAL)));
        // Use a benchmark prefix (RFC 5180 + erratum), since the documentation
        // prefix is included in the excluded prefix list.
        wifiLinkProperties.addLinkAddress(new LinkAddress("2001:2::1/64"));
        wifiLinkProperties.addLinkAddress(new LinkAddress("2001:2::2/64"));
        wifiLinkProperties.addRoute(new RouteInfo(new IpPrefix("2001:2::/64")));
        offload.notifyDownstreamLinkProperties(wifiLinkProperties);

        offload.removeDownstreamInterface(RNDIS0);

        // Clear invocation history, especially the getForwardedStats() calls
        // that happen with setUpstreamParameters().
        clearInvocations(mHardware);

        OffloadHardwareInterface.ControlCallback callback = mControlCallbackCaptor.getValue();
        callback.onSupportAvailable();

        // Verify forwarded stats behaviour.
        verify(mHardware, times(1)).getForwardedStats(eq(RMNET0));
        verify(mHardware, times(1)).getForwardedStats(eq(WLAN0));
        verify(mNMService, times(1)).tetherLimitReached(mTetherStatsProviderCaptor.getValue());
        verifyNoMoreInteractions(mNMService);

        // TODO: verify local prefixes and downstreams are also pushed to the HAL.
        verify(mHardware, times(1)).setLocalPrefixes(mStringArrayCaptor.capture());
        ArrayList<String> localPrefixes = mStringArrayCaptor.getValue();
        assertEquals(4, localPrefixes.size());
        assertArrayListContains(localPrefixes,
                // TODO: The logic to find and exclude downstream IP prefixes
                // is currently in Tethering's OffloadWrapper but must be moved
                // into OffloadController proper. After this, also check for:
                //     "192.168.43.1/32", "2001:2::1/128", "2001:2::2/128"
                "127.0.0.0/8", "192.0.2.0/24", "fe80::/64", "2001:db8::/64");
        verify(mHardware, times(1)).addDownstreamPrefix(WLAN0, "192.168.43.0/24");
        verify(mHardware, times(1)).addDownstreamPrefix(WLAN0, "2001:2::/64");
        verify(mHardware, times(1)).setUpstreamParameters(eq(RMNET0), any(), any(), any());
        verify(mHardware, times(1)).setDataLimit(eq(RMNET0), anyLong());
        verifyNoMoreInteractions(mHardware);
    }

    private static void assertArrayListContains(ArrayList<String> list, String... elems) {
        for (String element : elems) {
            assertTrue(element + " not in list", list.contains(element));
        }
    }
}
