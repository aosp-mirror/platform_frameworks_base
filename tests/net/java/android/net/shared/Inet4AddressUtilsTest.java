/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import static android.net.shared.Inet4AddressUtils.getBroadcastAddress;
import static android.net.shared.Inet4AddressUtils.getImplicitNetmask;
import static android.net.shared.Inet4AddressUtils.getPrefixMaskAsInet4Address;
import static android.net.shared.Inet4AddressUtils.inet4AddressToIntHTH;
import static android.net.shared.Inet4AddressUtils.inet4AddressToIntHTL;
import static android.net.shared.Inet4AddressUtils.intToInet4AddressHTH;
import static android.net.shared.Inet4AddressUtils.intToInet4AddressHTL;
import static android.net.shared.Inet4AddressUtils.netmaskToPrefixLength;
import static android.net.shared.Inet4AddressUtils.prefixLengthToV4NetmaskIntHTH;
import static android.net.shared.Inet4AddressUtils.prefixLengthToV4NetmaskIntHTL;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.fail;

import android.net.InetAddresses;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class Inet4AddressUtilsTest {

    @Test
    public void testInet4AddressToIntHTL() {
        assertEquals(0, inet4AddressToIntHTL(ipv4Address("0.0.0.0")));
        assertEquals(0x000080ff, inet4AddressToIntHTL(ipv4Address("255.128.0.0")));
        assertEquals(0x0080ff0a, inet4AddressToIntHTL(ipv4Address("10.255.128.0")));
        assertEquals(0x00feff0a, inet4AddressToIntHTL(ipv4Address("10.255.254.0")));
        assertEquals(0xfeffa8c0, inet4AddressToIntHTL(ipv4Address("192.168.255.254")));
        assertEquals(0xffffa8c0, inet4AddressToIntHTL(ipv4Address("192.168.255.255")));
    }

    @Test
    public void testIntToInet4AddressHTL() {
        assertEquals(ipv4Address("0.0.0.0"), intToInet4AddressHTL(0));
        assertEquals(ipv4Address("255.128.0.0"), intToInet4AddressHTL(0x000080ff));
        assertEquals(ipv4Address("10.255.128.0"), intToInet4AddressHTL(0x0080ff0a));
        assertEquals(ipv4Address("10.255.254.0"), intToInet4AddressHTL(0x00feff0a));
        assertEquals(ipv4Address("192.168.255.254"), intToInet4AddressHTL(0xfeffa8c0));
        assertEquals(ipv4Address("192.168.255.255"), intToInet4AddressHTL(0xffffa8c0));
    }

    @Test
    public void testInet4AddressToIntHTH() {
        assertEquals(0, inet4AddressToIntHTH(ipv4Address("0.0.0.0")));
        assertEquals(0xff800000, inet4AddressToIntHTH(ipv4Address("255.128.0.0")));
        assertEquals(0x0aff8000, inet4AddressToIntHTH(ipv4Address("10.255.128.0")));
        assertEquals(0x0afffe00, inet4AddressToIntHTH(ipv4Address("10.255.254.0")));
        assertEquals(0xc0a8fffe, inet4AddressToIntHTH(ipv4Address("192.168.255.254")));
        assertEquals(0xc0a8ffff, inet4AddressToIntHTH(ipv4Address("192.168.255.255")));
    }

    @Test
    public void testIntToInet4AddressHTH() {
        assertEquals(ipv4Address("0.0.0.0"), intToInet4AddressHTH(0));
        assertEquals(ipv4Address("255.128.0.0"), intToInet4AddressHTH(0xff800000));
        assertEquals(ipv4Address("10.255.128.0"), intToInet4AddressHTH(0x0aff8000));
        assertEquals(ipv4Address("10.255.254.0"), intToInet4AddressHTH(0x0afffe00));
        assertEquals(ipv4Address("192.168.255.254"), intToInet4AddressHTH(0xc0a8fffe));
        assertEquals(ipv4Address("192.168.255.255"), intToInet4AddressHTH(0xc0a8ffff));
    }


    @Test
    public void testPrefixLengthToV4NetmaskIntHTL() {
        assertEquals(0, prefixLengthToV4NetmaskIntHTL(0));
        assertEquals(0x000080ff /* 255.128.0.0 */, prefixLengthToV4NetmaskIntHTL(9));
        assertEquals(0x0080ffff /* 255.255.128.0 */, prefixLengthToV4NetmaskIntHTL(17));
        assertEquals(0x00feffff /* 255.255.254.0 */, prefixLengthToV4NetmaskIntHTL(23));
        assertEquals(0xfeffffff /* 255.255.255.254 */, prefixLengthToV4NetmaskIntHTL(31));
        assertEquals(0xffffffff /* 255.255.255.255 */, prefixLengthToV4NetmaskIntHTL(32));
    }

    @Test
    public void testPrefixLengthToV4NetmaskIntHTH() {
        assertEquals(0, prefixLengthToV4NetmaskIntHTH(0));
        assertEquals(0xff800000 /* 255.128.0.0 */, prefixLengthToV4NetmaskIntHTH(9));
        assertEquals(0xffff8000 /* 255.255.128.0 */, prefixLengthToV4NetmaskIntHTH(17));
        assertEquals(0xfffffe00 /* 255.255.254.0 */, prefixLengthToV4NetmaskIntHTH(23));
        assertEquals(0xfffffffe /* 255.255.255.254 */, prefixLengthToV4NetmaskIntHTH(31));
        assertEquals(0xffffffff /* 255.255.255.255 */, prefixLengthToV4NetmaskIntHTH(32));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrefixLengthToV4NetmaskIntHTH_NegativeLength() {
        prefixLengthToV4NetmaskIntHTH(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrefixLengthToV4NetmaskIntHTH_LengthTooLarge() {
        prefixLengthToV4NetmaskIntHTH(33);
    }

    private void checkAddressMasking(String expectedAddr, String addr, int prefixLength) {
        final int prefix = prefixLengthToV4NetmaskIntHTH(prefixLength);
        final int addrInt = inet4AddressToIntHTH(ipv4Address(addr));
        assertEquals(ipv4Address(expectedAddr), intToInet4AddressHTH(prefix & addrInt));
    }

    @Test
    public void testPrefixLengthToV4NetmaskIntHTH_MaskAddr() {
        checkAddressMasking("192.168.0.0", "192.168.128.1", 16);
        checkAddressMasking("255.240.0.0", "255.255.255.255", 12);
        checkAddressMasking("255.255.255.255", "255.255.255.255", 32);
        checkAddressMasking("0.0.0.0", "255.255.255.255", 0);
    }

    @Test
    public void testGetImplicitNetmask() {
        assertEquals(8, getImplicitNetmask(ipv4Address("4.2.2.2")));
        assertEquals(8, getImplicitNetmask(ipv4Address("10.5.6.7")));
        assertEquals(16, getImplicitNetmask(ipv4Address("173.194.72.105")));
        assertEquals(16, getImplicitNetmask(ipv4Address("172.23.68.145")));
        assertEquals(24, getImplicitNetmask(ipv4Address("192.0.2.1")));
        assertEquals(24, getImplicitNetmask(ipv4Address("192.168.5.1")));
        assertEquals(32, getImplicitNetmask(ipv4Address("224.0.0.1")));
        assertEquals(32, getImplicitNetmask(ipv4Address("255.6.7.8")));
    }

    private void assertInvalidNetworkMask(Inet4Address addr) {
        try {
            netmaskToPrefixLength(addr);
            fail("Invalid netmask " + addr.getHostAddress() + " did not cause exception");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testNetmaskToPrefixLength() {
        assertEquals(0, netmaskToPrefixLength(ipv4Address("0.0.0.0")));
        assertEquals(9, netmaskToPrefixLength(ipv4Address("255.128.0.0")));
        assertEquals(17, netmaskToPrefixLength(ipv4Address("255.255.128.0")));
        assertEquals(23, netmaskToPrefixLength(ipv4Address("255.255.254.0")));
        assertEquals(31, netmaskToPrefixLength(ipv4Address("255.255.255.254")));
        assertEquals(32, netmaskToPrefixLength(ipv4Address("255.255.255.255")));

        assertInvalidNetworkMask(ipv4Address("0.0.0.1"));
        assertInvalidNetworkMask(ipv4Address("255.255.255.253"));
        assertInvalidNetworkMask(ipv4Address("255.255.0.255"));
    }

    @Test
    public void testGetPrefixMaskAsAddress() {
        assertEquals("255.255.240.0", getPrefixMaskAsInet4Address(20).getHostAddress());
        assertEquals("255.0.0.0", getPrefixMaskAsInet4Address(8).getHostAddress());
        assertEquals("0.0.0.0", getPrefixMaskAsInet4Address(0).getHostAddress());
        assertEquals("255.255.255.255", getPrefixMaskAsInet4Address(32).getHostAddress());
    }

    @Test
    public void testGetBroadcastAddress() {
        assertEquals("192.168.15.255",
                getBroadcastAddress(ipv4Address("192.168.0.123"), 20).getHostAddress());
        assertEquals("192.255.255.255",
                getBroadcastAddress(ipv4Address("192.168.0.123"), 8).getHostAddress());
        assertEquals("192.168.0.123",
                getBroadcastAddress(ipv4Address("192.168.0.123"), 32).getHostAddress());
        assertEquals("255.255.255.255",
                getBroadcastAddress(ipv4Address("192.168.0.123"), 0).getHostAddress());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetBroadcastAddress_PrefixTooLarge() {
        getBroadcastAddress(ipv4Address("192.168.0.123"), 33);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetBroadcastAddress_NegativePrefix() {
        getBroadcastAddress(ipv4Address("192.168.0.123"), -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetPrefixMaskAsAddress_PrefixTooLarge() {
        getPrefixMaskAsInet4Address(33);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetPrefixMaskAsAddress_NegativePrefix() {
        getPrefixMaskAsInet4Address(-1);
    }

    private Inet4Address ipv4Address(String addr) {
        return (Inet4Address) InetAddresses.parseNumericAddress(addr);
    }
}
