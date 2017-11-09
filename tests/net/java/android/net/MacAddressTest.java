/*
 * Copyright 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.net.MacAddress.MacAddressType;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MacAddressTest {

    static class AddrTypeTestCase {
        byte[] addr;
        MacAddressType expected;

        static AddrTypeTestCase of(MacAddressType expected, int... addr) {
            AddrTypeTestCase t = new AddrTypeTestCase();
            t.expected = expected;
            t.addr = toByteArray(addr);
            return t;
        }
    }

    @Test
    public void testMacAddrTypes() {
        AddrTypeTestCase[] testcases = {
            AddrTypeTestCase.of(null),
            AddrTypeTestCase.of(null, 0),
            AddrTypeTestCase.of(null, 1, 2, 3, 4, 5),
            AddrTypeTestCase.of(null, 1, 2, 3, 4, 5, 6, 7),
            AddrTypeTestCase.of(MacAddressType.UNICAST, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0),
            AddrTypeTestCase.of(MacAddressType.BROADCAST, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            AddrTypeTestCase.of(MacAddressType.MULTICAST, 1, 2, 3, 4, 5, 6),
            AddrTypeTestCase.of(MacAddressType.MULTICAST, 11, 22, 33, 44, 55, 66),
            AddrTypeTestCase.of(MacAddressType.MULTICAST, 33, 33, 0xaa, 0xbb, 0xcc, 0xdd)
        };

        for (AddrTypeTestCase t : testcases) {
            MacAddressType got = MacAddress.macAddressType(t.addr);
            String msg = String.format("expected type of %s to be %s, but got %s",
                    Arrays.toString(t.addr), t.expected, got);
            assertEquals(msg, t.expected, got);

            if (got != null) {
                assertEquals(got, new MacAddress(t.addr).addressType());
            }
        }
    }

    @Test
    public void testIsMulticastAddress() {
        MacAddress[] multicastAddresses = {
            MacAddress.BROADCAST_ADDRESS,
            new MacAddress("07:00:d3:56:8a:c4"),
            new MacAddress("33:33:aa:bb:cc:dd"),
        };
        MacAddress[] unicastAddresses = {
            MacAddress.ALL_ZEROS_ADDRESS,
            new MacAddress("00:01:44:55:66:77"),
            new MacAddress("08:00:22:33:44:55"),
            new MacAddress("06:00:00:00:00:00"),
        };

        for (MacAddress mac : multicastAddresses) {
            String msg = mac.toString() + " expected to be a multicast address";
            assertTrue(msg, mac.isMulticastAddress());
        }
        for (MacAddress mac : unicastAddresses) {
            String msg = mac.toString() + " expected not to be a multicast address";
            assertFalse(msg, mac.isMulticastAddress());
        }
    }

    @Test
    public void testIsLocallyAssignedAddress() {
        MacAddress[] localAddresses = {
            new MacAddress("06:00:00:00:00:00"),
            new MacAddress("07:00:d3:56:8a:c4"),
            new MacAddress("33:33:aa:bb:cc:dd"),
        };
        MacAddress[] universalAddresses = {
            new MacAddress("00:01:44:55:66:77"),
            new MacAddress("08:00:22:33:44:55"),
        };

        for (MacAddress mac : localAddresses) {
            String msg = mac.toString() + " expected to be a locally assigned address";
            assertTrue(msg, mac.isLocallyAssigned());
        }
        for (MacAddress mac : universalAddresses) {
            String msg = mac.toString() + " expected not to be globally unique address";
            assertFalse(msg, mac.isLocallyAssigned());
        }
    }

    @Test
    public void testMacAddressConversions() {
        final int iterations = 10000;
        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.getRandomAddress();

            String stringRepr = mac.toString();
            byte[] bytesRepr = mac.toByteArray();

            assertEquals(mac, new MacAddress(stringRepr));
            assertEquals(mac, new MacAddress(bytesRepr));
        }
    }

    @Test
    public void testMacAddressRandomGeneration() {
        final int iterations = 1000;
        final String expectedAndroidOui = "da:a1:19";
        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.getRandomAddress();
            String stringRepr = mac.toString();

            assertTrue(stringRepr + " expected to be a locally assigned address",
                    mac.isLocallyAssigned());
            assertTrue(stringRepr + " expected to begin with " + expectedAndroidOui,
                    stringRepr.startsWith(expectedAndroidOui));
        }

        final Random r = new Random();
        final String anotherOui = "24:5f:78";
        final String expectedLocalOui = "26:5f:78";
        final MacAddress base = new MacAddress(anotherOui + ":0:0:0");
        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.getRandomAddress(base, r);
            String stringRepr = mac.toString();

            assertTrue(stringRepr + " expected to be a locally assigned address",
                    mac.isLocallyAssigned());
            assertTrue(stringRepr + " expected to begin with " + expectedLocalOui,
                    stringRepr.startsWith(expectedLocalOui));
        }
    }

    @Test
    public void testConstructorInputValidation() {
        String[] invalidStringAddresses = {
            null,
            "",
            "abcd",
            "1:2:3:4:5",
            "1:2:3:4:5:6:7",
            "10000:2:3:4:5:6",
        };

        for (String s : invalidStringAddresses) {
            try {
                MacAddress mac = new MacAddress(s);
                fail("new MacAddress(" + s + ") should have failed, but returned " + mac);
            } catch (IllegalArgumentException excepted) {
            }
        }

        byte[][] invalidBytesAddresses = {
            null,
            {},
            {1,2,3,4,5},
            {1,2,3,4,5,6,7},
        };

        for (byte[] b : invalidBytesAddresses) {
            try {
                MacAddress mac = new MacAddress(b);
                fail("new MacAddress(" + Arrays.toString(b)
                        + ") should have failed, but returned " + mac);
            } catch (IllegalArgumentException excepted) {
            }
        }
    }

    static byte[] toByteArray(int... in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) in[i];
        }
        return out;
    }
}
