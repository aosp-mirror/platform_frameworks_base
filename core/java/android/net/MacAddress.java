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

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.BitUtils;

import java.util.Arrays;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Represents a mac address.
 *
 * @hide
 */
public final class MacAddress implements Parcelable {

    private static final int ETHER_ADDR_LEN = 6;
    private static final byte[] ETHER_ADDR_BROADCAST = addr(0xff, 0xff, 0xff, 0xff, 0xff, 0xff);

    /** The broadcast mac address.  */
    public static final MacAddress BROADCAST_ADDRESS = new MacAddress(ETHER_ADDR_BROADCAST);

    /** The zero mac address. */
    public static final MacAddress ALL_ZEROS_ADDRESS = new MacAddress(0);

    /** Represents categories of mac addresses. */
    public enum MacAddressType {
        UNICAST,
        MULTICAST,
        BROADCAST;
    }

    private static final long VALID_LONG_MASK = BROADCAST_ADDRESS.mAddr;
    private static final long LOCALLY_ASSIGNED_MASK = new MacAddress("2:0:0:0:0:0").mAddr;
    private static final long MULTICAST_MASK = new MacAddress("1:0:0:0:0:0").mAddr;
    private static final long OUI_MASK = new MacAddress("ff:ff:ff:0:0:0").mAddr;
    private static final long NIC_MASK = new MacAddress("0:0:0:ff:ff:ff").mAddr;
    private static final MacAddress BASE_ANDROID_MAC = new MacAddress("da:a1:19:0:0:0");

    // Internal representation of the mac address as a single 8 byte long.
    // The encoding scheme sets the two most significant bytes to 0. The 6 bytes of the
    // mac address are encoded in the 6 least significant bytes of the long, where the first
    // byte of the array is mapped to the 3rd highest logical byte of the long, the second
    // byte of the array is mapped to the 4th highest logical byte of the long, and so on.
    private final long mAddr;

    private MacAddress(long addr) {
        mAddr = addr;
    }

    /** Creates a MacAddress for the given byte representation. */
    public MacAddress(byte[] addr) {
        this(longAddrFromByteAddr(addr));
    }

    /** Creates a MacAddress for the given string representation. */
    public MacAddress(String addr) {
        this(longAddrFromByteAddr(byteAddrFromStringAddr(addr)));
    }

    /** Returns the MacAddressType of this MacAddress. */
    public MacAddressType addressType() {
        if (equals(BROADCAST_ADDRESS)) {
            return MacAddressType.BROADCAST;
        }
        if (isMulticastAddress()) {
            return MacAddressType.MULTICAST;
        }
        return MacAddressType.UNICAST;
    }

    /** Returns true if this MacAddress corresponds to a multicast address. */
    public boolean isMulticastAddress() {
        return (mAddr & MULTICAST_MASK) != 0;
    }

    /** Returns true if this MacAddress corresponds to a locally assigned address. */
    public boolean isLocallyAssigned() {
        return (mAddr & LOCALLY_ASSIGNED_MASK) != 0;
    }

    /** Returns a byte array representation of this MacAddress. */
    public byte[] toByteArray() {
        return byteAddrFromLongAddr(mAddr);
    }

    @Override
    public String toString() {
        return stringAddrFromByteAddr(byteAddrFromLongAddr(mAddr));
    }

    @Override
    public int hashCode() {
        return (int) ((mAddr >> 32) ^ mAddr);
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof MacAddress) && ((MacAddress) o).mAddr == mAddr;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mAddr);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<MacAddress> CREATOR =
            new Parcelable.Creator<MacAddress>() {
                public MacAddress createFromParcel(Parcel in) {
                    return new MacAddress(in.readLong());
                }

                public MacAddress[] newArray(int size) {
                    return new MacAddress[size];
                }
            };

    /** Return true if the given byte array is not null and has the length of a mac address. */
    public static boolean isMacAddress(byte[] addr) {
        return addr != null && addr.length == ETHER_ADDR_LEN;
    }

    /**
     * Return the MacAddressType of the mac address represented by the given byte array,
     * or null if the given byte array does not represent an mac address.
     */
    public static MacAddressType macAddressType(byte[] addr) {
        if (!isMacAddress(addr)) {
            return null;
        }
        return new MacAddress(addr).addressType();
    }

    /** DOCME */
    public static byte[] byteAddrFromStringAddr(String addr) {
        if (addr == null) {
            throw new IllegalArgumentException("cannot convert the null String");
        }
        String[] parts = addr.split(":");
        if (parts.length != ETHER_ADDR_LEN) {
            throw new IllegalArgumentException(addr + " was not a valid MAC address");
        }
        byte[] bytes = new byte[ETHER_ADDR_LEN];
        for (int i = 0; i < ETHER_ADDR_LEN; i++) {
            int x = Integer.valueOf(parts[i], 16);
            if (x < 0 || 0xff < x) {
                throw new IllegalArgumentException(addr + "was not a valid MAC address");
            }
            bytes[i] = (byte) x;
        }
        return bytes;
    }

    /** DOCME */
    public static String stringAddrFromByteAddr(byte[] addr) {
        if (!isMacAddress(addr)) {
            return null;
        }
        StringJoiner j = new StringJoiner(":");
        for (byte b : addr) {
            j.add(Integer.toHexString(BitUtils.uint8(b)));
        }
        return j.toString();
    }

    /** @hide */
    public static byte[] byteAddrFromLongAddr(long addr) {
        byte[] bytes = new byte[ETHER_ADDR_LEN];
        int index = ETHER_ADDR_LEN;
        while (index-- > 0) {
            bytes[index] = (byte) addr;
            addr = addr >> 8;
        }
        return bytes;
    }

    /** @hide */
    public static long longAddrFromByteAddr(byte[] addr) {
        if (!isMacAddress(addr)) {
            throw new IllegalArgumentException(
                    Arrays.toString(addr) + " was not a valid MAC address");
        }
        long longAddr = 0;
        for (byte b : addr) {
            longAddr = (longAddr << 8) + BitUtils.uint8(b);
        }
        return longAddr;
    }

    /** @hide */
    public static long longAddrFromStringAddr(String addr) {
        if (addr == null) {
            throw new IllegalArgumentException("cannot convert the null String");
        }
        String[] parts = addr.split(":");
        if (parts.length != ETHER_ADDR_LEN) {
            throw new IllegalArgumentException(addr + " was not a valid MAC address");
        }
        long longAddr = 0;
        int index = ETHER_ADDR_LEN;
        while (index-- > 0) {
            int x = Integer.valueOf(parts[index], 16);
            if (x < 0 || 0xff < x) {
                throw new IllegalArgumentException(addr + "was not a valid MAC address");
            }
            longAddr = x + (longAddr << 8);
        }
        return longAddr;
    }

    /** @hide */
    public static String stringAddrFromLongAddr(long addr) {
        addr = Long.reverseBytes(addr) >> 16;
        StringJoiner j = new StringJoiner(":");
        for (int i = 0; i < ETHER_ADDR_LEN; i++) {
            j.add(Integer.toHexString((byte) addr));
            addr = addr >> 8;
        }
        return j.toString();
    }

    /**
     * Returns a randomely generated mac address with the Android OUI value "DA-A1-19".
     * The locally assigned bit is always set to 1.
     */
    public static MacAddress getRandomAddress() {
        return getRandomAddress(BASE_ANDROID_MAC, new Random());
    }

    /**
     * Returns a randomely generated mac address using the given Random object and the same
     * OUI values as the given MacAddress. The locally assigned bit is always set to 1.
     */
    public static MacAddress getRandomAddress(MacAddress base, Random r) {
        long longAddr = (base.mAddr & OUI_MASK) | (NIC_MASK & r.nextLong()) | LOCALLY_ASSIGNED_MASK;
        return new MacAddress(longAddr);
    }

    // Convenience function for working around the lack of byte literals.
    private static byte[] addr(int... in) {
        if (in.length != ETHER_ADDR_LEN) {
            throw new IllegalArgumentException(Arrays.toString(in)
                    + " was not an array with length equal to " + ETHER_ADDR_LEN);
        }
        byte[] out = new byte[ETHER_ADDR_LEN];
        for (int i = 0; i < ETHER_ADDR_LEN; i++) {
            out[i] = (byte) in[i];
        }
        return out;
    }
}
