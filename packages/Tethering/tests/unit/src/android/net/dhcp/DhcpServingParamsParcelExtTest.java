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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.LinkAddress;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DhcpServingParamsParcelExtTest {
    private static final Inet4Address TEST_ADDRESS = inet4Addr("192.168.0.123");
    private static final Inet4Address TEST_CLIENT_ADDRESS = inet4Addr("192.168.0.42");
    private static final int TEST_ADDRESS_PARCELED = 0xc0a8007b;
    private static final int TEST_CLIENT_ADDRESS_PARCELED = 0xc0a8002a;
    private static final int TEST_PREFIX_LENGTH = 17;
    private static final int TEST_LEASE_TIME_SECS = 120;
    private static final int TEST_MTU = 1000;
    private static final Set<Inet4Address> TEST_ADDRESS_SET =
            new HashSet<Inet4Address>(Arrays.asList(
            new Inet4Address[] {inet4Addr("192.168.1.123"), inet4Addr("192.168.1.124")}));
    private static final Set<Integer> TEST_ADDRESS_SET_PARCELED =
            new HashSet<Integer>(Arrays.asList(new Integer[] {0xc0a8017b, 0xc0a8017c}));

    private DhcpServingParamsParcelExt mParcel;

    @Before
    public void setUp() {
        mParcel = new DhcpServingParamsParcelExt();
    }

    @Test
    public void testSetServerAddr() {
        mParcel.setServerAddr(new LinkAddress(TEST_ADDRESS, TEST_PREFIX_LENGTH));

        assertEquals(TEST_ADDRESS_PARCELED, mParcel.serverAddr);
        assertEquals(TEST_PREFIX_LENGTH, mParcel.serverAddrPrefixLength);
    }

    @Test
    public void testSetDefaultRouters() {
        mParcel.setDefaultRouters(TEST_ADDRESS_SET);
        assertEquals(TEST_ADDRESS_SET_PARCELED, asSet(mParcel.defaultRouters));
    }

    @Test
    public void testSetDnsServers() {
        mParcel.setDnsServers(TEST_ADDRESS_SET);
        assertEquals(TEST_ADDRESS_SET_PARCELED, asSet(mParcel.dnsServers));
    }

    @Test
    public void testSetExcludedAddrs() {
        mParcel.setExcludedAddrs(TEST_ADDRESS_SET);
        assertEquals(TEST_ADDRESS_SET_PARCELED, asSet(mParcel.excludedAddrs));
    }

    @Test
    public void testSetDhcpLeaseTimeSecs() {
        mParcel.setDhcpLeaseTimeSecs(TEST_LEASE_TIME_SECS);
        assertEquals(TEST_LEASE_TIME_SECS, mParcel.dhcpLeaseTimeSecs);
    }

    @Test
    public void testSetLinkMtu() {
        mParcel.setLinkMtu(TEST_MTU);
        assertEquals(TEST_MTU, mParcel.linkMtu);
    }

    @Test
    public void testSetMetered() {
        mParcel.setMetered(true);
        assertTrue(mParcel.metered);
        mParcel.setMetered(false);
        assertFalse(mParcel.metered);
    }

    @Test
    public void testSetClientAddr() {
        mParcel.setSingleClientAddr(TEST_CLIENT_ADDRESS);
        assertEquals(TEST_CLIENT_ADDRESS_PARCELED, mParcel.clientAddr);
    }

    private static Inet4Address inet4Addr(String addr) {
        return (Inet4Address) parseNumericAddress(addr);
    }

    private static Set<Integer> asSet(int[] ints) {
        return IntStream.of(ints).boxed().collect(Collectors.toSet());
    }
}
