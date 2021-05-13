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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.ConnectivityService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class Nat464XlatTest {

    static final String BASE_IFACE = "test0";
    static final String STACKED_IFACE = "v4-test0";
    static final LinkAddress V6ADDR = new LinkAddress("2001:db8:1::f00/64");
    static final LinkAddress ADDR = new LinkAddress("192.0.2.5/29");
    static final String NAT64_PREFIX = "64:ff9b::/96";
    static final String OTHER_NAT64_PREFIX = "2001:db8:0:64::/96";
    static final int NETID = 42;

    @Mock ConnectivityService mConnectivity;
    @Mock IDnsResolver mDnsResolver;
    @Mock INetd mNetd;
    @Mock NetworkAgentInfo mNai;

    TestLooper mLooper;
    Handler mHandler;
    NetworkAgentConfig mAgentConfig = new NetworkAgentConfig();

    Nat464Xlat makeNat464Xlat(boolean isCellular464XlatEnabled) {
        return new Nat464Xlat(mNai, mNetd, mDnsResolver, new ConnectivityService.Dependencies()) {
            @Override protected int getNetId() {
                return NETID;
            }

            @Override protected boolean isCellular464XlatEnabled() {
                return isCellular464XlatEnabled;
            }
        };
    }

    private void markNetworkConnected() {
        mNai.networkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, "", "");
    }

    private void markNetworkDisconnected() {
        mNai.networkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, "", "");
    }

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());

        MockitoAnnotations.initMocks(this);

        mNai.linkProperties = new LinkProperties();
        mNai.linkProperties.setInterfaceName(BASE_IFACE);
        mNai.networkInfo = new NetworkInfo(null);
        mNai.networkInfo.setType(ConnectivityManager.TYPE_WIFI);
        mNai.networkCapabilities = new NetworkCapabilities();
        markNetworkConnected();
        when(mNai.connService()).thenReturn(mConnectivity);
        when(mNai.netAgentConfig()).thenReturn(mAgentConfig);
        when(mNai.handler()).thenReturn(mHandler);
        final InterfaceConfigurationParcel mConfig = new InterfaceConfigurationParcel();
        when(mNetd.interfaceGetCfg(eq(STACKED_IFACE))).thenReturn(mConfig);
        mConfig.ipv4Addr = ADDR.getAddress().getHostAddress();
        mConfig.prefixLength =  ADDR.getPrefixLength();
    }

    private void assertRequiresClat(boolean expected, NetworkAgentInfo nai) {
        Nat464Xlat nat = makeNat464Xlat(true);
        String msg = String.format("requiresClat expected %b for type=%d state=%s skip=%b "
                + "nat64Prefix=%s addresses=%s", expected, nai.networkInfo.getType(),
                nai.networkInfo.getDetailedState(),
                mAgentConfig.skip464xlat, nai.linkProperties.getNat64Prefix(),
                nai.linkProperties.getLinkAddresses());
        assertEquals(msg, expected, nat.requiresClat(nai));
    }

    private void assertShouldStartClat(boolean expected, NetworkAgentInfo nai) {
        Nat464Xlat nat = makeNat464Xlat(true);
        String msg = String.format("shouldStartClat expected %b for type=%d state=%s skip=%b "
                + "nat64Prefix=%s addresses=%s", expected, nai.networkInfo.getType(),
                nai.networkInfo.getDetailedState(),
                mAgentConfig.skip464xlat, nai.linkProperties.getNat64Prefix(),
                nai.linkProperties.getLinkAddresses());
        assertEquals(msg, expected, nat.shouldStartClat(nai));
    }

    @Test
    public void testRequiresClat() throws Exception {
        final int[] supportedTypes = {
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_ETHERNET,
        };

        // NetworkInfo doesn't allow setting the State directly, but rather
        // requires setting DetailedState in order set State as a side-effect.
        final NetworkInfo.DetailedState[] supportedDetailedStates = {
            NetworkInfo.DetailedState.CONNECTED,
            NetworkInfo.DetailedState.SUSPENDED,
        };

        LinkProperties oldLp = new LinkProperties(mNai.linkProperties);
        for (int type : supportedTypes) {
            mNai.networkInfo.setType(type);
            for (NetworkInfo.DetailedState state : supportedDetailedStates) {
                mNai.networkInfo.setDetailedState(state, "reason", "extraInfo");

                mNai.linkProperties.setNat64Prefix(new IpPrefix(OTHER_NAT64_PREFIX));
                assertRequiresClat(false, mNai);
                assertShouldStartClat(false, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("fc00::1/64"));
                assertRequiresClat(false, mNai);
                assertShouldStartClat(false, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("2001:db8::1/64"));
                assertRequiresClat(true, mNai);
                assertShouldStartClat(true, mNai);

                mAgentConfig.skip464xlat = true;
                assertRequiresClat(false, mNai);
                assertShouldStartClat(false, mNai);

                mAgentConfig.skip464xlat = false;
                assertRequiresClat(true, mNai);
                assertShouldStartClat(true, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("192.0.2.2/24"));
                assertRequiresClat(false, mNai);
                assertShouldStartClat(false, mNai);

                mNai.linkProperties.removeLinkAddress(new LinkAddress("192.0.2.2/24"));
                assertRequiresClat(true, mNai);
                assertShouldStartClat(true, mNai);

                mNai.linkProperties.setNat64Prefix(null);
                assertRequiresClat(true, mNai);
                assertShouldStartClat(false, mNai);

                mNai.linkProperties = new LinkProperties(oldLp);
            }
        }
    }

    private void makeClatUnnecessary(boolean dueToDisconnect) {
        if (dueToDisconnect) {
            markNetworkDisconnected();
        } else {
            mNai.linkProperties.addLinkAddress(ADDR);
        }
    }

    private void checkNormalStartAndStop(boolean dueToDisconnect) throws Exception {
        Nat464Xlat nat = makeNat464Xlat(true);
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        mNai.linkProperties.addLinkAddress(V6ADDR);

        nat.setNat64PrefixFromDns(new IpPrefix(NAT64_PREFIX));

        // Start clat.
        nat.start();

        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        verify(mNetd).interfaceGetCfg(eq(STACKED_IFACE));
        verify(mConnectivity).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // Stop clat (Network disconnects, IPv4 addr appears, ...).
        makeClatUnnecessary(dueToDisconnect);
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        verify(mDnsResolver).stopPrefix64Discovery(eq(NETID));
        assertIdle(nat);

        // Stacked interface removed notification arrives and is ignored.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        verifyNoMoreInteractions(mNetd, mConnectivity);
    }

    @Test
    public void testNormalStartAndStopDueToDisconnect() throws Exception {
        checkNormalStartAndStop(true);
    }

    @Test
    public void testNormalStartAndStopDueToIpv4Addr() throws Exception {
        checkNormalStartAndStop(false);
    }

    private void checkStartStopStart(boolean interfaceRemovedFirst) throws Exception {
        Nat464Xlat nat = makeNat464Xlat(true);
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);
        InOrder inOrder = inOrder(mNetd, mConnectivity);

        mNai.linkProperties.addLinkAddress(V6ADDR);

        nat.setNat64PrefixFromDns(new IpPrefix(NAT64_PREFIX));

        nat.start();

        inOrder.verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        inOrder.verify(mConnectivity).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // ConnectivityService stops clat (Network disconnects, IPv4 addr appears, ...).
        nat.stop();

        inOrder.verify(mNetd).clatdStop(eq(BASE_IFACE));

        inOrder.verify(mConnectivity, times(1)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        if (interfaceRemovedFirst) {
            // Stacked interface removed notification arrives and is ignored.
            nat.interfaceRemoved(STACKED_IFACE);
            mLooper.dispatchNext();
            nat.interfaceLinkStateChanged(STACKED_IFACE, false);
            mLooper.dispatchNext();
        }

        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);
        inOrder.verifyNoMoreInteractions();

        nat.start();

        inOrder.verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        if (!interfaceRemovedFirst) {
            // Stacked interface removed notification arrives and is ignored.
            nat.interfaceRemoved(STACKED_IFACE);
            mLooper.dispatchNext();
            nat.interfaceLinkStateChanged(STACKED_IFACE, false);
            mLooper.dispatchNext();
        }

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        inOrder.verify(mConnectivity).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // ConnectivityService stops clat again.
        nat.stop();

        inOrder.verify(mNetd).clatdStop(eq(BASE_IFACE));

        inOrder.verify(mConnectivity, times(1)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testStartStopStart() throws Exception {
        checkStartStopStart(true);
    }

    @Test
    public void testStartStopStartBeforeInterfaceRemoved() throws Exception {
        checkStartStopStart(false);
    }

    @Test
    public void testClatdCrashWhileRunning() throws Exception {
        Nat464Xlat nat = makeNat464Xlat(true);
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        nat.setNat64PrefixFromDns(new IpPrefix(NAT64_PREFIX));

        nat.start();

        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        verify(mNetd).interfaceGetCfg(eq(STACKED_IFACE));
        verify(mConnectivity, times(1)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // Stacked interface removed notification arrives (clatd crashed, ...).
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        verify(mDnsResolver).stopPrefix64Discovery(eq(NETID));
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        // ConnectivityService stops clat: no-op.
        nat.stop();

        verifyNoMoreInteractions(mNetd, mConnectivity);
    }

    private void checkStopBeforeClatdStarts(boolean dueToDisconnect) throws Exception {
        Nat464Xlat nat = makeNat464Xlat(true);

        mNai.linkProperties.addLinkAddress(new LinkAddress("2001:db8::1/64"));

        nat.setNat64PrefixFromDns(new IpPrefix(NAT64_PREFIX));

        nat.start();

        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        makeClatUnnecessary(dueToDisconnect);
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mDnsResolver).stopPrefix64Discovery(eq(NETID));
        assertIdle(nat);

        // In-flight interface up notification arrives: no-op
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        // Interface removed notification arrives after stopClatd() takes effect: no-op.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        assertIdle(nat);

        verifyNoMoreInteractions(mNetd, mConnectivity);
    }

    @Test
    public void testStopDueToDisconnectBeforeClatdStarts() throws Exception {
        checkStopBeforeClatdStarts(true);
    }

    @Test
    public void testStopDueToIpv4AddrBeforeClatdStarts() throws Exception {
        checkStopBeforeClatdStarts(false);
    }

    private void checkStopAndClatdNeverStarts(boolean dueToDisconnect) throws Exception {
        Nat464Xlat nat = makeNat464Xlat(true);

        mNai.linkProperties.addLinkAddress(new LinkAddress("2001:db8::1/64"));

        nat.setNat64PrefixFromDns(new IpPrefix(NAT64_PREFIX));

        nat.start();

        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        makeClatUnnecessary(dueToDisconnect);
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mDnsResolver).stopPrefix64Discovery(eq(NETID));
        assertIdle(nat);

        verifyNoMoreInteractions(mNetd, mConnectivity);
    }

    @Test
    public void testStopDueToDisconnectAndClatdNeverStarts() throws Exception {
        checkStopAndClatdNeverStarts(true);
    }

    @Test
    public void testStopDueToIpv4AddressAndClatdNeverStarts() throws Exception {
        checkStopAndClatdNeverStarts(false);
    }

    @Test
    public void testNat64PrefixPreference() throws Exception {
        final IpPrefix prefixFromDns = new IpPrefix(NAT64_PREFIX);
        final IpPrefix prefixFromRa = new IpPrefix(OTHER_NAT64_PREFIX);

        Nat464Xlat nat = makeNat464Xlat(true);

        final LinkProperties emptyLp = new LinkProperties();
        LinkProperties fixedupLp;

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromDns(prefixFromDns);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(prefixFromDns, fixedupLp.getNat64Prefix());

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromRa(prefixFromRa);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(prefixFromRa, fixedupLp.getNat64Prefix());

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromRa(null);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(prefixFromDns, fixedupLp.getNat64Prefix());

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromRa(prefixFromRa);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(prefixFromRa, fixedupLp.getNat64Prefix());

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromDns(null);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(prefixFromRa, fixedupLp.getNat64Prefix());

        fixedupLp = new LinkProperties();
        nat.setNat64PrefixFromRa(null);
        nat.fixupLinkProperties(emptyLp, fixedupLp);
        assertEquals(null, fixedupLp.getNat64Prefix());
    }

    private void checkClatDisabledOnCellular(boolean onCellular) throws Exception {
        // Disable 464xlat on cellular networks.
        Nat464Xlat nat = makeNat464Xlat(false);
        mNai.linkProperties.addLinkAddress(V6ADDR);
        mNai.networkCapabilities.setTransportType(TRANSPORT_CELLULAR, onCellular);
        nat.update();

        final IpPrefix nat64Prefix = new IpPrefix(NAT64_PREFIX);
        if (onCellular) {
            // Prefix discovery is never started.
            verify(mDnsResolver, never()).startPrefix64Discovery(eq(NETID));
            assertIdle(nat);

            // If a NAT64 prefix comes in from an RA, clat is not started either.
            mNai.linkProperties.setNat64Prefix(nat64Prefix);
            nat.setNat64PrefixFromRa(nat64Prefix);
            nat.update();
            verify(mNetd, never()).clatdStart(anyString(), anyString());
            assertIdle(nat);
        } else {
            // Prefix discovery is started.
            verify(mDnsResolver).startPrefix64Discovery(eq(NETID));
            assertIdle(nat);

            // If a NAT64 prefix comes in from an RA, clat is started.
            mNai.linkProperties.setNat64Prefix(nat64Prefix);
            nat.setNat64PrefixFromRa(nat64Prefix);
            nat.update();
            verify(mNetd).clatdStart(BASE_IFACE, NAT64_PREFIX);
            assertStarting(nat);
        }
    }

    @Test
    public void testClatDisabledOnCellular() throws Exception {
        checkClatDisabledOnCellular(true);
    }

    @Test
    public void testClatDisabledOnNonCellular() throws Exception {
        checkClatDisabledOnCellular(false);
    }

    static void assertIdle(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not IDLE", !nat.isStarted());
    }

    static void assertStarting(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not STARTING", nat.isStarting());
    }

    static void assertRunning(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not RUNNING", nat.isRunning());
    }
}
