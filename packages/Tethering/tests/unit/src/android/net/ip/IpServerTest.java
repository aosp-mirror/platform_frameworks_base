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

import static android.net.INetd.IF_STATE_UP;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_ENABLE_NAT_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.ip.IpServer.STATE_AVAILABLE;
import static android.net.ip.IpServer.STATE_LOCAL_ONLY;
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
import android.net.InterfaceConfigurationParcel;
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
    private static final String BLUETOOTH_IFACE_ADDR = "192.168.42.1";
    private static final int BLUETOOTH_DHCP_PREFIX_LENGTH = 24;
    private static final int DHCP_LEASE_TIME_SECS = 3600;

    private static final InterfaceParams TEST_IFACE_PARAMS = new InterfaceParams(
            IFACE_NAME, 42 /* index */, MacAddress.ALL_ZEROS_ADDRESS, 1500 /* defaultMtu */);

    private static final int MAKE_DHCPSERVER_TIMEOUT_MS = 1000;

    @Mock private INetd mNetd;
    @Mock private IpServer.Callback mCallback;
    @Mock private SharedLog mSharedLog;
    @Mock private IDhcpServer mDhcpServer;
    @Mock private RouterAdvertisementDaemon mRaDaemon;
    @Mock private IpServer.Dependencies mDependencies;

    @Captor private ArgumentCaptor<DhcpServingParamsParcel> mDhcpParamsCaptor;

    private final TestLooper mLooper = new TestLooper();
    private final ArgumentCaptor<LinkProperties> mLinkPropertiesCaptor =
            ArgumentCaptor.forClass(LinkProperties.class);
    private IpServer mIpServer;
    private InterfaceConfigurationParcel mInterfaceConfiguration;

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
        mInterfaceConfiguration = new InterfaceConfigurationParcel();
        mInterfaceConfiguration.flags = new String[0];
        if (interfaceType == TETHERING_BLUETOOTH) {
            mInterfaceConfiguration.ipv4Addr = BLUETOOTH_IFACE_ADDR;
            mInterfaceConfiguration.prefixLength = BLUETOOTH_DHCP_PREFIX_LENGTH;
        }
        mIpServer = new IpServer(
                IFACE_NAME, mLooper.getLooper(), interfaceType, mSharedLog, mNetd,
                mCallback, usingLegacyDhcp, mDependencies);
        mIpServer.start();
        // Starting the state machine always puts us in a consistent state and notifies
        // the rest of the world that we've changed from an unknown to available state.
        mLooper.dispatchAll();
        reset(mNetd, mCallback);

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
        reset(mNetd, mCallback);
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mSharedLog.forSubComponent(anyString())).thenReturn(mSharedLog);
    }

    @Test
    public void startsOutAvailable() {
        mIpServer = new IpServer(IFACE_NAME, mLooper.getLooper(), TETHERING_BLUETOOTH, mSharedLog,
                mNetd, mCallback, false /* usingLegacyDhcp */, mDependencies);
        mIpServer.start();
        mLooper.dispatchAll();
        verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mCallback).updateLinkProperties(eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mCallback, mNetd);
    }

    @Test
    public void shouldDoNothingUntilRequested() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);
        final int [] noOp_commands = {
            IpServer.CMD_TETHER_UNREQUESTED,
            IpServer.CMD_IP_FORWARDING_ENABLE_ERROR,
            IpServer.CMD_IP_FORWARDING_DISABLE_ERROR,
            IpServer.CMD_START_TETHERING_ERROR,
            IpServer.CMD_STOP_TETHERING_ERROR,
            IpServer.CMD_SET_DNS_FORWARDERS_ERROR,
            IpServer.CMD_TETHER_CONNECTION_CHANGED
        };
        for (int command : noOp_commands) {
            // None of these commands should trigger us to request action from
            // the rest of the system.
            dispatchCommand(command);
            verifyNoMoreInteractions(mNetd, mCallback);
        }
    }

    @Test
    public void handlesImmediateInterfaceDown() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(IpServer.CMD_INTERFACE_DOWN);
        verify(mCallback).updateInterfaceState(
                mIpServer, STATE_UNAVAILABLE, TETHER_ERROR_NO_ERROR);
        verify(mCallback).updateLinkProperties(eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void canBeTethered() throws Exception {
        initStateMachine(TETHERING_BLUETOOTH);

        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mCallback, mNetd);
        inOrder.verify(mNetd).tetherInterfaceAdd(IFACE_NAME);
        inOrder.verify(mNetd).networkAddInterface(INetd.LOCAL_NET_ID, IFACE_NAME);
        // One for ipv4 route, one for ipv6 link local route.
        inOrder.verify(mNetd, times(2)).networkAddRoute(eq(INetd.LOCAL_NET_ID), eq(IFACE_NAME),
                any(), any());
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void canUnrequestTethering() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, null);

        dispatchCommand(IpServer.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNetd, mCallback);
        inOrder.verify(mNetd).tetherApplyDnsInterfaces();
        inOrder.verify(mNetd).tetherInterfaceRemove(IFACE_NAME);
        inOrder.verify(mNetd).networkRemoveInterface(INetd.LOCAL_NET_ID, IFACE_NAME);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void canBeTetheredAsUsb() throws Exception {
        initStateMachine(TETHERING_USB);

        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder inOrder = inOrder(mCallback, mNetd);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg ->
                  IFACE_NAME.equals(cfg.ifName) && assertContainsFlag(cfg.flags, IF_STATE_UP)));
        inOrder.verify(mNetd).tetherInterfaceAdd(IFACE_NAME);
        inOrder.verify(mNetd).networkAddInterface(INetd.LOCAL_NET_ID, IFACE_NAME);
        inOrder.verify(mNetd, times(2)).networkAddRoute(eq(INetd.LOCAL_NET_ID), eq(IFACE_NAME),
                any(), any());
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_TETHERED, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertIPv4AddressAndDirectlyConnectedRoute(mLinkPropertiesCaptor.getValue());
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void canBeTetheredAsWifiP2p() throws Exception {
        initStateMachine(TETHERING_WIFI_P2P);

        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_LOCAL_ONLY);
        InOrder inOrder = inOrder(mCallback, mNetd);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg ->
                  IFACE_NAME.equals(cfg.ifName) && assertNotContainsFlag(cfg.flags, IF_STATE_UP)));
        inOrder.verify(mNetd).tetherInterfaceAdd(IFACE_NAME);
        inOrder.verify(mNetd).networkAddInterface(INetd.LOCAL_NET_ID, IFACE_NAME);
        inOrder.verify(mNetd, times(2)).networkAddRoute(eq(INetd.LOCAL_NET_ID), eq(IFACE_NAME),
                any(), any());
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_LOCAL_ONLY, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertIPv4AddressAndDirectlyConnectedRoute(mLinkPropertiesCaptor.getValue());
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void handlesFirstUpstreamChange() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, null);

        // Telling the state machine about its upstream interface triggers
        // a little more configuration.
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder inOrder = inOrder(mNetd);
        inOrder.verify(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).ipfwdAddInterfaceForward(IFACE_NAME, UPSTREAM_IFACE);
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void handlesChangingUpstream() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, UPSTREAM_IFACE);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNetd);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).ipfwdAddInterfaceForward(IFACE_NAME, UPSTREAM_IFACE2);
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void handlesChangingUpstreamNatFailure() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        doThrow(RemoteException.class).when(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE2);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNetd);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE2);
    }

    @Test
    public void handlesChangingUpstreamInterfaceForwardingFailure() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        doThrow(RemoteException.class).when(mNetd).ipfwdAddInterfaceForward(
                IFACE_NAME, UPSTREAM_IFACE2);

        dispatchTetherConnectionChanged(UPSTREAM_IFACE2);
        InOrder inOrder = inOrder(mNetd);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).ipfwdAddInterfaceForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE2);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE2);
    }

    @Test
    public void canUnrequestTetheringWithUpstream() throws Exception {
        initTetheredStateMachine(TETHERING_BLUETOOTH, UPSTREAM_IFACE);

        dispatchCommand(IpServer.CMD_TETHER_UNREQUESTED);
        InOrder inOrder = inOrder(mNetd, mCallback);
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherRemoveForward(IFACE_NAME, UPSTREAM_IFACE);
        inOrder.verify(mNetd).tetherApplyDnsInterfaces();
        inOrder.verify(mNetd).tetherInterfaceRemove(IFACE_NAME);
        inOrder.verify(mNetd).networkRemoveInterface(INetd.LOCAL_NET_ID, IFACE_NAME);
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        inOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_NO_ERROR);
        inOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), any(LinkProperties.class));
        verifyNoMoreInteractions(mNetd, mCallback);
    }

    @Test
    public void interfaceDownLeadsToUnavailable() throws Exception {
        for (boolean shouldThrow : new boolean[]{true, false}) {
            initTetheredStateMachine(TETHERING_USB, null);

            if (shouldThrow) {
                doThrow(RemoteException.class).when(mNetd).tetherInterfaceRemove(IFACE_NAME);
            }
            dispatchCommand(IpServer.CMD_INTERFACE_DOWN);
            InOrder usbTeardownOrder = inOrder(mNetd, mCallback);
            // Currently IpServer interfaceSetCfg twice to stop IPv4. One just set interface down
            // Another one is set IPv4 to 0.0.0.0/0 as clearng ipv4 address.
            usbTeardownOrder.verify(mNetd, times(2)).interfaceSetCfg(
                    argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
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

        doThrow(RemoteException.class).when(mNetd).tetherInterfaceAdd(IFACE_NAME);
        dispatchCommand(IpServer.CMD_TETHER_REQUESTED, STATE_TETHERED);
        InOrder usbTeardownOrder = inOrder(mNetd, mCallback);
        usbTeardownOrder.verify(mNetd).interfaceSetCfg(
                argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        usbTeardownOrder.verify(mNetd).tetherInterfaceAdd(IFACE_NAME);

        usbTeardownOrder.verify(mNetd, times(2)).interfaceSetCfg(
                argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        usbTeardownOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_TETHER_IFACE_ERROR);
        usbTeardownOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void shouldTearDownUsbOnUpstreamError() throws Exception {
        initTetheredStateMachine(TETHERING_USB, null);

        doThrow(RemoteException.class).when(mNetd).tetherAddForward(anyString(), anyString());
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);
        InOrder usbTeardownOrder = inOrder(mNetd, mCallback);
        usbTeardownOrder.verify(mNetd).tetherAddForward(IFACE_NAME, UPSTREAM_IFACE);

        usbTeardownOrder.verify(mNetd, times(2)).interfaceSetCfg(
                argThat(cfg -> IFACE_NAME.equals(cfg.ifName)));
        usbTeardownOrder.verify(mCallback).updateInterfaceState(
                mIpServer, STATE_AVAILABLE, TETHER_ERROR_ENABLE_NAT_ERROR);
        usbTeardownOrder.verify(mCallback).updateLinkProperties(
                eq(mIpServer), mLinkPropertiesCaptor.capture());
        assertNoAddressesNorRoutes(mLinkPropertiesCaptor.getValue());
    }

    @Test
    public void ignoresDuplicateUpstreamNotifications() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE);

        verifyNoMoreInteractions(mNetd, mCallback);

        for (int i = 0; i < 5; i++) {
            dispatchTetherConnectionChanged(UPSTREAM_IFACE);
            verifyNoMoreInteractions(mNetd, mCallback);
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
    public void startsDhcpServerOnWifiP2p() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI_P2P, UPSTREAM_IFACE);
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);

        assertDhcpStarted(new IpPrefix("192.168.49.0/24"));
    }

    @Test
    public void doesNotStartDhcpServerIfDisabled() throws Exception {
        initTetheredStateMachine(TETHERING_WIFI, UPSTREAM_IFACE, true /* usingLegacyDhcp */);
        dispatchTetherConnectionChanged(UPSTREAM_IFACE);

        verify(mDependencies, never()).makeDhcpServer(any(), any(), any());
    }

    private void assertDhcpStarted(IpPrefix expectedPrefix) throws Exception {
        verify(mDependencies, times(1)).makeDhcpServer(eq(IFACE_NAME), any(), any());
        verify(mDhcpServer, timeout(MAKE_DHCPSERVER_TIMEOUT_MS).times(1)).startWithCallbacks(
                any(), any());
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

        final IpPrefix destination = new IpPrefix(addr4.getAddress(), addr4.getPrefixLength());
        // Assert the presence of the associated directly connected route.
        final RouteInfo directlyConnected = new RouteInfo(destination, null, lp.getInterfaceName(),
                RouteInfo.RTN_UNICAST);
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

    private boolean assertContainsFlag(String[] flags, String match) {
        for (String flag : flags) {
            if (flag.equals(match)) return true;
        }
        fail("Missing flag: " + match);
        return false;
    }

    private boolean assertNotContainsFlag(String[] flags, String match) {
        for (String flag : flags) {
            if (flag.equals(match)) {
                fail("Unexpected flag: " + match);
                return false;
            }
        }
        return true;
    }
}
