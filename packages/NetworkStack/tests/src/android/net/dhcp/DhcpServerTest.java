/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.dhcp;

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.dhcp.DhcpPacket.DHCP_CLIENT;
import static android.net.dhcp.DhcpPacket.DHCP_HOST_NAME;
import static android.net.dhcp.DhcpPacket.ENCAP_BOOTP;
import static android.net.dhcp.DhcpPacket.INADDR_ANY;
import static android.net.dhcp.DhcpPacket.INADDR_BROADCAST;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.INetworkStackStatusCallback;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.dhcp.DhcpLeaseRepository.InvalidAddressException;
import android.net.dhcp.DhcpLeaseRepository.OutOfAddressesException;
import android.net.dhcp.DhcpServer.Clock;
import android.net.dhcp.DhcpServer.Dependencies;
import android.net.util.SharedLog;
import android.os.HandlerThread;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class DhcpServerTest {
    private static final String TEST_IFACE = "testiface";

    private static final Inet4Address TEST_SERVER_ADDR = parseAddr("192.168.0.2");
    private static final LinkAddress TEST_SERVER_LINKADDR = new LinkAddress(TEST_SERVER_ADDR, 20);
    private static final Set<Inet4Address> TEST_DEFAULT_ROUTERS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.123"), parseAddr("192.168.0.124")));
    private static final Set<Inet4Address> TEST_DNS_SERVERS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.126"), parseAddr("192.168.0.127")));
    private static final Set<Inet4Address> TEST_EXCLUDED_ADDRS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.200"), parseAddr("192.168.0.201")));
    private static final long TEST_LEASE_TIME_SECS = 3600L;
    private static final int TEST_MTU = 1500;
    private static final String TEST_HOSTNAME = "testhostname";

    private static final int TEST_TRANSACTION_ID = 123;
    private static final byte[] TEST_CLIENT_MAC_BYTES = new byte [] { 1, 2, 3, 4, 5, 6 };
    private static final MacAddress TEST_CLIENT_MAC = MacAddress.fromBytes(TEST_CLIENT_MAC_BYTES);
    private static final Inet4Address TEST_CLIENT_ADDR = parseAddr("192.168.0.42");

    private static final long TEST_CLOCK_TIME = 1234L;
    private static final int TEST_LEASE_EXPTIME_SECS = 3600;
    private static final DhcpLease TEST_LEASE = new DhcpLease(null, TEST_CLIENT_MAC,
            TEST_CLIENT_ADDR, TEST_LEASE_EXPTIME_SECS * 1000L + TEST_CLOCK_TIME,
            null /* hostname */);
    private static final DhcpLease TEST_LEASE_WITH_HOSTNAME = new DhcpLease(null, TEST_CLIENT_MAC,
            TEST_CLIENT_ADDR, TEST_LEASE_EXPTIME_SECS * 1000L + TEST_CLOCK_TIME, TEST_HOSTNAME);

    @NonNull @Mock
    private Dependencies mDeps;
    @NonNull @Mock
    private DhcpLeaseRepository mRepository;
    @NonNull @Mock
    private Clock mClock;
    @NonNull @Mock
    private DhcpPacketListener mPacketListener;

    @NonNull @Captor
    private ArgumentCaptor<ByteBuffer> mSentPacketCaptor;
    @NonNull @Captor
    private ArgumentCaptor<Inet4Address> mResponseDstAddrCaptor;

    @NonNull
    private HandlerThread mHandlerThread;
    @NonNull
    private TestableLooper mLooper;
    @NonNull
    private DhcpServer mServer;

    @Nullable
    private String mPrevShareClassloaderProp;

    private final INetworkStackStatusCallback mAssertSuccessCallback =
            new INetworkStackStatusCallback.Stub() {
        @Override
        public void onStatusAvailable(int statusCode) {
            assertEquals(STATUS_SUCCESS, statusCode);
        }
    };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mDeps.makeLeaseRepository(any(), any(), any())).thenReturn(mRepository);
        when(mDeps.makeClock()).thenReturn(mClock);
        when(mDeps.makePacketListener()).thenReturn(mPacketListener);
        doNothing().when(mDeps)
                .sendPacket(any(), mSentPacketCaptor.capture(), mResponseDstAddrCaptor.capture());
        when(mClock.elapsedRealtime()).thenReturn(TEST_CLOCK_TIME);

        final DhcpServingParams servingParams = new DhcpServingParams.Builder()
                .setDefaultRouters(TEST_DEFAULT_ROUTERS)
                .setDhcpLeaseTimeSecs(TEST_LEASE_TIME_SECS)
                .setDnsServers(TEST_DNS_SERVERS)
                .setServerAddr(TEST_SERVER_LINKADDR)
                .setLinkMtu(TEST_MTU)
                .setExcludedAddrs(TEST_EXCLUDED_ADDRS)
                .build();

        mLooper = TestableLooper.get(this);
        mHandlerThread = spy(new HandlerThread("TestDhcpServer"));
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        mServer = new DhcpServer(mHandlerThread, TEST_IFACE, servingParams,
                new SharedLog(DhcpServerTest.class.getSimpleName()), mDeps);

        mServer.start(mAssertSuccessCallback);
        mLooper.processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        mServer.stop(mAssertSuccessCallback);
        mLooper.processMessages(1);
        verify(mPacketListener, times(1)).stop();
        verify(mHandlerThread, times(1)).quitSafely();
    }

    @Test
    public void testStart() throws Exception {
        verify(mPacketListener, times(1)).start();
    }

    @Test
    public void testDiscover() throws Exception {
        // TODO: refactor packet construction to eliminate unnecessary/confusing/duplicate fields
        when(mRepository.getOffer(isNull() /* clientId */, eq(TEST_CLIENT_MAC),
                eq(INADDR_ANY) /* relayAddr */, isNull() /* reqAddr */, isNull() /* hostname */))
                .thenReturn(TEST_LEASE);

        final DhcpDiscoverPacket discover = new DhcpDiscoverPacket(TEST_TRANSACTION_ID,
                (short) 0 /* secs */, INADDR_ANY /* relayIp */, TEST_CLIENT_MAC_BYTES,
                false /* broadcast */, INADDR_ANY /* srcIp */);
        mServer.processPacket(discover, DHCP_CLIENT);

        assertResponseSentTo(TEST_CLIENT_ADDR);
        final DhcpOfferPacket packet = assertOffer(getPacket());
        assertMatchesTestLease(packet);
    }

    @Test
    public void testDiscover_OutOfAddresses() throws Exception {
        when(mRepository.getOffer(isNull() /* clientId */, eq(TEST_CLIENT_MAC),
                eq(INADDR_ANY) /* relayAddr */, isNull() /* reqAddr */, isNull() /* hostname */))
                .thenThrow(new OutOfAddressesException("Test exception"));

        final DhcpDiscoverPacket discover = new DhcpDiscoverPacket(TEST_TRANSACTION_ID,
                (short) 0 /* secs */, INADDR_ANY /* relayIp */, TEST_CLIENT_MAC_BYTES,
                false /* broadcast */, INADDR_ANY /* srcIp */);
        mServer.processPacket(discover, DHCP_CLIENT);

        assertResponseSentTo(INADDR_BROADCAST);
        final DhcpNakPacket packet = assertNak(getPacket());
        assertMatchesClient(packet);
    }

    private DhcpRequestPacket makeRequestSelectingPacket() {
        final DhcpRequestPacket request = new DhcpRequestPacket(TEST_TRANSACTION_ID,
                (short) 0 /* secs */, INADDR_ANY /* clientIp */, INADDR_ANY /* relayIp */,
                TEST_CLIENT_MAC_BYTES, false /* broadcast */);
        request.mServerIdentifier = TEST_SERVER_ADDR;
        request.mRequestedIp = TEST_CLIENT_ADDR;
        return request;
    }

    @Test
    public void testRequest_Selecting_Ack() throws Exception {
        when(mRepository.requestLease(isNull() /* clientId */, eq(TEST_CLIENT_MAC),
                eq(INADDR_ANY) /* clientAddr */, eq(INADDR_ANY) /* relayAddr */,
                eq(TEST_CLIENT_ADDR) /* reqAddr */, eq(true) /* sidSet */, eq(TEST_HOSTNAME)))
                .thenReturn(TEST_LEASE_WITH_HOSTNAME);

        final DhcpRequestPacket request = makeRequestSelectingPacket();
        request.mHostName = TEST_HOSTNAME;
        request.mRequestedParams = new byte[] { DHCP_HOST_NAME };
        mServer.processPacket(request, DHCP_CLIENT);

        assertResponseSentTo(TEST_CLIENT_ADDR);
        final DhcpAckPacket packet = assertAck(getPacket());
        assertMatchesTestLease(packet, TEST_HOSTNAME);
    }

    @Test
    public void testRequest_Selecting_Nak() throws Exception {
        when(mRepository.requestLease(isNull(), eq(TEST_CLIENT_MAC),
                eq(INADDR_ANY) /* clientAddr */, eq(INADDR_ANY) /* relayAddr */,
                eq(TEST_CLIENT_ADDR) /* reqAddr */, eq(true) /* sidSet */, isNull() /* hostname */))
                .thenThrow(new InvalidAddressException("Test error"));

        final DhcpRequestPacket request = makeRequestSelectingPacket();
        mServer.processPacket(request, DHCP_CLIENT);

        assertResponseSentTo(INADDR_BROADCAST);
        final DhcpNakPacket packet = assertNak(getPacket());
        assertMatchesClient(packet);
    }

    @Test
    public void testRequest_Selecting_WrongClientPort() throws Exception {
        final DhcpRequestPacket request = makeRequestSelectingPacket();
        mServer.processPacket(request, 50000);

        verify(mRepository, never())
                .requestLease(any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(mDeps, never()).sendPacket(any(), any(), any());
    }

    @Test
    public void testRelease() throws Exception {
        final DhcpReleasePacket release = new DhcpReleasePacket(TEST_TRANSACTION_ID,
                TEST_SERVER_ADDR, TEST_CLIENT_ADDR,
                INADDR_ANY /* relayIp */, TEST_CLIENT_MAC_BYTES);
        mServer.processPacket(release, DHCP_CLIENT);

        verify(mRepository, times(1))
                .releaseLease(isNull(), eq(TEST_CLIENT_MAC), eq(TEST_CLIENT_ADDR));
    }

    /* TODO: add more tests once packet construction is refactored, including:
     *  - usage of giaddr
     *  - usage of broadcast bit
     *  - other request states (init-reboot/renewing/rebinding)
     */

    private void assertMatchesTestLease(@NonNull DhcpPacket packet, @Nullable String hostname) {
        assertMatchesClient(packet);
        assertFalse(packet.hasExplicitClientId());
        assertEquals(TEST_SERVER_ADDR, packet.mServerIdentifier);
        assertEquals(TEST_CLIENT_ADDR, packet.mYourIp);
        assertNotNull(packet.mLeaseTime);
        assertEquals(TEST_LEASE_EXPTIME_SECS, (int) packet.mLeaseTime);
        assertEquals(hostname, packet.mHostName);
    }

    private void assertMatchesTestLease(@NonNull DhcpPacket packet) {
        assertMatchesTestLease(packet, null);
    }

    private void assertMatchesClient(@NonNull DhcpPacket packet) {
        assertEquals(TEST_TRANSACTION_ID, packet.mTransId);
        assertEquals(TEST_CLIENT_MAC, MacAddress.fromBytes(packet.mClientMac));
    }

    private void assertResponseSentTo(@NonNull Inet4Address addr) {
        assertEquals(addr, mResponseDstAddrCaptor.getValue());
    }

    private static DhcpNakPacket assertNak(@Nullable DhcpPacket packet) {
        assertTrue(packet instanceof DhcpNakPacket);
        return (DhcpNakPacket) packet;
    }

    private static DhcpAckPacket assertAck(@Nullable DhcpPacket packet) {
        assertTrue(packet instanceof DhcpAckPacket);
        return (DhcpAckPacket) packet;
    }

    private static DhcpOfferPacket assertOffer(@Nullable DhcpPacket packet) {
        assertTrue(packet instanceof DhcpOfferPacket);
        return (DhcpOfferPacket) packet;
    }

    private DhcpPacket getPacket() throws Exception {
        verify(mDeps, times(1)).sendPacket(any(), any(), any());
        return DhcpPacket.decodeFullPacket(mSentPacketCaptor.getValue(), ENCAP_BOOTP);
    }

    private static Inet4Address parseAddr(@Nullable String inet4Addr) {
        return (Inet4Address) parseNumericAddress(inet4Addr);
    }
}
