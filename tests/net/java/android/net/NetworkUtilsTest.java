/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.net.NetworkUtils;
import android.test.suitebuilder.annotation.SmallTest;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.TreeSet;

import junit.framework.TestCase;

public class NetworkUtilsTest extends TestCase {

    private InetAddress Address(String addr) {
        return InetAddress.parseNumericAddress(addr);
    }

    private Inet4Address IPv4Address(String addr) {
        return (Inet4Address) Address(addr);
    }

    @SmallTest
    public void testGetImplicitNetmask() {
        assertEquals(8, NetworkUtils.getImplicitNetmask(IPv4Address("4.2.2.2")));
        assertEquals(8, NetworkUtils.getImplicitNetmask(IPv4Address("10.5.6.7")));
        assertEquals(16, NetworkUtils.getImplicitNetmask(IPv4Address("173.194.72.105")));
        assertEquals(16, NetworkUtils.getImplicitNetmask(IPv4Address("172.23.68.145")));
        assertEquals(24, NetworkUtils.getImplicitNetmask(IPv4Address("192.0.2.1")));
        assertEquals(24, NetworkUtils.getImplicitNetmask(IPv4Address("192.168.5.1")));
        assertEquals(32, NetworkUtils.getImplicitNetmask(IPv4Address("224.0.0.1")));
        assertEquals(32, NetworkUtils.getImplicitNetmask(IPv4Address("255.6.7.8")));
    }

    private void assertInvalidNetworkMask(Inet4Address addr) {
        try {
            NetworkUtils.netmaskToPrefixLength(addr);
            fail("Invalid netmask " + addr.getHostAddress() + " did not cause exception");
        } catch (IllegalArgumentException expected) {
        }
    }

    @SmallTest
    public void testNetmaskToPrefixLength() {
        assertEquals(0, NetworkUtils.netmaskToPrefixLength(IPv4Address("0.0.0.0")));
        assertEquals(9, NetworkUtils.netmaskToPrefixLength(IPv4Address("255.128.0.0")));
        assertEquals(17, NetworkUtils.netmaskToPrefixLength(IPv4Address("255.255.128.0")));
        assertEquals(23, NetworkUtils.netmaskToPrefixLength(IPv4Address("255.255.254.0")));
        assertEquals(31, NetworkUtils.netmaskToPrefixLength(IPv4Address("255.255.255.254")));
        assertEquals(32, NetworkUtils.netmaskToPrefixLength(IPv4Address("255.255.255.255")));

        assertInvalidNetworkMask(IPv4Address("0.0.0.1"));
        assertInvalidNetworkMask(IPv4Address("255.255.255.253"));
        assertInvalidNetworkMask(IPv4Address("255.255.0.255"));
    }

    @SmallTest
    public void testRoutedIPv4AddressCount() {
        final TreeSet<IpPrefix> set = new TreeSet<>(IpPrefix.lengthComparator());
        // No routes routes to no addresses.
        assertEquals(0, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("0.0.0.0/0"));
        assertEquals(1l << 32, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("20.18.0.0/16"));
        set.add(new IpPrefix("20.18.0.0/24"));
        set.add(new IpPrefix("20.18.0.0/8"));
        // There is a default route, still covers everything
        assertEquals(1l << 32, NetworkUtils.routedIPv4AddressCount(set));

        set.clear();
        set.add(new IpPrefix("20.18.0.0/24"));
        set.add(new IpPrefix("20.18.0.0/8"));
        // The 8-length includes the 24-length prefix
        assertEquals(1l << 24, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("10.10.10.126/25"));
        // The 8-length does not include this 25-length prefix
        assertEquals((1l << 24) + (1 << 7), NetworkUtils.routedIPv4AddressCount(set));

        set.clear();
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        set.add(new IpPrefix("1.2.3.4/32"));
        assertEquals(1l, NetworkUtils.routedIPv4AddressCount(set));

        set.add(new IpPrefix("1.2.3.5/32"));
        set.add(new IpPrefix("1.2.3.6/32"));

        set.add(new IpPrefix("1.2.3.7/32"));
        set.add(new IpPrefix("1.2.3.8/32"));
        set.add(new IpPrefix("1.2.3.9/32"));
        set.add(new IpPrefix("1.2.3.0/32"));
        assertEquals(7l, NetworkUtils.routedIPv4AddressCount(set));

        // 1.2.3.4/30 eats 1.2.3.{4-7}/32
        set.add(new IpPrefix("1.2.3.4/30"));
        set.add(new IpPrefix("6.2.3.4/28"));
        set.add(new IpPrefix("120.2.3.4/16"));
        assertEquals(7l - 4 + 4 + 16 + 65536, NetworkUtils.routedIPv4AddressCount(set));
    }

    @SmallTest
    public void testRoutedIPv6AddressCount() {
        final TreeSet<IpPrefix> set = new TreeSet<>(IpPrefix.lengthComparator());
        // No routes routes to no addresses.
        assertEquals(BigInteger.ZERO, NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("::/0"));
        assertEquals(BigInteger.ONE.shiftLeft(128), NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("1234:622a::18/64"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/96"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/8"));
        // There is a default route, still covers everything
        assertEquals(BigInteger.ONE.shiftLeft(128), NetworkUtils.routedIPv6AddressCount(set));

        set.clear();
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/96"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6adb/8"));
        // The 8-length includes the 96-length prefix
        assertEquals(BigInteger.ONE.shiftLeft(120), NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("10::26/64"));
        // The 8-length does not include this 64-length prefix
        assertEquals(BigInteger.ONE.shiftLeft(120).add(BigInteger.ONE.shiftLeft(64)),
                NetworkUtils.routedIPv6AddressCount(set));

        set.clear();
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/128"));
        assertEquals(BigInteger.ONE, NetworkUtils.routedIPv6AddressCount(set));

        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad5/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad6/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad7/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad8/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad9/128"));
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad0/128"));
        assertEquals(BigInteger.valueOf(7), NetworkUtils.routedIPv6AddressCount(set));

        // add4:f00:80:f7:1111::6ad4/126 eats add4:f00:8[:f7:1111::6ad{4-7}/128
        set.add(new IpPrefix("add4:f00:80:f7:1111::6ad4/126"));
        set.add(new IpPrefix("d00d:f00:80:f7:1111::6ade/124"));
        set.add(new IpPrefix("f00b:a33::/112"));
        assertEquals(BigInteger.valueOf(7l - 4 + 4 + 16 + 65536),
                NetworkUtils.routedIPv6AddressCount(set));
    }
}
