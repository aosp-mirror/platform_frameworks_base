/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.net.LinkAddress;
import android.os.Parcel;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for {@link LinkAddress}.
 */
public class LinkAddressTest extends AndroidTestCase {

    private static final String V4 = "192.0.2.1";
    private static final String V6 = "2001:db8::1";
    private static final InetAddress V4_ADDRESS = NetworkUtils.numericToInetAddress(V4);
    private static final InetAddress V6_ADDRESS = NetworkUtils.numericToInetAddress(V6);

    public void testConstructors() throws SocketException {
        LinkAddress address;

        // Valid addresses work as expected.
        address = new LinkAddress(V4_ADDRESS, 25);
        assertEquals(V4_ADDRESS, address.getAddress());
        assertEquals(25, address.getNetworkPrefixLength());

        address = new LinkAddress(V6_ADDRESS, 127);
        assertEquals(V6_ADDRESS, address.getAddress());
        assertEquals(127, address.getNetworkPrefixLength());

        address = new LinkAddress(V6 + "/64");
        assertEquals(V6_ADDRESS, address.getAddress());
        assertEquals(64, address.getNetworkPrefixLength());

        address = new LinkAddress(V4 + "/23");
        assertEquals(V4_ADDRESS, address.getAddress());
        assertEquals(23, address.getNetworkPrefixLength());

        // InterfaceAddress doesn't have a constructor. Fetch some from an interface.
        List<InterfaceAddress> addrs = NetworkInterface.getByName("lo").getInterfaceAddresses();

        // We expect to find 127.0.0.1/8 and ::1/128, in any order.
        LinkAddress ipv4Loopback, ipv6Loopback;
        assertEquals(2, addrs.size());
        if (addrs.get(0).getAddress() instanceof Inet4Address) {
            ipv4Loopback = new LinkAddress(addrs.get(0));
            ipv6Loopback = new LinkAddress(addrs.get(1));
        } else {
            ipv4Loopback = new LinkAddress(addrs.get(1));
            ipv6Loopback = new LinkAddress(addrs.get(0));
        }

        assertEquals(NetworkUtils.numericToInetAddress("127.0.0.1"), ipv4Loopback.getAddress());
        assertEquals(8, ipv4Loopback.getNetworkPrefixLength());

        assertEquals(NetworkUtils.numericToInetAddress("::1"), ipv6Loopback.getAddress());
        assertEquals(128, ipv6Loopback.getNetworkPrefixLength());

        // Null addresses are rejected.
        try {
            address = new LinkAddress(null, 24);
            fail("Null InetAddress should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress((String) null);
            fail("Null string should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress((InterfaceAddress) null);
            fail("Null string should cause NullPointerException");
        } catch(NullPointerException expected) {}

        // Invalid prefix lengths are rejected.
        try {
            address = new LinkAddress(V4 + "/-1");
            fail("Negative IPv4 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V6 + "/-1");
            fail("Negative IPv6 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V4 + "/33");
            fail("/35 IPv4 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V6 + "/129");
            fail("/129 IPv6 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}
    }

    private void assertLinkAddressesEqual(LinkAddress l1, LinkAddress l2) {
        assertTrue(l1 + " unexpectedly not equal to " + l2, l1.equals(l2));
        assertTrue(l2 + " unexpectedly not equal to " + l1, l2.equals(l1));
        assertEquals(l1.hashCode(), l2.hashCode());
    }

    private void assertLinkAddressesNotEqual(LinkAddress l1, LinkAddress l2) {
        assertFalse(l1 + " unexpectedly equal to " + l2, l1.equals(l2));
        assertFalse(l2 + " unexpectedly equal to " + l1, l2.equals(l1));
    }

    public void testEquals() {
        LinkAddress l1, l2;

        l1 = new LinkAddress("2001:db8::1/64");
        l2 = new LinkAddress("2001:db8::1/64");
        assertLinkAddressesEqual(l1, l2);

        l2 = new LinkAddress("2001:db8::1/65");
        assertLinkAddressesNotEqual(l1, l2);
        l2 = new LinkAddress("2001:db8::2/64");
        assertLinkAddressesNotEqual(l1, l2);

        l1 = new LinkAddress("192.0.2.1/24");
        l2 = new LinkAddress("192.0.2.1/24");
        assertLinkAddressesEqual(l1, l2);

        l2 = new LinkAddress("192.0.2.1/23");
        assertLinkAddressesNotEqual(l1, l2);
        l2 = new LinkAddress("192.0.2.2/24");
        assertLinkAddressesNotEqual(l1, l2);

        // Addresses with the same start or end bytes aren't equal between families.
        l1 = new LinkAddress("255.255.255.255/24");
        l2 = new LinkAddress("ffff:ffff::/24");
        assertLinkAddressesNotEqual(l1, l2);
        l2 = new LinkAddress("::ffff:ffff/24");
        assertLinkAddressesNotEqual(l1, l2);

        // Because we use InetAddress, an IPv4 address is equal to its IPv4-mapped address.
        // TODO: Investigate fixing this.
        String addressString = V4 + "/24";
        l1 = new LinkAddress(addressString);
        l2 = new LinkAddress("::ffff:" + addressString);
        assertLinkAddressesEqual(l1, l2);
    }

    public void testHashCode() {
        LinkAddress l;

        l = new LinkAddress(V4_ADDRESS, 23);
        assertEquals(-982787, l.hashCode());

        l = new LinkAddress(V4_ADDRESS, 27);
        assertEquals(-982743, l.hashCode());

        l = new LinkAddress(V6_ADDRESS, 64);
        assertEquals(1076522926, l.hashCode());

        l = new LinkAddress(V6_ADDRESS, 128);
        assertEquals(1076523630, l.hashCode());
    }

    private LinkAddress passThroughParcel(LinkAddress l) {
        Parcel p = Parcel.obtain();
        LinkAddress l2 = null;
        try {
            l.writeToParcel(p, 0);
            p.setDataPosition(0);
            l2 = LinkAddress.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
        assertNotNull(l2);
        return l2;
    }

    private void assertParcelingIsLossless(LinkAddress l) {
      LinkAddress l2 = passThroughParcel(l);
      assertEquals(l, l2);
    }

    public void testParceling() {
        LinkAddress l;

        l = new LinkAddress(V6_ADDRESS, 64);
        assertParcelingIsLossless(l);

        l = new LinkAddress(V4 + "/28");
        assertParcelingIsLossless(l);
    }
}
