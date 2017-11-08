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

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * @hide
 */
public final class MacAddress {

    // TODO: add isLocallyAssigned().
    // TODO: add getRandomAddress() factory method.

    private static final int ETHER_ADDR_LEN = 6;
    private static final byte FF = (byte) 0xff;
    @VisibleForTesting
    static final byte[] ETHER_ADDR_BROADCAST = { FF, FF, FF, FF, FF, FF };

    public enum MacAddressType {
        UNICAST,
        MULTICAST,
        BROADCAST;
    }

    /** Return true if the given byte array is not null and has the length of a mac address. */
    public static boolean isMacAddress(byte[] addr) {
        return addr != null && addr.length == ETHER_ADDR_LEN;
    }

    /**
     * Return the MacAddressType of the mac address represented by the given byte array,
     * or null if the given byte array does not represent an mac address. */
    public static MacAddressType macAddressType(byte[] addr) {
        if (!isMacAddress(addr)) {
            return null;
        }
        if (Arrays.equals(addr, ETHER_ADDR_BROADCAST)) {
            return MacAddressType.BROADCAST;
        }
        if ((addr[0] & 0x01) == 1) {
            return MacAddressType.MULTICAST;
        }
        return MacAddressType.UNICAST;
    }
}
