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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class Nat464XlatTest {

    static final String BASE_IFACE = "test0";
    static final String STACKED_IFACE = "v4-test0";
    static final LinkAddress ADDR = new LinkAddress("192.0.2.5/29");

    @Mock ConnectivityService mConnectivity;
    @Mock NetworkMisc mMisc;
    @Mock INetworkManagementService mNms;
    @Mock InterfaceConfiguration mConfig;
    @Mock NetworkAgentInfo mNai;

    TestLooper mLooper;
    Handler mHandler;

    Nat464Xlat makeNat464Xlat() {
        return new Nat464Xlat(mNms, mNai);
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

        for (int type : supportedTypes) {
            mNai.networkInfo.setType(type);
            for (NetworkInfo.DetailedState state : supportedDetailedStates) {
                mNai.networkInfo.setDetailedState(state, "reason", "extraInfo");
                String msg = String.format("requiresClat expected for type=%d state=%s",
                        type, state);

                mMisc.skip464xlat = true;
                String errorMsg = msg + String.format(" skip464xlat=%b", mMisc.skip464xlat);
                assertFalse(errorMsg, Nat464Xlat.requiresClat(mNai));

                mMisc.skip464xlat = false;
                errorMsg = msg + String.format(" skip464xlat=%b", mMisc.skip464xlat);
                assertTrue(errorMsg, Nat464Xlat.requiresClat(mNai));
            }
        }
    }

    @Test
    public void testNormalStartAndStop() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNms).startClatd(eq(BASE_IFACE));

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

        verify(mNms).stopClatd(eq(BASE_IFACE));

        // Stacked interface removed notification arrives.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        verify(mNms).unregisterObserver(eq(nat));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        verifyNoMoreInteractions(mNms, mConnectivity);
    }

    @Test
    public void testClatdCrashWhileRunning() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();
        ArgumentCaptor<LinkProperties> c = ArgumentCaptor.forClass(LinkProperties.class);

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNms).startClatd(eq(BASE_IFACE));

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

        verify(mNms).unregisterObserver(eq(nat));
        verify(mNms).stopClatd(eq(BASE_IFACE));
        verify(mConnectivity, times(2)).handleUpdateLinkProperties(eq(mNai), c.capture());
        assertTrue(c.getValue().getStackedLinks().isEmpty());
        assertFalse(c.getValue().getAllInterfaceNames().contains(STACKED_IFACE));
        assertIdle(nat);

        // ConnectivityService stops clat: no-op.
        nat.stop();

        verifyNoMoreInteractions(mNms, mConnectivity);
    }

    @Test
    public void testStopBeforeClatdStarts() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNms).startClatd(eq(BASE_IFACE));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        nat.stop();

        verify(mNms).unregisterObserver(eq(nat));
        verify(mNms).stopClatd(eq(BASE_IFACE));
        assertIdle(nat);

        // In-flight interface up notification arrives: no-op
        nat.interfaceLinkStateChanged(STACKED_IFACE, true);
        mLooper.dispatchNext();


        // Interface removed notification arrives after stopClatd() takes effect: no-op.
        nat.interfaceRemoved(STACKED_IFACE);
        mLooper.dispatchNext();

        assertIdle(nat);

        verifyNoMoreInteractions(mNms, mConnectivity);
    }

    @Test
    public void testStopAndClatdNeverStarts() throws Exception {
        Nat464Xlat nat = makeNat464Xlat();

        // ConnectivityService starts clat.
        nat.start();

        verify(mNms).registerObserver(eq(nat));
        verify(mNms).startClatd(eq(BASE_IFACE));

        // ConnectivityService immediately stops clat (Network disconnects, IPv4 addr appears, ...)
        nat.stop();

        verify(mNms).unregisterObserver(eq(nat));
        verify(mNms).stopClatd(eq(BASE_IFACE));
        assertIdle(nat);

        verifyNoMoreInteractions(mNms, mConnectivity);
    }

    static void assertIdle(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not IDLE", !nat.isStarted());
    }

    static void assertRunning(Nat464Xlat nat) {
        assertTrue("Nat464Xlat was not RUNNING", nat.isRunning());
    }
}
