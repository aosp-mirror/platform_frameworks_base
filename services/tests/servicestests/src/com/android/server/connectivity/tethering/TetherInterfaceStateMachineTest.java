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

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static android.net.ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_AVAILABLE;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_TETHERED;
import static com.android.server.connectivity.tethering.IControlsTethering.STATE_UNAVAILABLE;

import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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

    private final TestLooper mLooper = new TestLooper();
    private TetherInterfaceStateMachine mTestedSm;

    private void initStateMachine(int interfaceType) throws Exception {
        mTestedSm = new TetherInterfaceStateMachine(IFACE_NAME, mLooper.getLooper(), interfaceType,
                mNMService, mStatsService, mTetherHelper);
        mTestedSm.start();
        // Starting the state machine always puts us in a consistent state and notifies
        // the test of the world that we've changed from an unknown to available state.
        mLooper.dispatchAll();
        reset(mNMService, mStatsService, mTetherHelper);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);
    }

    private void initTetheredStateMachine(int interfaceType, String upstreamIface) throws Exception {
        initStateMachine(interfaceType);
        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED);
        if (upstreamIface != null) {
            dispatchTetherConnectionChanged(upstreamIface);
        }
        reset(mNMService, mStatsService, mTetherHelper);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void startsOutAvailable() {
        mTestedSm = new TetherInterfaceStateMachine(IFACE_NAME, mLooper.getLooper(),
                ConnectivityManager.TETHERING_BLUETOOTH, mNMService, mStatsService, mTetherHelper);
        mTestedSm.start();
        mLooper.dispatchAll();
        verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mTetherHelper, mNMService, mStatsService);
    }

    @Test
    public void shouldDoNothingUntilRequested() throws Exception {
        initStateMachine(ConnectivityManager.TETHERING_BLUETOOTH);
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
        initStateMachine(ConnectivityManager.TETHERING_BLUETOOTH);

        dispatchCommand(TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
        verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canBeTethered() throws Exception {
        initStateMachine(ConnectivityManager.TETHERING_BLUETOOTH);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED);
        InOrder inOrder = inOrder(mTetherHelper, mNMService);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canUnrequestTethering() throws Exception {
        initTetheredStateMachine(ConnectivityManager.TETHERING_BLUETOOTH, null);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mStatsService, mTetherHelper);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void canBeTetheredAsUsb() throws Exception {
        initStateMachine(ConnectivityManager.TETHERING_USB);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED);
        InOrder inOrder = inOrder(mTetherHelper, mNMService);
        inOrder.verify(mNMService).getInterfaceConfig(IFACE_NAME);
        inOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void handlesFirstUpstreamChange() throws Exception {
        initTetheredStateMachine(ConnectivityManager.TETHERING_BLUETOOTH, null);

        // Telling the state machine about its upstream interface triggers a little more configuration.
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder inOrder = inOrder(mNMService);
        inOrder.verify(mNMService).enableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).startInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void handlesChangingUpstream() throws Exception {
        initTetheredStateMachine(ConnectivityManager.TETHERING_BLUETOOTH, UPSTREAM_IFACE);

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
    public void canUnrequestTetheringWithUpstream() throws Exception {
        initTetheredStateMachine(ConnectivityManager.TETHERING_BLUETOOTH, UPSTREAM_IFACE);

        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mStatsService, mTetherHelper);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verifyNoMoreInteractions(mNMService, mStatsService, mTetherHelper);
    }

    @Test
    public void interfaceDownLeadsToUnavailable() throws Exception {
        for (boolean shouldThrow : new boolean[]{true, false}) {
            initTetheredStateMachine(ConnectivityManager.TETHERING_USB, null);

            if (shouldThrow) {
                doThrow(RemoteException.class).when(mNMService).untetherInterface(IFACE_NAME);
            }
            dispatchCommand(TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
            InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
            usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
            usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                    IFACE_NAME, mInterfaceConfiguration);
            usbTeardownOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                    IFACE_NAME, mTestedSm, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
        }
    }

    @Test
    public void usbShouldBeTornDownOnTetherError() throws Exception {
        initStateMachine(ConnectivityManager.TETHERING_USB);

        doThrow(RemoteException.class).when(mNMService).tetherInterface(IFACE_NAME);
        dispatchCommand(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_AVAILABLE, TETHER_ERROR_TETHER_IFACE_ERROR);
    }

    @Test
    public void shouldTearDownUsbOnUpstreamError() throws Exception {
        initTetheredStateMachine(ConnectivityManager.TETHERING_USB, null);

        doThrow(RemoteException.class).when(mNMService).enableNat(anyString(), anyString());
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mTetherHelper);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mTetherHelper).notifyInterfaceStateChange(
                IFACE_NAME, mTestedSm, STATE_AVAILABLE, TETHER_ERROR_ENABLE_NAT_ERROR);
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
                upstreamIface);
        mLooper.dispatchAll();
    }
}