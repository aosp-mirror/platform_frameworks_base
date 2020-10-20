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
package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.util.PrefixUtils.asIpPrefix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ip.IpServer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class PrivateAddressCoordinatorTest {
    private static final String TEST_IFNAME = "test0";

    @Mock private IpServer mHotspotIpServer;
    @Mock private IpServer mUsbIpServer;
    @Mock private IpServer mEthernetIpServer;
    @Mock private IpServer mWifiP2pIpServer;
    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityMgr;
    @Mock private TetheringConfiguration mConfig;

    private PrivateAddressCoordinator mPrivateAddressCoordinator;
    private final LinkAddress mBluetoothAddress = new LinkAddress("192.168.44.1/24");
    private final LinkAddress mLegacyWifiP2pAddress = new LinkAddress("192.168.49.1/24");
    private final Network mWifiNetwork = new Network(1);
    private final Network mMobileNetwork = new Network(2);
    private final Network mVpnNetwork = new Network(3);
    private final Network mMobileNetwork2 = new Network(4);
    private final Network mMobileNetwork3 = new Network(5);
    private final Network mMobileNetwork4 = new Network(6);
    private final Network mMobileNetwork5 = new Network(7);
    private final Network mMobileNetwork6 = new Network(8);
    private final Network[] mAllNetworks = {mMobileNetwork, mWifiNetwork, mVpnNetwork,
            mMobileNetwork2, mMobileNetwork3, mMobileNetwork4, mMobileNetwork5, mMobileNetwork6};
    private final ArrayList<IpPrefix> mTetheringPrefixes = new ArrayList<>(Arrays.asList(
            new IpPrefix("192.168.0.0/16"),
            new IpPrefix("172.16.0.0/12"),
            new IpPrefix("10.0.0.0/8")));

    private void setUpIpServers() throws Exception {
        when(mUsbIpServer.interfaceType()).thenReturn(TETHERING_USB);
        when(mEthernetIpServer.interfaceType()).thenReturn(TETHERING_ETHERNET);
        when(mHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mWifiP2pIpServer.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mConnectivityMgr);
        when(mConnectivityMgr.getAllNetworks()).thenReturn(mAllNetworks);
        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(false);
        when(mConfig.isSelectAllPrefixRangeEnabled()).thenReturn(true);
        setUpIpServers();
        mPrivateAddressCoordinator = spy(new PrivateAddressCoordinator(mContext, mConfig));
    }

    private LinkAddress requestDownstreamAddress(final IpServer ipServer, boolean useLastAddress) {
        final LinkAddress address = mPrivateAddressCoordinator.requestDownstreamAddress(
                ipServer, useLastAddress);
        when(ipServer.getAddress()).thenReturn(address);
        return address;
    }

    @Test
    public void testRequestDownstreamAddressWithoutUsingLastAddress() throws Exception {
        final IpPrefix bluetoothPrefix = asIpPrefix(mBluetoothAddress);
        final LinkAddress address = requestDownstreamAddress(mHotspotIpServer,
                false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        assertNotEquals(hotspotPrefix, bluetoothPrefix);

        final LinkAddress newAddress = requestDownstreamAddress(mHotspotIpServer,
                false /* useLastAddress */);
        final IpPrefix testDupRequest = asIpPrefix(newAddress);
        assertNotEquals(hotspotPrefix, testDupRequest);
        assertNotEquals(bluetoothPrefix, testDupRequest);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(usbPrefix, bluetoothPrefix);
        assertNotEquals(usbPrefix, hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);
    }

    @Test
    public void testSanitizedAddress() throws Exception {
        int fakeSubAddr = 0x2b00; // 43.0.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeSubAddr);
        LinkAddress actualAddress = requestDownstreamAddress(mHotspotIpServer,
                false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.43.2/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2d01; // 45.1.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeSubAddr);
        actualAddress = requestDownstreamAddress(mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.45.2/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2eff; // 46.255.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeSubAddr);
        actualAddress = requestDownstreamAddress(mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.46.254/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2f05; // 47.5.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeSubAddr);
        actualAddress = requestDownstreamAddress(mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.47.5/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testReservedPrefix() throws Exception {
        // - Test bluetooth prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mBluetoothAddress.getAddress().getAddress()));
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        // - Test previous enabled hotspot prefix(cached prefix) is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(hotspotAddress.getAddress().getAddress()));
        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), usbPrefix);
        assertNotEquals(hotspotPrefix, usbPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        // - Test wifi p2p prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        final LinkAddress etherAddress = requestDownstreamAddress(mEthernetIpServer,
                false /* useLastAddress */);
        final IpPrefix etherPrefix = asIpPrefix(etherAddress);
        assertNotEquals(asIpPrefix(mLegacyWifiP2pAddress), etherPrefix);
        assertNotEquals(asIpPrefix(mBluetoothAddress), etherPrefix);
        assertNotEquals(hotspotPrefix, etherPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mEthernetIpServer);
    }

    @Test
    public void testRequestLastDownstreamAddress() throws Exception {
        final int fakeHotspotSubAddr = 0x2b05; // 43.5
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeHotspotSubAddr);
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong wifi prefix: ", new LinkAddress("192.168.43.5/24"), hotspotAddress);

        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong wifi prefix: ", new LinkAddress("192.168.45.5/24"), usbAddress);

        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        final int newFakeSubAddr = 0x3c05;
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeHotspotSubAddr);

        final LinkAddress newHotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals(hotspotAddress, newHotspotAddress);
        final LinkAddress newUsbAddress = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        assertEquals(usbAddress, newUsbAddress);

        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/16"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);
        verify(mHotspotIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        verify(mUsbIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
    }

    private UpstreamNetworkState buildUpstreamNetworkState(final Network network,
            final LinkAddress v4Addr, final LinkAddress v6Addr, final NetworkCapabilities cap) {
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFNAME);
        if (v4Addr != null) prop.addLinkAddress(v4Addr);

        if (v6Addr != null) prop.addLinkAddress(v6Addr);

        return new UpstreamNetworkState(prop, cap, network);
    }

    private NetworkCapabilities makeNetworkCapabilities(final int transportType) {
        final NetworkCapabilities cap = new NetworkCapabilities();
        cap.addTransportType(transportType);
        if (transportType == TRANSPORT_VPN) {
            cap.removeCapability(NET_CAPABILITY_NOT_VPN);
        }

        return cap;
    }

    @Test
    public void testNoConflictUpstreamPrefix() throws Exception {
        final int fakeHotspotSubAddr = 0x2b05; // 43.5
        final IpPrefix predefinedPrefix = new IpPrefix("192.168.43.0/24");
        // Force always get subAddress "43.5" for conflict testing.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(fakeHotspotSubAddr);
        // - Enable hotspot with prefix 192.168.43.0/24
        final LinkAddress hotspotAddr = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddr);
        assertEquals("Wrong wifi prefix: ", predefinedPrefix, hotspotPrefix);
        // - test mobile network with null NetworkCapabilities. Ideally this should not happen
        // because NetworkCapabilities update should always happen before LinkProperties update
        // and the UpstreamNetworkState update, just make sure no crash in this case.
        final UpstreamNetworkState noCapUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), null, null);
        mPrivateAddressCoordinator.updateUpstreamPrefix(noCapUpstream);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - test mobile upstream with no address.
        final UpstreamNetworkState noAddress = buildUpstreamNetworkState(mMobileNetwork,
                null, null, makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(noCapUpstream);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v6 only mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v6OnlyMobile = buildUpstreamNetworkState(mMobileNetwork,
                null, new LinkAddress("2001:db8::/64"),
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v6OnlyMobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.removeUpstreamPrefix(mMobileNetwork);
        // - Update v4 only mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v4OnlyMobile = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyMobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v4v6 mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v4v6Mobile = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), new LinkAddress("2001:db8::/64"),
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4v6Mobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v6 only wifi network, hotspot prefix should not be removed.
        final UpstreamNetworkState v6OnlyWifi = buildUpstreamNetworkState(mWifiNetwork,
                null, new LinkAddress("2001:db8::/64"), makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v6OnlyWifi);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.removeUpstreamPrefix(mWifiNetwork);
        // - Update vpn network, it conflict with hotspot prefix but VPN networks are ignored.
        final UpstreamNetworkState v4OnlyVpn = buildUpstreamNetworkState(mVpnNetwork,
                new LinkAddress("192.168.43.5/24"), null, makeNetworkCapabilities(TRANSPORT_VPN));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyVpn);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v4 only wifi network, it conflict with hotspot prefix.
        final UpstreamNetworkState v4OnlyWifi = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.43.5/24"), null, makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyWifi);
        verify(mHotspotIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        reset(mHotspotIpServer);
        // - Restart hotspot again and its prefix is different previous.
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        final LinkAddress hotspotAddr2 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        final IpPrefix hotspotPrefix2 = asIpPrefix(hotspotAddr2);
        assertNotEquals(hotspotPrefix, hotspotPrefix2);
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyWifi);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Usb tethering can be enabled and its prefix is different with conflict one.
        final LinkAddress usbAddr = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddr);
        assertNotEquals(predefinedPrefix, usbPrefix);
        assertNotEquals(hotspotPrefix2, usbPrefix);
        // - Disable wifi upstream, then wifi's prefix can be selected again.
        mPrivateAddressCoordinator.removeUpstreamPrefix(mWifiNetwork);
        final LinkAddress ethAddr = requestDownstreamAddress(mEthernetIpServer,
                true /* useLastAddress */);
        final IpPrefix ethPrefix = asIpPrefix(ethAddr);
        assertEquals(predefinedPrefix, ethPrefix);
    }

    @Test
    public void testChooseAvailablePrefix() throws Exception {
        final int randomAddress = 0x8605; // 134.5
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress addr0 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.134.5.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.134.5/24"), addr0);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.134.13/26"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);

        // Check whether return address is next prefix of 192.168.134.0/24.
        final LinkAddress addr1 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.135.5/24"), addr1);
        final UpstreamNetworkState wifiUpstream2 = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.149.16/19"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream2);


        // The conflict range is 128 ~ 159, so the address is 192.168.160.5/24.
        final LinkAddress addr2 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.160.5/24"), addr2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.168.129.53/18"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        // Update another conflict upstream which is covered by the previous one (but not the first
        // one) and verify whether this would affect the result.
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("192.168.170.7/19"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);

        // The conflict range are 128 ~ 159 and 159 ~ 191, so the address is 192.168.192.5/24.
        final LinkAddress addr3 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.192.5/24"), addr3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("192.168.188.133/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);

        // Conflict range: 128 ~ 255. The next available address is 192.168.0.5 because
        // 192.168.134/24 ~ 192.168.255.255/24 is not available.
        final LinkAddress addr4 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.0.5/24"), addr4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("192.168.3.59/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);

        // Conflict ranges: 128 ~ 255 and 0 ~ 7, so the address is 192.168.8.5/24.
        final LinkAddress addr5 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr5);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("192.168.68.43/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);

        // Update an upstream that does *not* conflict, check whether return the same address
        // 192.168.5/24.
        final LinkAddress addr6 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr6);
        final UpstreamNetworkState mobileUpstream6 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.10.97/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream6);

        // Conflict ranges: 0 ~ 15 and 128 ~ 255, so the address is 192.168.16.5/24.
        final LinkAddress addr7 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.16.5/24"), addr7);
        final UpstreamNetworkState mobileUpstream7 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.0.0/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream7);

        // Choose prefix from next range(172.16.0.0/12) when no available prefix in 192.168.0.0/16.
        final LinkAddress addr8 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.134.5/24"), addr8);
    }

    @Test
    public void testChoosePrefixFromDifferentRanges() throws Exception {
        final int randomAddress = 0x1f2b2a; // 31.43.42
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress classC1 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.43.42.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.43.42/24"), classC1);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/17"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is next address of prefix 192.168.128.0/17.
        final LinkAddress classC2 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.128.42/24"), classC2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.1.2.3/8"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is under prefix 172.16.0.0/12.
        final LinkAddress classB1 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.31.43.42/24"), classB1);
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("172.28.123.100/14"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // 172.28.0.0 ~ 172.31.255.255 is not available.
        // Check whether return address is next address of prefix 172.16.0.0/14.
        final LinkAddress classB2 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.0.42/24"), classB2);

        // Check whether new downstream is next address of address 172.16.0.42/24.
        final LinkAddress classB3 = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.1.42/24"), classB3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("172.16.0.1/24"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verify(mUsbIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);

        // Check whether return address is next address of prefix 172.16.1.42/24.
        final LinkAddress classB4 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.2.42/24"), classB4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("172.16.0.1/13"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is next address of prefix 172.16.0.1/13.
        final LinkAddress classB5 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.0.42/24"), classB5);
        // Check whether return address is next address of prefix 172.24.0.42/24.
        final LinkAddress classB6 = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.1.42/24"), classB6);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("172.24.0.1/12"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is prefix 10.0.0.0/8 + subAddress 0.31.43.42.
        final LinkAddress classA1 = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.43.42/24"), classA1);
        // Check whether new downstream is next address of address 10.31.43.42/24.
        final LinkAddress classA2 = requestDownstreamAddress(mUsbIpServer,
                true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.44.42/24"), classA2);
    }

    private void verifyNotifyConflictAndRelease(final IpServer ipServer) throws Exception {
        verify(ipServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.releaseDownstream(ipServer);
        reset(ipServer);
        setUpIpServers();
    }

    private int getSubAddress(final byte... ipv4Address) {
        assertEquals(4, ipv4Address.length);

        int subnet = Byte.toUnsignedInt(ipv4Address[2]);
        return (subnet << 8) + ipv4Address[3];
    }

    private void assertReseveredWifiP2pPrefix() throws Exception {
        LinkAddress address = requestDownstreamAddress(mHotspotIpServer,
                true /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        final IpPrefix legacyWifiP2pPrefix = asIpPrefix(mLegacyWifiP2pAddress);
        assertNotEquals(legacyWifiP2pPrefix, hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testEnableLegacyWifiP2PAddress() throws Exception {
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        // No matter #shouldEnableWifiP2pDedicatedIp() is enabled or not, legacy wifi p2p prefix
        // is resevered.
        assertReseveredWifiP2pPrefix();

        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(true);
        assertReseveredWifiP2pPrefix();

        // If #shouldEnableWifiP2pDedicatedIp() is enabled, wifi P2P gets the configured address.
        LinkAddress address = requestDownstreamAddress(mWifiP2pIpServer,
                true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);
    }
}
