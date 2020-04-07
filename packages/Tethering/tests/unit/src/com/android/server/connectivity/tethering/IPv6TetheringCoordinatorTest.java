/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.RouteInfo.RTN_UNICAST;
import static android.net.ip.IpServer.STATE_LOCAL_ONLY;
import static android.net.ip.IpServer.STATE_TETHERED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.ip.IpServer;
import android.net.util.SharedLog;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IPv6TetheringCoordinatorTest {
    private static final String TEST_DNS_SERVER = "2001:4860:4860::8888";
    private static final String TEST_INTERFACE = "test_rmnet0";
    private static final String TEST_IPV6_ADDRESS = "2001:db8::1/64";
    private static final String TEST_IPV4_ADDRESS = "192.168.100.1/24";

    private IPv6TetheringCoordinator mIPv6TetheringCoordinator;
    private ArrayList<IpServer> mNotifyList;

    @Mock private SharedLog mSharedLog;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mSharedLog.forSubComponent(anyString())).thenReturn(mSharedLog);
        mNotifyList = new ArrayList<IpServer>();
        mIPv6TetheringCoordinator = new IPv6TetheringCoordinator(mNotifyList, mSharedLog);
    }

    private UpstreamNetworkState createDualStackUpstream(final int transportType) {
        final Network network = mock(Network.class);
        final NetworkCapabilities netCap =
                new NetworkCapabilities.Builder().addTransportType(transportType).build();
        final InetAddress dns = InetAddresses.parseNumericAddress(TEST_DNS_SERVER);
        final LinkProperties linkProp = new LinkProperties();
        linkProp.setInterfaceName(TEST_INTERFACE);
        linkProp.addLinkAddress(new LinkAddress(TEST_IPV6_ADDRESS));
        linkProp.addLinkAddress(new LinkAddress(TEST_IPV4_ADDRESS));
        linkProp.addRoute(new RouteInfo(new IpPrefix("::/0"), null, TEST_INTERFACE, RTN_UNICAST));
        linkProp.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0"), null, TEST_INTERFACE,
                    RTN_UNICAST));
        linkProp.addDnsServer(dns);
        return new UpstreamNetworkState(linkProp, netCap, network);
    }

    private void assertOnlyOneV6AddressAndNoV4(LinkProperties lp) {
        assertEquals(lp.getInterfaceName(), TEST_INTERFACE);
        assertFalse(lp.hasIpv4Address());
        final List<LinkAddress> addresses = lp.getLinkAddresses();
        assertEquals(addresses.size(), 1);
        final LinkAddress v6Address = addresses.get(0);
        assertEquals(v6Address, new LinkAddress(TEST_IPV6_ADDRESS));
    }

    @Test
    public void testUpdateIpv6Upstream() throws Exception {
        // 1. Add first IpServer.
        final IpServer firstServer = mock(IpServer.class);
        mNotifyList.add(firstServer);
        mIPv6TetheringCoordinator.addActiveDownstream(firstServer, STATE_TETHERED);
        verify(firstServer).sendMessage(IpServer.CMD_IPV6_TETHER_UPDATE, 0, 0, null);
        verifyNoMoreInteractions(firstServer);

        // 2. Add second IpServer and it would not have ipv6 tethering.
        final IpServer secondServer = mock(IpServer.class);
        mNotifyList.add(secondServer);
        mIPv6TetheringCoordinator.addActiveDownstream(secondServer, STATE_LOCAL_ONLY);
        verifyNoMoreInteractions(secondServer);
        reset(firstServer, secondServer);

        // 3. No upstream.
        mIPv6TetheringCoordinator.updateUpstreamNetworkState(null);
        verify(secondServer).sendMessage(IpServer.CMD_IPV6_TETHER_UPDATE, 0, 0, null);
        reset(firstServer, secondServer);

        // 4. Update ipv6 mobile upstream.
        final UpstreamNetworkState mobileUpstream = createDualStackUpstream(TRANSPORT_CELLULAR);
        final ArgumentCaptor<LinkProperties> lp = ArgumentCaptor.forClass(LinkProperties.class);
        mIPv6TetheringCoordinator.updateUpstreamNetworkState(mobileUpstream);
        verify(firstServer).sendMessage(eq(IpServer.CMD_IPV6_TETHER_UPDATE), eq(0), eq(0),
                lp.capture());
        final LinkProperties v6OnlyLink = lp.getValue();
        assertOnlyOneV6AddressAndNoV4(v6OnlyLink);
        verifyNoMoreInteractions(firstServer);
        verifyNoMoreInteractions(secondServer);
        reset(firstServer, secondServer);

        // 5. Remove first IpServer.
        mNotifyList.remove(firstServer);
        mIPv6TetheringCoordinator.removeActiveDownstream(firstServer);
        verify(firstServer).sendMessage(IpServer.CMD_IPV6_TETHER_UPDATE, 0, 0, null);
        verify(secondServer).sendMessage(eq(IpServer.CMD_IPV6_TETHER_UPDATE), eq(0), eq(0),
                lp.capture());
        final LinkProperties localOnlyLink = lp.getValue();
        assertNotNull(localOnlyLink);
        assertNotEquals(localOnlyLink, v6OnlyLink);
        reset(firstServer, secondServer);

        // 6. Remove second IpServer.
        mNotifyList.remove(secondServer);
        mIPv6TetheringCoordinator.removeActiveDownstream(secondServer);
        verifyNoMoreInteractions(firstServer);
        verify(secondServer).sendMessage(IpServer.CMD_IPV6_TETHER_UPDATE, 0, 0, null);
    }
}
