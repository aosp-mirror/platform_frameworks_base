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
import static android.net.dhcp.DhcpLease.HOSTNAME_NONE;
import static android.net.dhcp.DhcpLeaseRepository.CLIENTID_UNSPEC;
import static android.net.dhcp.DhcpLeaseRepository.INETADDR_UNSPEC;

import static com.android.server.util.NetworkStackConstants.IPV4_ADDR_ANY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import static java.lang.String.format;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IpPrefix;
import android.net.MacAddress;
import android.net.dhcp.DhcpServer.Clock;
import android.net.util.SharedLog;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DhcpLeaseRepositoryTest {
    private static final Inet4Address TEST_DEF_ROUTER = parseAddr4("192.168.42.247");
    private static final Inet4Address TEST_SERVER_ADDR = parseAddr4("192.168.42.241");
    private static final Inet4Address TEST_RESERVED_ADDR = parseAddr4("192.168.42.243");
    private static final MacAddress TEST_MAC_1 = MacAddress.fromBytes(
            new byte[] { 5, 4, 3, 2, 1, 0 });
    private static final MacAddress TEST_MAC_2 = MacAddress.fromBytes(
            new byte[] { 0, 1, 2, 3, 4, 5 });
    private static final MacAddress TEST_MAC_3 = MacAddress.fromBytes(
            new byte[] { 0, 1, 2, 3, 4, 6 });
    private static final Inet4Address TEST_INETADDR_1 = parseAddr4("192.168.42.248");
    private static final Inet4Address TEST_INETADDR_2 = parseAddr4("192.168.42.249");
    private static final String TEST_HOSTNAME_1 = "hostname1";
    private static final String TEST_HOSTNAME_2 = "hostname2";
    private static final IpPrefix TEST_IP_PREFIX = new IpPrefix(TEST_SERVER_ADDR, 22);
    private static final long TEST_TIME = 100L;
    private static final int TEST_LEASE_TIME_MS = 3_600_000;
    private static final Set<Inet4Address> TEST_EXCL_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                TEST_SERVER_ADDR, TEST_DEF_ROUTER, TEST_RESERVED_ADDR)));

    @NonNull
    private SharedLog mLog;
    @NonNull @Mock
    private Clock mClock;
    @NonNull
    private DhcpLeaseRepository mRepo;

    private static Inet4Address parseAddr4(String inet4Addr) {
        return (Inet4Address) parseNumericAddress(inet4Addr);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLog = new SharedLog("DhcpLeaseRepositoryTest");
        when(mClock.elapsedRealtime()).thenReturn(TEST_TIME);
        mRepo = new DhcpLeaseRepository(
                TEST_IP_PREFIX, TEST_EXCL_SET, TEST_LEASE_TIME_MS, mLog, mClock);
    }

    /**
     * Request a number of addresses through offer/request. Useful to test address exhaustion.
     * @param nAddr Number of addresses to request.
     */
    private void requestAddresses(byte nAddr) throws Exception {
        final HashSet<Inet4Address> addrs = new HashSet<>();
        byte[] hwAddrBytes = new byte[] { 8, 4, 3, 2, 1, 0 };
        for (byte i = 0; i < nAddr; i++) {
            hwAddrBytes[5] = i;
            MacAddress newMac = MacAddress.fromBytes(hwAddrBytes);
            final String hostname = "host_" + i;
            final DhcpLease lease = mRepo.getOffer(CLIENTID_UNSPEC, newMac,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, hostname);

            assertNotNull(lease);
            assertEquals(newMac, lease.getHwAddr());
            assertEquals(hostname, lease.getHostname());
            assertTrue(format("Duplicate address allocated: %s in %s", lease.getNetAddr(), addrs),
                    addrs.add(lease.getNetAddr()));

            requestLeaseSelecting(newMac, lease.getNetAddr(), hostname);
        }
    }

    @Test
    public void testAddressExhaustion() throws Exception {
        // Use a /28 to quickly run out of addresses
        mRepo.updateParams(new IpPrefix(TEST_SERVER_ADDR, 28), TEST_EXCL_SET, TEST_LEASE_TIME_MS);

        // /28 should have 16 addresses, 14 w/o the first/last, 11 w/o excluded addresses
        requestAddresses((byte) 11);

        try {
            mRepo.getOffer(null, TEST_MAC_2,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
            fail("Should be out of addresses");
        } catch (DhcpLeaseRepository.OutOfAddressesException e) {
            // Expected
        }
    }

    @Test
    public void testUpdateParams_LeaseCleanup() throws Exception {
        // Inside /28:
        final Inet4Address reqAddrIn28 = parseAddr4("192.168.42.242");
        final Inet4Address declinedAddrIn28 = parseAddr4("192.168.42.245");

        // Inside /28, but not available there (first address of the range)
        final Inet4Address declinedFirstAddrIn28 = parseAddr4("192.168.42.240");

        final DhcpLease reqAddrIn28Lease = requestLeaseSelecting(TEST_MAC_1, reqAddrIn28);
        mRepo.markLeaseDeclined(declinedAddrIn28);
        mRepo.markLeaseDeclined(declinedFirstAddrIn28);

        // Inside /22, but outside /28:
        final Inet4Address reqAddrIn22 = parseAddr4("192.168.42.3");
        final Inet4Address declinedAddrIn22 = parseAddr4("192.168.42.4");

        final DhcpLease reqAddrIn22Lease = requestLeaseSelecting(TEST_MAC_3, reqAddrIn22);
        mRepo.markLeaseDeclined(declinedAddrIn22);

        // Address that will be reserved in the updateParams call below
        final Inet4Address reservedAddr = parseAddr4("192.168.42.244");
        final DhcpLease reservedAddrLease = requestLeaseSelecting(TEST_MAC_2, reservedAddr);

        // Update from /22 to /28 and add another reserved address
        Set<Inet4Address> newReserved = new HashSet<>(TEST_EXCL_SET);
        newReserved.add(reservedAddr);
        mRepo.updateParams(new IpPrefix(TEST_SERVER_ADDR, 28), newReserved, TEST_LEASE_TIME_MS);

        assertHasLease(reqAddrIn28Lease);
        assertDeclined(declinedAddrIn28);

        assertNotDeclined(declinedFirstAddrIn28);

        assertNoLease(reqAddrIn22Lease);
        assertNotDeclined(declinedAddrIn22);

        assertNoLease(reservedAddrLease);
    }

    @Test
    public void testGetOffer_StableAddress() throws Exception {
        for (final MacAddress macAddr : new MacAddress[] { TEST_MAC_1, TEST_MAC_2, TEST_MAC_3 }) {
            final DhcpLease lease = mRepo.getOffer(CLIENTID_UNSPEC, macAddr,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);

            // Same lease is offered twice
            final DhcpLease newLease = mRepo.getOffer(CLIENTID_UNSPEC, macAddr,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
            assertEquals(lease, newLease);
        }
    }

    @Test
    public void testUpdateParams_UsesNewPrefix() throws Exception {
        final IpPrefix newPrefix = new IpPrefix(parseAddr4("192.168.123.0"), 24);
        mRepo.updateParams(newPrefix, TEST_EXCL_SET, TEST_LEASE_TIME_MS);

        DhcpLease lease = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
        assertTrue(newPrefix.contains(lease.getNetAddr()));
    }

    @Test
    public void testGetOffer_ExistingLease() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1, TEST_HOSTNAME_1);

        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
        assertEquals(TEST_INETADDR_1, offer.getNetAddr());
        assertEquals(TEST_HOSTNAME_1, offer.getHostname());
    }

    @Test
    public void testGetOffer_ClientIdHasExistingLease() throws Exception {
        final byte[] clientId = new byte[] { 1, 2 };
        mRepo.requestLease(clientId, TEST_MAC_1, IPV4_ADDR_ANY /* clientAddr */,
                IPV4_ADDR_ANY /* relayAddr */, TEST_INETADDR_1 /* reqAddr */, false,
                TEST_HOSTNAME_1);

        // Different MAC, but same clientId
        DhcpLease offer = mRepo.getOffer(clientId, TEST_MAC_2,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
        assertEquals(TEST_INETADDR_1, offer.getNetAddr());
        assertEquals(TEST_HOSTNAME_1, offer.getHostname());
    }

    @Test
    public void testGetOffer_DifferentClientId() throws Exception {
        final byte[] clientId1 = new byte[] { 1, 2 };
        final byte[] clientId2 = new byte[] { 3, 4 };
        mRepo.requestLease(clientId1, TEST_MAC_1, IPV4_ADDR_ANY /* clientAddr */,
                IPV4_ADDR_ANY /* relayAddr */, TEST_INETADDR_1 /* reqAddr */, false,
                TEST_HOSTNAME_1);

        // Same MAC, different client ID
        DhcpLease offer = mRepo.getOffer(clientId2, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
        // Obtains a different address
        assertNotEquals(TEST_INETADDR_1, offer.getNetAddr());
        assertEquals(HOSTNAME_NONE, offer.getHostname());
        assertEquals(TEST_MAC_1, offer.getHwAddr());
    }

    @Test
    public void testGetOffer_RequestedAddress() throws Exception {
        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1, IPV4_ADDR_ANY /* relayAddr */,
                TEST_INETADDR_1 /* reqAddr */, TEST_HOSTNAME_1);
        assertEquals(TEST_INETADDR_1, offer.getNetAddr());
        assertEquals(TEST_HOSTNAME_1, offer.getHostname());
    }

    @Test
    public void testGetOffer_RequestedAddressInUse() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);
        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_2, IPV4_ADDR_ANY /* relayAddr */,
                TEST_INETADDR_1 /* reqAddr */, HOSTNAME_NONE);
        assertNotEquals(TEST_INETADDR_1, offer.getNetAddr());
    }

    @Test
    public void testGetOffer_RequestedAddressReserved() throws Exception {
        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1, IPV4_ADDR_ANY /* relayAddr */,
                TEST_RESERVED_ADDR /* reqAddr */, HOSTNAME_NONE);
        assertNotEquals(TEST_RESERVED_ADDR, offer.getNetAddr());
    }

    @Test
    public void testGetOffer_RequestedAddressInvalid() throws Exception {
        final Inet4Address invalidAddr = parseAddr4("192.168.42.0");
        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1, IPV4_ADDR_ANY /* relayAddr */,
                invalidAddr /* reqAddr */, HOSTNAME_NONE);
        assertNotEquals(invalidAddr, offer.getNetAddr());
    }

    @Test
    public void testGetOffer_RequestedAddressOutsideSubnet() throws Exception {
        final Inet4Address invalidAddr = parseAddr4("192.168.254.2");
        DhcpLease offer = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1, IPV4_ADDR_ANY /* relayAddr */,
                invalidAddr /* reqAddr */, HOSTNAME_NONE);
        assertNotEquals(invalidAddr, offer.getNetAddr());
    }

    @Test(expected = DhcpLeaseRepository.InvalidSubnetException.class)
    public void testGetOffer_RelayInInvalidSubnet() throws Exception {
        mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1, parseAddr4("192.168.254.2") /* relayAddr */,
                INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
    }

    @Test
    public void testRequestLease_SelectingTwice() throws Exception {
        final DhcpLease lease1 = requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1,
                TEST_HOSTNAME_1);

        // Second request from same client for a different address
        final DhcpLease lease2 = requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_2,
                TEST_HOSTNAME_2);

        assertEquals(TEST_INETADDR_1, lease1.getNetAddr());
        assertEquals(TEST_HOSTNAME_1, lease1.getHostname());

        assertEquals(TEST_INETADDR_2, lease2.getNetAddr());
        assertEquals(TEST_HOSTNAME_2, lease2.getHostname());

        // First address freed when client requested a different one: another client can request it
        final DhcpLease lease3 = requestLeaseSelecting(TEST_MAC_2, TEST_INETADDR_1, HOSTNAME_NONE);
        assertEquals(TEST_INETADDR_1, lease3.getNetAddr());
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_SelectingInvalid() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, parseAddr4("192.168.254.5"));
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_SelectingInUse() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);
        requestLeaseSelecting(TEST_MAC_2, TEST_INETADDR_1);
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_SelectingReserved() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, TEST_RESERVED_ADDR);
    }

    @Test(expected = DhcpLeaseRepository.InvalidSubnetException.class)
    public void testRequestLease_SelectingRelayInInvalidSubnet() throws  Exception {
        mRepo.requestLease(CLIENTID_UNSPEC, TEST_MAC_1, IPV4_ADDR_ANY /* clientAddr */,
                parseAddr4("192.168.128.1") /* relayAddr */, TEST_INETADDR_1 /* reqAddr */,
                true /* sidSet */, HOSTNAME_NONE);
    }

    @Test
    public void testRequestLease_InitReboot() throws Exception {
        // Request address once
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);

        final long newTime = TEST_TIME + 100;
        when(mClock.elapsedRealtime()).thenReturn(newTime);

        // init-reboot (sidSet == false): verify configuration
        final DhcpLease lease = requestLeaseInitReboot(TEST_MAC_1, TEST_INETADDR_1);
        assertEquals(TEST_INETADDR_1, lease.getNetAddr());
        assertEquals(newTime + TEST_LEASE_TIME_MS, lease.getExpTime());
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_InitRebootWrongAddr() throws Exception {
        // Request address once
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);
        // init-reboot with different requested address
        requestLeaseInitReboot(TEST_MAC_1, TEST_INETADDR_2);
    }

    @Test
    public void testRequestLease_InitRebootUnknownAddr() throws Exception {
        // init-reboot with unknown requested address
        final DhcpLease lease = requestLeaseInitReboot(TEST_MAC_1, TEST_INETADDR_2);
        // RFC2131 says we should not reply to accommodate other servers, but since we are
        // authoritative we allow creating the lease to avoid issues with lost lease DB (same as
        // dnsmasq behavior)
        assertEquals(TEST_INETADDR_2, lease.getNetAddr());
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_InitRebootWrongSubnet() throws Exception {
        requestLeaseInitReboot(TEST_MAC_1, parseAddr4("192.168.254.2"));
    }

    @Test
    public void testRequestLease_Renewing() throws Exception {
        requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);

        final long newTime = TEST_TIME + 100;
        when(mClock.elapsedRealtime()).thenReturn(newTime);

        final DhcpLease lease = requestLeaseRenewing(TEST_MAC_1, TEST_INETADDR_1);

        assertEquals(TEST_INETADDR_1, lease.getNetAddr());
        assertEquals(newTime + TEST_LEASE_TIME_MS, lease.getExpTime());
    }

    @Test
    public void testRequestLease_RenewingUnknownAddr() throws Exception {
        final long newTime = TEST_TIME + 100;
        when(mClock.elapsedRealtime()).thenReturn(newTime);
        final DhcpLease lease = requestLeaseRenewing(TEST_MAC_1, TEST_INETADDR_1);
        // Allows renewing an unknown address if available
        assertEquals(TEST_INETADDR_1, lease.getNetAddr());
        assertEquals(newTime + TEST_LEASE_TIME_MS, lease.getExpTime());
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_RenewingAddrInUse() throws Exception {
        requestLeaseSelecting(TEST_MAC_2, TEST_INETADDR_1);
        requestLeaseRenewing(TEST_MAC_1, TEST_INETADDR_1);
    }

    @Test(expected = DhcpLeaseRepository.InvalidAddressException.class)
    public void testRequestLease_RenewingInvalidAddr() throws Exception {
        requestLeaseRenewing(TEST_MAC_1, parseAddr4("192.168.254.2"));
    }

    @Test
    public void testReleaseLease() throws Exception {
        final DhcpLease lease1 = requestLeaseSelecting(TEST_MAC_1, TEST_INETADDR_1);

        assertHasLease(lease1);
        assertTrue(mRepo.releaseLease(CLIENTID_UNSPEC, TEST_MAC_1, TEST_INETADDR_1));
        assertNoLease(lease1);

        final DhcpLease lease2 = requestLeaseSelecting(TEST_MAC_2, TEST_INETADDR_1);
        assertEquals(TEST_INETADDR_1, lease2.getNetAddr());
    }

    @Test
    public void testReleaseLease_UnknownLease() {
        assertFalse(mRepo.releaseLease(CLIENTID_UNSPEC, TEST_MAC_1, TEST_INETADDR_1));
    }

    @Test
    public void testReleaseLease_StableOffer() throws Exception {
        for (MacAddress mac : new MacAddress[] { TEST_MAC_1, TEST_MAC_2, TEST_MAC_3 }) {
            final DhcpLease lease = mRepo.getOffer(CLIENTID_UNSPEC, mac,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);

            requestLeaseSelecting(mac, lease.getNetAddr());
            mRepo.releaseLease(CLIENTID_UNSPEC, mac, lease.getNetAddr());

            // Same lease is offered after it was released
            final DhcpLease newLease = mRepo.getOffer(CLIENTID_UNSPEC, mac,
                    IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
            assertEquals(lease.getNetAddr(), newLease.getNetAddr());
        }
    }

    @Test
    public void testMarkLeaseDeclined() throws Exception {
        final DhcpLease lease = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);

        mRepo.markLeaseDeclined(lease.getNetAddr());

        // Same lease is not offered again
        final DhcpLease newLease = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
        assertNotEquals(lease.getNetAddr(), newLease.getNetAddr());
    }

    @Test
    public void testMarkLeaseDeclined_UsedIfOutOfAddresses() throws Exception {
        // Use a /28 to quickly run out of addresses
        mRepo.updateParams(new IpPrefix(TEST_SERVER_ADDR, 28), TEST_EXCL_SET, TEST_LEASE_TIME_MS);

        mRepo.markLeaseDeclined(TEST_INETADDR_1);
        mRepo.markLeaseDeclined(TEST_INETADDR_2);

        // /28 should have 16 addresses, 14 w/o the first/last, 11 w/o excluded addresses
        requestAddresses((byte) 9);

        // Last 2 addresses: addresses marked declined should be used
        final DhcpLease firstLease = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_1,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, TEST_HOSTNAME_1);
        requestLeaseSelecting(TEST_MAC_1, firstLease.getNetAddr());

        final DhcpLease secondLease = mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_2,
                IPV4_ADDR_ANY /* relayAddr */, INETADDR_UNSPEC /* reqAddr */, TEST_HOSTNAME_2);
        requestLeaseSelecting(TEST_MAC_2, secondLease.getNetAddr());

        // Now out of addresses
        try {
            mRepo.getOffer(CLIENTID_UNSPEC, TEST_MAC_3, IPV4_ADDR_ANY /* relayAddr */,
                    INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE);
            fail("Repository should be out of addresses and throw");
        } catch (DhcpLeaseRepository.OutOfAddressesException e) { /* expected */ }

        assertEquals(TEST_INETADDR_1, firstLease.getNetAddr());
        assertEquals(TEST_HOSTNAME_1, firstLease.getHostname());
        assertEquals(TEST_INETADDR_2, secondLease.getNetAddr());
        assertEquals(TEST_HOSTNAME_2, secondLease.getHostname());
    }

    private DhcpLease requestLease(@NonNull MacAddress macAddr, @NonNull Inet4Address clientAddr,
            @Nullable Inet4Address reqAddr, @Nullable String hostname, boolean sidSet)
            throws DhcpLeaseRepository.DhcpLeaseException {
        return mRepo.requestLease(CLIENTID_UNSPEC, macAddr, clientAddr,
                IPV4_ADDR_ANY /* relayAddr */,
                reqAddr, sidSet, hostname);
    }

    /**
     * Request a lease simulating a client in the SELECTING state.
     */
    private DhcpLease requestLeaseSelecting(@NonNull MacAddress macAddr,
            @NonNull Inet4Address reqAddr, @Nullable String hostname)
            throws DhcpLeaseRepository.DhcpLeaseException {
        return requestLease(macAddr, IPV4_ADDR_ANY /* clientAddr */, reqAddr, hostname,
                true /* sidSet */);
    }

    /**
     * Request a lease simulating a client in the SELECTING state.
     */
    private DhcpLease requestLeaseSelecting(@NonNull MacAddress macAddr,
            @NonNull Inet4Address reqAddr) throws DhcpLeaseRepository.DhcpLeaseException {
        return requestLeaseSelecting(macAddr, reqAddr, HOSTNAME_NONE);
    }

    /**
     * Request a lease simulating a client in the INIT-REBOOT state.
     */
    private DhcpLease requestLeaseInitReboot(@NonNull MacAddress macAddr,
            @NonNull Inet4Address reqAddr) throws DhcpLeaseRepository.DhcpLeaseException {
        return requestLease(macAddr, IPV4_ADDR_ANY /* clientAddr */, reqAddr, HOSTNAME_NONE,
                false /* sidSet */);
    }

    /**
     * Request a lease simulating a client in the RENEWING state.
     */
    private DhcpLease requestLeaseRenewing(@NonNull MacAddress macAddr,
            @NonNull Inet4Address clientAddr) throws DhcpLeaseRepository.DhcpLeaseException {
        // Renewing: clientAddr filled in, no reqAddr
        return requestLease(macAddr, clientAddr, INETADDR_UNSPEC /* reqAddr */, HOSTNAME_NONE,
                true /* sidSet */);
    }

    private void assertNoLease(DhcpLease lease) {
        assertFalse("Leases contain " + lease, mRepo.getCommittedLeases().contains(lease));
    }

    private void assertHasLease(DhcpLease lease) {
        assertTrue("Leases do not contain " + lease, mRepo.getCommittedLeases().contains(lease));
    }

    private void assertNotDeclined(Inet4Address addr) {
        assertFalse("Address is declined: " + addr, mRepo.getDeclinedAddresses().contains(addr));
    }

    private void assertDeclined(Inet4Address addr) {
        assertTrue("Address is not declined: " + addr, mRepo.getDeclinedAddresses().contains(addr));
    }
}
