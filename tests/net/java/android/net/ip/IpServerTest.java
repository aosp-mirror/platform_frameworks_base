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

package android.net.ip;

import static android.net.ConnectivityManager.TETHERING_BLUETOOTH;
import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TETHERING_WIFI;
import static android.net.ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.ip.IpServer.STATE_AVAILABLE;
import static android.net.ip.IpServer.STATE_TETHERED;
import static android.net.ip.IpServer.STATE_UNAVAILABLE;
import static android.net.shared.Inet4AddressUtils.intToInet4AddressHTH;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.INetd;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.RouteInfo;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServer;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.util.InterfaceParams;
import android.net.util.InterfaceSet;
import android.net.util.SharedLog;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpServerTest {
    private static final String IFACE_NAME = "testnet1";
    private static final String UPSTREAM_IFACE = "upstream0";
    private static final String UPSTREAM_IFACE2 = "upstream1";
    private static final int DHCP_LEASE_TIME_SECS = 3600;

    private static final InterfaceParams TEST_IFACE_PARAMS = new InterfaceParams(
            IFACE_NAME, 42 /* index */, MacAddress.ALL_ZEROS_ADDRESS, 1500 /* defaultMtu */);

    private static final int MAKE_DHCPSERVER_TIMEOUT_MS = 1000;

    @Mock private INetworkManagementService mNMService;
    @Mock private INetd mNetd;
    @Mock private INetworkStatsService mStatsService;
    @Mock private IpServer.Callback mCallback;
    @Mock private InterfaceConfiguration mInterfaceConfiguration;
    @Mock private SharedLog mSharedLog;
    @Mock private IDhcpServer mDhcpServer;
    @Mock private RouterAdvertisementDaemon mRaDaemon;
    @Mock private IpServer.Dependencies mDependencies;

    @Captor private ArgumentCaptor<DhcpServingParamsParcel> mDhcpParamsCaptor;

    private final TestLooper mLooper = new TestLooper();
    private final ArgumentCaptor<LinkProperties> mLinkPropertiesCaptor =
            ArgumentCaptor.forClass(LinkProperties.class);
    private IpServer mIpServer;

    private void initStateMachine(int interfaceType) throws Exception {
        initStateMachine(interfaceType, false /* usingLegacyDhcp */);
    }

    private void initStateMachine(int interfaceType, boolean usingLegacyDhcp) throws Exception {
        doAnswer(inv -> {
            final IDhcpServerCallbacks cb = inv.getArgument(2);
            new Thread(() -> {
                try {
                    cb.onDhcpServerCreated(STATUS_SUCCESS, mDhcpServer);
                } catch (RemoteException e) {
                    fail(e.getMessage());
                }
            }).run();
            return null;
        }).when(mDependencies).makeDhcpServer(any(), mDhcpParamsCaptor.capture(), any());
        when(mDependencies.getRouterAdvertisementDaemon(any())).thenReturn(mRaDaemon);
        when(mDependencies.getInterfaceParams(IFACE_NAME)).thenReturn(TEST_IFACE_PARAMS);
        when(mDependencies.getNetdService()).thenReturn(mNetd);

        mIpServer = new IpServer(
                IFACE_NAME, mLooper.getLooper(), interfaceType, mSharedLog,
                mNMService, mStatsService, mCallback, usingLegacyDhcp, mDependencies);
        mIpServer.start();
        // Starting the state machine always puts us in a consistent state and notifies
        // the rest of the world that we've changed from an unknown to available state.
        mLooper.dispatchAll();
        reset(mNMService, mStatsService, mCallback);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);

        when(mRaDaemon.start()).thenReturn(true);
    }

    private void initTetheredStateMachine(int interfaceType, String upstreamIface)
            throws Exception {
        initTetheredStateMachine(interfaceType, upstreamIface, false);
    }

    private void initTetheredStateMachine(int interfaceType, String upstreamIface,
            boolean usingLegacyDhcp) throws Exception {
        initStateMachine(interfaceType, usingLegacyDhcp);
        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        if (upstreamIface != null) {
            dispatchTetherConnectionChanged(upstreamIface);
        }
        reset(mNMService, mStatsService, mCallback);
        when(mNMService.getInterfaceConfig(IFACE_NAME)).thenReturn(mInterfaceConfiguration);
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mSharedLog.forSubComponent(anyString())).thenReturn(mSharedLog);
    }

    @Test
    public void startsOutAvailable() {
        mIpServer = new IpServer(IFACE_NAME, mLooper.getLooper(),
                TETHERING_BLUETOOTH, mSharedLog, mNMService, mStatsService, mCallback,
                false /* usingLegacyDhcp */, mDependencies);
        mIpServer.start();
        mLooper.dispatchAll();
        verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mCallback).updateLinkProperties(eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mCallback, mNMService, mStatsService);
    }

    @Test
    public void shouldDoNothingUntilRequested() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);
        final int [] NOOP_COMMANDS = {
            IpServer.CMD_TETHER_UNREQUESTED,
            IpServer.CMD_IP_FORWARDING_ENABLE_ERROR,
            IpServer.CMD_IP_FORWARDING_DISABLE_ERROR,
            IpServer.CMD_START_TETHERING_ERROR,
            IpServer.CMD_STOP_TETHERING_ERROR,
            IpServer.CMD_SET_DNS_FORWARDERS_ERROR,
            IpServer.CMD_TETHER_CONNECTION_CHANGED
        };
        for (int command : NOOP_COMMANDS) {
            // None of these commands should trigger us to request action from
            // the rest of the system.
            dispatchCommand(command);
            verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
        }
    }

    @Test
    public void handlesImmediateInterfaceDown() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(IpServer.CMD_INTERFACE_DOWN);
        verify(mCallback).updateInterfaceState(
                mIpServer, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mCallback).updateLinkProperties(eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
    }

    @Test
    public void canBeTethered() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mCallback, mNMService);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
    }

    @Test
    public void canUnrequestTethering() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, null);

        dispatchCommand(IpServer.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mNetd, mStatsService, mCallback);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
    }

    @Test
    public void canBeTetheredAsUsb() throws Exception {
        initStateMachine(TETHERING_USB);

        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mCallback, mNMService);
        inOrder.verify(mNMService).getInterfaceConfig(IFACE_NAME);
        inOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        inOrder.verify(mNMService).tetherInterface(IFACE_NAME);
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertIPv4AddressAndDirectlyConnectedRoute(mLinkPropertiesCaptor.getValue());
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
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
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
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
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
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

        dispatchCommand(IpServer.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNMService, mNetd, mStatsService, mCallback);
        inOrder.verify(mStatsService).forceUpdate();
        inOrder.verify(mNMService).stopInterfaceForwarding(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).disableNat(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNMService).untetherInterface(IFACE_NAME);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
    }

    @Test
    public void interfaceDownLeadsToUnavailable() throws Exception {
        for (boolean shouldThrow : new boolean[]{true, false}) {
            initTetheredStateMachine(TETHERING_USB, null);

            if (shouldThrow) {
                doThrow(RemoteException.class).when(mNMService).untetherInterface(IFACE_NAME);
            }
            dispatchCommand(IpServer.CMD_INTERFACE_DOWN);
            InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mCallback);
            usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
            usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                    IFACE_NAME, mInterfaceConfiguration);
            usbTeardownOrder.verify(mCallback).updateInterfaceState(
                    mIpServer, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
            usbTeardownOrder.verify(mCallback).updateLinkProperties(
                    eq(mIpServer), mLinkPropertiesCaptor.capture());
            assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
        }
    }

    @Test
    public void usbShouldBeTornDownOnTetherError() throws Exception {
        initStateMachine(TETHERING_USB);

        doThrow(RemoteException.class).when(mNMService).tetherInterface(IFACE_NAME);
        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mCallback);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(
                IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_TETHER_IFACE_ERROR);
        usbTeardownOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void shouldTearDownUsbOnUpstreamError() throws Exception {
        initTetheredStateMachine(TETHERING_USB, null);

        doThrow(RemoteException.class).when(mNMService).enableNat(anyString(), anyString());
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder usbTeardownOrder = inOrder(mNMService, mInterfaceConfiguration, mCallback);
        usbTeardownOrder.verify(mInterfaceConfiguration).setInterfaceDown();
        usbTeardownOrder.verify(mNMService).setInterfaceConfig(IFACE_NAME, mInterfaceConfiguration);
        usbTeardownOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_ENABLE_NAT_ERROR);
        usbTeardownOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void ignoresDuplicateUpstreamNotifications() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        verifyNoMoreInteractions(mNMService, mStatsService, mCallback);

        for (int i = 0; i < 5; i++) {
            dispatchTetherConnectionChanged(UPSTREAM_IFACE);
            verifyNoMoreInteractions(mNMService, mStatsService, mCallback);
        }
    }

    @Test
    public void startsDhcpServer() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);

        assertDhcpStarted(new IpPrefix("192.168.43.0/24"));
    }

    @Test
    public void startsDhcpServerOnBluetooth() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, UPSTREAM_IFACE);
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);

        assertDhcpStarted(new IpPrefix("192.168.44.0/24"));
    }

    @Test
    public void doesNotStartDhcpServerIfDisabled() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE, true /* usingLegacyDhcp */);
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);

        verify(mDependencies, never()).makeDhcpServer(any(), any(), any());
    }

    private void assertDhcpStarted(IpPrefix expectedPrefix) throws Exception {
        verify(mDependencies, times(1)).makeDhcpServer(eq(IFACE_NAME), any(), any());
        verify(mDhcpServer, timeout(MAKE_DHCPSERVER_TIMEOUT_MS).times(1)).start(any());
        final DhcpServingParamsParcel params = mDhcpParamsCaptor.getValue();
        // Last address byte is random
        assertTrue(expectedPrefix.contains(intToInet4AddressHTH(params.serverAddr)));
        assertEquals(expectedPrefix.getPrefixLength(), params.serverAddrPrefixLength);
        assertEquals(1, params.defaultRouters.length);
        assertEquals(params.serverAddr, params.defaultRouters[0]);
        assertEquals(1, params.dnsServers.length);
        assertEquals(params.serverAddr, params.dnsServers[0]);
        assertEquals(DHCP_LEASE_TIME_SECS, params.dhcpLeaseTimeSecs);
    }

    /**
     * Send a command to the state machine under test, and run the event loop to idle.
     *
     * @param command One of the IpServer.CMD_* constants.
     * @param arg1 An additional argument to pass.
     */
    private void dispatchCommand(int command, int arg1) {
        mIpServer.sendMessage(command, arg1);
        mLooper.dispatchAll();
    }

    /**
     * Send a command to the state machine under test, and run the event loop to idle.
     *
     * @param command One of the IpServer.CMD_* constants.
     */
    private void dispatchCommand(int command) {
        mIpServer.sendMessage(command);
        mLooper.dispatchAll();
    }

    /**
     * Special override to tell the state machine that the upstream interface has changed.
     *
     * @see #dispatchCommand(int)
     * @param upstreamIface String name of upstream interface (or null)
     */
    private void dispatchTetherConnectionChanged(String upstreamIface) {
        mIpServer.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED,
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
        assertNotNull("missing IPv4 address", addr4);

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
