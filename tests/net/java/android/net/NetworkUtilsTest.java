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

import java.net.Inet4Address;
import java.net.InetAddress;

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
}
