/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static android.net.ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.ConnectivityManager.TETHERING_BLUETOOTH;
import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TETHERING_WIFI;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_AVAILABLE;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_TETHERED;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_UNAVAILABLE;

import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.util.InterfaceSet;
import android.net.util.SharedLog;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import java.net.Inet4Address;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetherInterfaceStateMachineTest {
    private static final String IFACE_NAME = "testnet1";
    private static final String UPSTREAM_IFACE = "upstream0";
    private static final String UPSTREAM_IFACE2 = "upstream1";

    @Mock private INetworkManagementService mNMService;
    @Mock private INetworkStatsService mStatsService;
    @Mock private IControlsTethering mTetherHelper;
    @Mock private InterfaceConfiguration mInterfaceConfiguration;
    @Mock private SharedLog mSharedLog;
    @Mock private TetheringDependencies mTetheringDependencies;

    private final TestLooper mLooper = new TestLooper();
    private final ArgumentCaptor<LinkProperties> mLinkPropertiesCaptor =
            ArgumentCaptor.forClass(LinkProperties.class);
    private TetherInterfaceStateMachine mTestedSm;

    private void initStateMachine(int interfaceType) throws Exception {
        mTestedSm = new TetherInterfaceStateMachine(
                IFACE_NAME, mLooper.getLooper(), interfaceType, mSharedLog,
                mNMService, mStatsService, mTetherHelper, mTetheringDependencies);
        mTestedSm.start();
        // Starting the state machine always puts us in a consistent state and notifies
        // the rest of the world that we've changed from an unknown to available state.
        mLooper.dispatchAll();
        reset(mNMService, mStatsService, mTetherHelper);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);
    }

    private void initTetheredStateMachine(int interfaceType, String upstreamIface) throws Exception {
        initStateMachine(interfaceType);
        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED, STATE_TETHERED);
        if (upstreamIface != null) {
            dispatchTetherConnectionChanged(upstreamIface);
        }
        reset(mNMService, mStatsService, mTetherHelper);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mSharedLog.forSubComponent(anyString())).thenReturn(mSharedLog);
    }

    @Test
    public void startsOutAvailable() {
        mTestedSm = new TetherInterfaceStateMachine(IFACE_NAME, mLooper.getLooper(),
                TETHERING_BLUETOOTH, mSharedLog, mNMService, mStatsService, mTetherHelper,
                mTetheringDependencies);
        mTestedSm.start();
        mLooper.dispatchAll();
        verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mTetherHelper).updateLinkProperties(eq(mTestedSm), any(LinkProperties.class));
        verifyNoMoreInteractions(mTetherHelper, mNMService, mStatsService);
    }

    @Test
    public void shouldDoNothingUntilRequested() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);
        final int [] NOOP_COMMANDS = {
            TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED,
            TetherInterfaceStateMachine.CMD_IP_FORWARDING_ENABLE_ERROR,
            TetherInterfaceStateMachine.CMD_IP_FORWARDING_DISABLE_ERROR,
            TetherInterfaceStateMachine.CMD_START_TETHERING_ERROR,
            TetherInterfaceStateMachine.CMD_STOP_TETHERING_ERROR,
            TetherInterfaceStateMachine.CMD_SET_DNS_FORWARDERS_ERROR,
            TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED
        };
        for (int command : NOOP_COMMANDS) {
            // None of these commands should trigger us to request action from
            // the rest of the system.
            dispatchCommand(command);
            verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
        }
    }

    @Test
    public void handlesImmediateInterfaceDown() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
        verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mTetherHelper).updateLinkProperties(eq(mTestedSm), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canBeTethered() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mTetherHelper, mNMService);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canUnrequestTethering() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, null);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mStatsService, mTetherHelper);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mNMService).setInterfaceConfig(eq(IFACE_NAME), any());
        inOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canBeTetheredAsUsb() throws Exception {
        initStateMachine(TETHERING_USB);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mTetherHelper, mNMService);
        inOrder.verify(mNMService).getInterfaceConfig(IFACE_NAME);
        inOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), mLinkPropertiesCaptor.capture());
        assertIPv4AddressAndDirectlyConnectedRoute(mLinkPropertiesCaptor.getValue());
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void handlesFirstUpstreamChange() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, null);

        // Telling the state machine about its upstream interface triggers
        // a little more configuration.
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder inOrder = inOrder(mNMService);
        inOrder.verify(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).startInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void handlesChangingUpstream() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, UPSTREAM_IFACE);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNMService, mStatsService);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNMService).startInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE2);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void handlesChangingUpstreamNatFailure() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        doThrow(RemoteException.class).when(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE2);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNMService, mStatsService);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE2);
    }

    @Test
    public void handlesChangingUpstreamInterfaceForwardingFailure() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        doThrow(RemoteException.class).when(mNMService).startInterfaceForwarding(
                IFACE_NAME, UPSTREAM_IFACE2);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNMService, mStatsService);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNMService).startInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE2);
    }

    @Test
    public void canUnrequestTetheringWithUpstream() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, UPSTREAM_IFACE);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mStatsService, mTetherHelper);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mNMService).setInterfaceConfig(eq(IFACE_NAME), any());
        inOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void interfaceDownLeadsToUnavailable() throws Exception {
        for (boolean shouldThrow : new boolean[]{true, false}) {
            initTetheredStateMachine(TETHERING_USB, null);

            if (shouldThrow) {
                doThrow(RemoteException.class).when(mNMService).untetherInterface(IFACE_NAME);
            }
            dispatchCommand(TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
            InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
            usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
            usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                    IFACE_NAME, mInterfaceConfiguration);
            usbTeardownOrder.verify(mTetherHelper).updateInterfaceState(
                    mTestedSm, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
            usbTeardownOrder.verify(mTetherHelper).updateLinkProperties(
                    eq(mTestedSm), mLinkPropertiesCaptor.capture());
            assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
        }
    }

    @Test
    public void usbShouldBeTornDownOnTetherError() throws Exception {
        initStateMachine(TETHERING_USB);

        doThrow(RemoteException.class).when(mNMService).tetherInterface(IFACE_NAME);
        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_AVAILABLE, TETHER_ERROR_TETHER_IFACE_ERROR);
        usbTeardownOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void shouldTearDownUsbOnUpstreamError() throws Exception {
        initTetheredStateMachine(TETHERING_USB, null);

        doThrow(RemoteException.class).when(mNMService).enableNat(anyString(), anyString());
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mTetherHelper).updateInterfaceState(
                mTestedSm, STATE_AVAILABLE, TETHER_ERROR_ENABLE_NAT_ERROR);
        usbTeardownOrder.verify(mTetherHelper).updateLinkProperties(
                eq(mTestedSm), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void ignoresDuplicateUpstreamNotifications() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);

        for (int i = 0; i < 5; i++) {
            dispatchTetherConnectionChanged(UPSTREAM_IFACE);
            verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
        }
    }

    /**
     * Send a command to the state machine under test, and run the event loop to idle.
     *
     * @param command One of the TetherInterfaceStateMachine.CMD_* constants.
     * @param arg1 An additional argument to pass.
     */
    private void dispatchCommand(int command, int arg1) {
        mTestedSm.sendMessage(command, arg1);
        mLooper.dispatchAll();
    }

    /**
     * Send a command to the state machine under test, and run the event loop to idle.
     *
     * @param command One of the TetherInterfaceStateMachine.CMD_* constants.
     */
    private void dispatchCommand(int command) {
        mTestedSm.sendMessage(command);
        mLooper.dispatchAll();
    }

    /**
     * Special override to tell the state machine that the upstream interface has changed.
     *
     * @see #dispatchCommand(int)
     * @param upstreamIface String name of upstream interface (or null)
     */
    private void dispatchTetherConnectionChanged(String upstreamIface) {
        mTestedSm.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED,
                new InterfaceSet(upstreamIface));
        mLooper.dispatchAll();
    }

    private void assertIPv4AddressAndDirectlyConnectedRoute(LinkProperties lp) {
        // Find the first IPv4 LinkAddress.
        LinkAddress addr4 = null;
        for (LinkAddress addr : lp.getLinkAddresses()) {
            if (!(addr.getAddress() instanceof Inet4Address)) continue;
            addr4 = addr;
            break;
        }
        assertTrue("missing IPv4 address", addr4 != null);

        // Assert the presence of the associated directly connected route.
        final RouteInfo directlyConnected = new RouteInfo(addr4, null, lp.getInterfaceName());
        assertTrue("missing directly connected route: '" + directlyConnected.toString() + "'",
                   lp.getRoutes().contains(directlyConnected));
    }

    private void assertNoAddressesNorRoutes(LinkProperties lp) {
        assertTrue(lp.getLinkAddresses().isEmpty());
        assertTrue(lp.getRoutes().isEmpty());
        // We also check that interface name is non-empty, because we should
        // never see an empty interface name in any LinkProperties update.
        assertFalse(TextUtils.isEmpty(lp.getInterfaceName()));
    }
}
