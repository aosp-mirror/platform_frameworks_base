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
import static android.net.dhcp.DhcpServingParams.MTU_UNSET;
import static android.net.shared.Inet4AddressUtils.inet4AddressToIntHTH;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.dhcp.DhcpServingParams.InvalidParameterException;
import android.net.shared.Inet4AddressUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DhcpServingParamsTest {
    @NonNull
    private DhcpServingParams.Builder mBuilder;

    private static final Set<Inet4Address> TEST_DEFAULT_ROUTERS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.123"), parseAddr("192.168.0.124")));
    private static final long TEST_LEASE_TIME_SECS = 3600L;
    private static final Set<Inet4Address> TEST_DNS_SERVERS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.126"), parseAddr("192.168.0.127")));
    private static final Inet4Address TEST_SERVER_ADDR = parseAddr("192.168.0.2");
    private static final LinkAddress TEST_LINKADDR = new LinkAddress(TEST_SERVER_ADDR, 20);
    private static final int TEST_MTU = 1500;
    private static final Set<Inet4Address> TEST_EXCLUDED_ADDRS = new HashSet<>(
            Arrays.asList(parseAddr("192.168.0.200"), parseAddr("192.168.0.201")));
    private static final boolean TEST_METERED = true;

    @Before
    public void setUp() {
        mBuilder = new DhcpServingParams.Builder()
                .setDefaultRouters(TEST_DEFAULT_ROUTERS)
                .setDhcpLeaseTimeSecs(TEST_LEASE_TIME_SECS)
                .setDnsServers(TEST_DNS_SERVERS)
                .setServerAddr(TEST_LINKADDR)
                .setLinkMtu(TEST_MTU)
                .setExcludedAddrs(TEST_EXCLUDED_ADDRS)
                .setMetered(TEST_METERED);
    }

    @Test
    public void testBuild_Immutable() throws InvalidParameterException {
        final Set<Inet4Address> routers = new HashSet<>(TEST_DEFAULT_ROUTERS);
        final Set<Inet4Address> dnsServers = new HashSet<>(TEST_DNS_SERVERS);
        final Set<Inet4Address> excludedAddrs = new HashSet<>(TEST_EXCLUDED_ADDRS);

        final DhcpServingParams params = mBuilder
                .setDefaultRouters(routers)
                .setDnsServers(dnsServers)
                .setExcludedAddrs(excludedAddrs)
                .build();

        // Modifications to source objects should not affect builder or final parameters
        final Inet4Address addedAddr = parseAddr("192.168.0.223");
        routers.add(addedAddr);
        dnsServers.add(addedAddr);
        excludedAddrs.add(addedAddr);

        assertEquals(TEST_DEFAULT_ROUTERS, params.defaultRouters);
        assertEquals(TEST_LEASE_TIME_SECS, params.dhcpLeaseTimeSecs);
        assertEquals(TEST_DNS_SERVERS, params.dnsServers);
        assertEquals(TEST_LINKADDR, params.serverAddr);
        assertEquals(TEST_MTU, params.linkMtu);
        assertEquals(TEST_METERED, params.metered);

        assertContains(params.excludedAddrs, TEST_EXCLUDED_ADDRS);
        assertContains(params.excludedAddrs, TEST_DEFAULT_ROUTERS);
        assertContains(params.excludedAddrs, TEST_DNS_SERVERS);
        assertContains(params.excludedAddrs, TEST_SERVER_ADDR);

        assertFalse("excludedAddrs should not contain " + addedAddr,
                params.excludedAddrs.contains(addedAddr));
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_NegativeLeaseTime() throws InvalidParameterException {
        mBuilder.setDhcpLeaseTimeSecs(-1).build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_LeaseTimeTooLarge() throws InvalidParameterException {
        // Set lease time larger than max value for uint32
        mBuilder.setDhcpLeaseTimeSecs(1L << 32).build();
    }

    @Test
    public void testBuild_InfiniteLeaseTime() throws InvalidParameterException {
        final long infiniteLeaseTime = 0xffffffffL;
        final DhcpServingParams params = mBuilder
                .setDhcpLeaseTimeSecs(infiniteLeaseTime).build();
        assertEquals(infiniteLeaseTime, params.dhcpLeaseTimeSecs);
        assertTrue(params.dhcpLeaseTimeSecs > 0L);
    }

    @Test
    public void testBuild_UnsetMtu() throws InvalidParameterException {
        final DhcpServingParams params = mBuilder.setLinkMtu(MTU_UNSET).build();
        assertEquals(MTU_UNSET, params.linkMtu);
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_MtuTooSmall() throws InvalidParameterException {
        mBuilder.setLinkMtu(20).build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_MtuTooLarge() throws InvalidParameterException {
        mBuilder.setLinkMtu(65_536).build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_IPv6Addr() throws InvalidParameterException {
        mBuilder.setServerAddr(new LinkAddress(parseNumericAddress("fe80::1111"), 120)).build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_PrefixTooLarge() throws InvalidParameterException {
        mBuilder.setServerAddr(new LinkAddress(TEST_SERVER_ADDR, 15)).build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_PrefixTooSmall() throws InvalidParameterException {
        mBuilder.setDefaultRouters(parseAddr("192.168.0.254"))
                .setServerAddr(new LinkAddress(TEST_SERVER_ADDR, 31))
                .build();
    }

    @Test(expected = InvalidParameterException.class)
    public void testBuild_RouterNotInPrefix() throws InvalidParameterException {
        mBuilder.setDefaultRouters(parseAddr("192.168.254.254")).build();
    }

    @Test
    public void testFromParcelableObject() throws InvalidParameterException {
        final DhcpServingParams params = mBuilder.build();
        final DhcpServingParamsParcel parcel = new DhcpServingParamsParcel();
        parcel.defaultRouters = toIntArray(TEST_DEFAULT_ROUTERS);
        parcel.dhcpLeaseTimeSecs = TEST_LEASE_TIME_SECS;
        parcel.dnsServers = toIntArray(TEST_DNS_SERVERS);
        parcel.serverAddr = inet4AddressToIntHTH(TEST_SERVER_ADDR);
        parcel.serverAddrPrefixLength = TEST_LINKADDR.getPrefixLength();
        parcel.linkMtu = TEST_MTU;
        parcel.excludedAddrs = toIntArray(TEST_EXCLUDED_ADDRS);
        parcel.metered = TEST_METERED;
        final DhcpServingParams parceled = DhcpServingParams.fromParcelableObject(parcel);

        assertEquals(params.defaultRouters, parceled.defaultRouters);
        assertEquals(params.dhcpLeaseTimeSecs, parceled.dhcpLeaseTimeSecs);
        assertEquals(params.dnsServers, parceled.dnsServers);
        assertEquals(params.serverAddr, parceled.serverAddr);
        assertEquals(params.linkMtu, parceled.linkMtu);
        assertEquals(params.excludedAddrs, parceled.excludedAddrs);
        assertEquals(params.metered, parceled.metered);

        // Ensure that we do not miss any field if added in the future
        final long numFields = Arrays.stream(DhcpServingParams.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .count();
        assertEquals(7, numFields);
    }

    @Test(expected = InvalidParameterException.class)
    public void testFromParcelableObject_NullArgument() throws InvalidParameterException {
        DhcpServingParams.fromParcelableObject(null);
    }

    private static int[] toIntArray(Collection<Inet4Address> addrs) {
        return addrs.stream().mapToInt(Inet4AddressUtils::inet4AddressToIntHTH).toArray();
    }

    private static <T> void assertContains(@NonNull Set<T> set, @NonNull Set<T> subset) {
        for (final T elem : subset) {
            assertContains(set, elem);
        }
    }

    private static <T> void assertContains(@NonNull Set<T> set, @Nullable T elem) {
        assertTrue("Set does not contain " + elem, set.contains(elem));
    }

    @NonNull
    private static Inet4Address parseAddr(@NonNull String inet4Addr) {
        return (Inet4Address) parseNumericAddress(inet4Addr);
    }
}
