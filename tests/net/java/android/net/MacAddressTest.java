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

import android.net.MacAddress.MacAddressType;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.Arrays;

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
        }
    }

    static byte[] toByteArray(int[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) in[i];
        }
        return out;
    }
}
