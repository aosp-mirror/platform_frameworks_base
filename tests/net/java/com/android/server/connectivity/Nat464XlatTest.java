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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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
    static final LinkAddress ADDR = new LinkAddress("192.0.2.5/29");
    static final String NAT64_PREFIX = "64:ff9b::/96";

    @Mock ConnectivityService mConnectivity;
    @Mock NetworkMisc mMisc;
    @Mock INetd mNetd;
    @Mock INetworkManagementService mNms;
    @Mock InterfaceConfiguration mConfig;
    @Mock NetworkAgentInfo mNai;

    TestLooper mLooper;
    Handler mHandler;

    Nat464Xlat makeNat464Xlat() {
        return new Nat464Xlat(mNai, mNetd, mNms);
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
        when(mNai.connService()).thenReturn(mConnectivity);
        when(mNai.netMisc()).thenReturn(mMisc);
        when(mNai.handler()).thenReturn(mHandler);

        when(mNms.getInterfaceConfig(eq(STACKED_IFACE))).thenReturn(mConfig);
        when(mConfig.getLinkAddress()).thenReturn(ADDR);
    }

    private void assertRequiresClat(boolean expected, NetworkAgentInfo nai) {
        String msg = String.format("requiresClat expected %b for type=%d state=%s skip464xlat=%b "
                + "nat64Prefix=%s addresses=%s", expected, nai.networkInfo.getType(),
                nai.networkInfo.getDetailedState(),
                mMisc.skip464xlat, nai.linkProperties.getNat64Prefix(),
                nai.linkProperties.getLinkAddresses());
        assertEquals(msg, expected, Nat464Xlat.requiresClat(nai));
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

                mNai.linkProperties.setNat64Prefix(new IpPrefix("2001:db8:0:64::/96"));
                assertRequiresClat(false, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("fc00::1/64"));
                assertRequiresClat(false, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("2001:db8::1/64"));
                assertRequiresClat(true, mNai);

                mMisc.skip464xlat = true;
                assertRequiresClat(false, mNai);

                mMisc.skip464xlat = false;
                assertRequiresClat(true, mNai);

                mNai.linkProperties.addLinkAddress(new LinkAddress("192.0.2.2/24"));
                assertRequiresClat(false, mNai);

                mNai.linkProperties = new LinkProperties(oldLp);
            }
        }
    }

    @Test
    public void testNormalStartAndStop() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        nat.setNat64Prefix(new IpPrefix(NAT64_PREFIX));

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        verify(mNms).getInterfaceConfig(eq(STACKED_IFACE));
        verify(mConnectivity).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // ConnectivityService stops clat (Network disconnects, IPv4 addr appears, ...).
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        verify(mNms).unregisterObserver(eq(nat));
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        // Stacked interface removed notification arrives and is ignored.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        verifyNoMoreInteractions(mNetd, mNms, mConnectivity);
    }

    private void checkStartStopStart(boolean interfaceRemovedFirst) throws Exception {
        Nat464Xlat nat = makeNat464Xlat();
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);
        InOrder inOrder = inOrder(mNetd, mConnectivity);

        nat.setNat64Prefix(new IpPrefix(NAT64_PREFIX));

        // ConnectivityService starts clat.
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

        // ConnectivityService starts clatd again.
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
        Nat464Xlat nat = makeNat464Xlat();
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        nat.setNat64Prefix(new IpPrefix(NAT64_PREFIX));

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // Stacked interface up notification arrives.
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();

        verify(mNms).getInterfaceConfig(eq(STACKED_IFACE));
        verify(mConnectivity, times(1)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertFalse(c.getValue().getStackedLinks().isEmpty());
        assertTrue(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertRunning(nat);

        // Stacked interface removed notification arrives (clatd crashed, ...).
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        verify(mNms).unregisterObserver(eq(nat));
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        // ConnectivityService stops clat: no-op.
        nat.stop();

        verifyNoMoreInteractions(mNetd, mNms, mConnectivity);
    }

    @Test
    public void testStopBeforeClatdStarts() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();

        nat.setNat64Prefix(new IpPrefix(NAT64_PREFIX));

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mNms).unregisterObserver(eq(nat));
        assertIdle(nat);

        // In-flight interface up notification arrives: no-op
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();


        // Interface removed notification arrives after stopClatd() takes effect: no-op.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        assertIdle(nat);

        verifyNoMoreInteractions(mNetd, mNms, mConnectivity);
    }

    @Test
    public void testStopAndClatdNeverStarts() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();

        nat.setNat64Prefix(new IpPrefix(NAT64_PREFIX));

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNetd).clatdStart(eq(BASE_IFACE), eq(NAT64_PREFIX));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        nat.stop();

        verify(mNetd).clatdStop(eq(BASE_IFACE));
        verify(mNms).unregisterObserver(eq(nat));
        assertIdle(nat);

        verifyNoMoreInteractions(mNetd, mNms, mConnectivity);
    }

    static void assertIdle(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not IDLE", !nat.isStarted());
    }

    static void assertRunning(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not RUNNING", nat.isRunning());
    }
}
