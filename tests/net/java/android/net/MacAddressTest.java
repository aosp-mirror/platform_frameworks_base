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
        int expectedType;

        static AddrTypeTestCase of(int expectedType, int... addr) {
            AddrTypeTestCase t = new AddrTypeTestCase();
            t.expectedType = expectedType;
            t.addr = toByteArray(addr);
            return t;
        }
    }

    @Test
    public void testMacAddrTypes() {
        AddrTypeTestCase[] testcases = {
            AddrTypeTestCase.of(MacAddress.TYPE_UNKNOWN),
            AddrTypeTestCase.of(MacAddress.TYPE_UNKNOWN, 0),
            AddrTypeTestCase.of(MacAddress.TYPE_UNKNOWN, 1, 2, 3, 4, 5),
            AddrTypeTestCase.of(MacAddress.TYPE_UNKNOWN, 1, 2, 3, 4, 5, 6, 7),
            AddrTypeTestCase.of(MacAddress.TYPE_UNICAST, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0),
            AddrTypeTestCase.of(MacAddress.TYPE_BROADCAST, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
            AddrTypeTestCase.of(MacAddress.TYPE_MULTICAST, 1, 2, 3, 4, 5, 6),
            AddrTypeTestCase.of(MacAddress.TYPE_MULTICAST, 11, 22, 33, 44, 55, 66),
            AddrTypeTestCase.of(MacAddress.TYPE_MULTICAST, 33, 33, 0xaa, 0xbb, 0xcc, 0xdd)
        };

        for (AddrTypeTestCase t : testcases) {
            int got = MacAddress.macAddressType(t.addr);
            String msg = String.format("expected type of %s to be %s, but got %s",
                    Arrays.toString(t.addr), t.expectedType, got);
            assertEquals(msg, t.expectedType, got);

            if (got != MacAddress.TYPE_UNKNOWN) {
                assertEquals(got, MacAddress.fromBytes(t.addr).getAddressType());
            }
        }
    }

    @Test
    public void testToOuiString() {
        String[][] macs = {
            {"07:00:d3:56:8a:c4", "07:00:d3"},
            {"33:33:aa:bb:cc:dd", "33:33:aa"},
            {"06:00:00:00:00:00", "06:00:00"},
            {"07:00:d3:56:8a:c4", "07:00:d3"}
        };

        for (String[] pair : macs) {
            String mac = pair[0];
            String expected = pair[1];
            assertEquals(expected, MacAddress.fromString(mac).toOuiString());
        }
    }

    @Test
    public void testHexPaddingWhenPrinting() {
        String[] macs = {
            "07:00:d3:56:8a:c4",
            "33:33:aa:bb:cc:dd",
            "06:00:00:00:00:00",
            "07:00:d3:56:8a:c4"
        };

        for (String mac : macs) {
            assertEquals(mac, MacAddress.fromString(mac).toString());
            assertEquals(mac,
                    MacAddress.stringAddrFromByteAddr(MacAddress.byteAddrFromStringAddr(mac)));
        }
    }

    @Test
    public void testIsMulticastAddress() {
        MacAddress[] multicastAddresses = {
            MacAddress.BROADCAST_ADDRESS,
            MacAddress.fromString("07:00:d3:56:8a:c4"),
            MacAddress.fromString("33:33:aa:bb:cc:dd"),
        };
        MacAddress[] unicastAddresses = {
            MacAddress.ALL_ZEROS_ADDRESS,
            MacAddress.fromString("00:01:44:55:66:77"),
            MacAddress.fromString("08:00:22:33:44:55"),
            MacAddress.fromString("06:00:00:00:00:00"),
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
            MacAddress.fromString("06:00:00:00:00:00"),
            MacAddress.fromString("07:00:d3:56:8a:c4"),
            MacAddress.fromString("33:33:aa:bb:cc:dd"),
        };
        MacAddress[] universalAddresses = {
            MacAddress.fromString("00:01:44:55:66:77"),
            MacAddress.fromString("08:00:22:33:44:55"),
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
            MacAddress mac = MacAddress.createRandomUnicastAddress();

            String stringRepr = mac.toString();
            byte[] bytesRepr = mac.toByteArray();

            assertEquals(mac, MacAddress.fromString(stringRepr));
            assertEquals(mac, MacAddress.fromBytes(bytesRepr));

            assertEquals(mac, MacAddress.fromString(MacAddress.stringAddrFromByteAddr(bytesRepr)));
            assertEquals(mac, MacAddress.fromBytes(MacAddress.byteAddrFromStringAddr(stringRepr)));
        }
    }

    @Test
    public void testMacAddressRandomGeneration() {
        final int iterations = 1000;
        final String expectedAndroidOui = "da:a1:19";
        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.createRandomUnicastAddressWithGoogleBase();
            String stringRepr = mac.toString();

            assertTrue(stringRepr + " expected to be a locally assigned address",
                    mac.isLocallyAssigned());
            assertTrue(stringRepr + " expected to begin with " + expectedAndroidOui,
                    stringRepr.startsWith(expectedAndroidOui));
        }

        final Random r = new Random();
        final String anotherOui = "24:5f:78";
        final String expectedLocalOui = "26:5f:78";
        final MacAddress base = MacAddress.fromString(anotherOui + ":0:0:0");
        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.createRandomUnicastAddress(base, r);
            String stringRepr = mac.toString();

            assertTrue(stringRepr + " expected to be a locally assigned address",
                    mac.isLocallyAssigned());
            assertEquals(MacAddress.TYPE_UNICAST, mac.getAddressType());
            assertTrue(stringRepr + " expected to begin with " + expectedLocalOui,
                    stringRepr.startsWith(expectedLocalOui));
        }

        for (int i = 0; i < iterations; i++) {
            MacAddress mac = MacAddress.createRandomUnicastAddress();
            String stringRepr = mac.toString();

            assertTrue(stringRepr + " expected to be a locally assigned address",
                    mac.isLocallyAssigned());
            assertEquals(MacAddress.TYPE_UNICAST, mac.getAddressType());
        }
    }

    @Test
    public void testConstructorInputValidation() {
        String[] invalidStringAddresses = {
            "",
            "abcd",
            "1:2:3:4:5",
            "1:2:3:4:5:6:7",
            "10000:2:3:4:5:6",
        };

        for (String s : invalidStringAddresses) {
            try {
                MacAddress mac = MacAddress.fromString(s);
                fail("MacAddress.fromString(" + s + ") should have failed, but returned " + mac);
            } catch (IllegalArgumentException excepted) {
            }
        }

        try {
            MacAddress mac = MacAddress.fromString(null);
            fail("MacAddress.fromString(null) should have failed, but returned " + mac);
        } catch (NullPointerException excepted) {
        }

        byte[][] invalidBytesAddresses = {
            {},
            {1,2,3,4,5},
            {1,2,3,4,5,6,7},
        };

        for (byte[] b : invalidBytesAddresses) {
            try {
                MacAddress mac = MacAddress.fromBytes(b);
                fail("MacAddress.fromBytes(" + Arrays.toString(b)
                        + ") should have failed, but returned " + mac);
            } catch (IllegalArgumentException excepted) {
            }
        }

        try {
            MacAddress mac = MacAddress.fromBytes(null);
            fail("MacAddress.fromBytes(null) should have failed, but returned " + mac);
        } catch (NullPointerException excepted) {
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
