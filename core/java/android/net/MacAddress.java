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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.BitUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

/**
 * Representation of a MAC address.
 *
 * This class only supports 48 bits long addresses and does not support 64 bits long addresses.
 * Instances of this class are immutable.
 */
public final class MacAddress implements Parcelable {

    private static final int ETHER_ADDR_LEN = 6;
    private static final byte[] ETHER_ADDR_BROADCAST = addr(0xff, 0xff, 0xff, 0xff, 0xff, 0xff);

    /**
     * The MacAddress representing the unique broadcast MAC address.
     */
    public static final MacAddress BROADCAST_ADDRESS = MacAddress.fromBytes(ETHER_ADDR_BROADCAST);

    /**
     * The MacAddress zero MAC address.
     * @hide
     */
    @UnsupportedAppUsage
    public static final MacAddress ALL_ZEROS_ADDRESS = new MacAddress(0);

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_UNICAST,
            TYPE_MULTICAST,
            TYPE_BROADCAST,
    })
    public @interface MacAddressType { }

    /** @hide Indicates a MAC address of unknown type. */
    public static final int TYPE_UNKNOWN = 0;
    /** Indicates a MAC address is a unicast address. */
    public static final int TYPE_UNICAST = 1;
    /** Indicates a MAC address is a multicast address. */
    public static final int TYPE_MULTICAST = 2;
    /** Indicates a MAC address is the broadcast address. */
    public static final int TYPE_BROADCAST = 3;

    private static final long VALID_LONG_MASK = (1L << 48) - 1;
    private static final long LOCALLY_ASSIGNED_MASK = MacAddress.fromString("2:0:0:0:0:0").mAddr;
    private static final long MULTICAST_MASK = MacAddress.fromString("1:0:0:0:0:0").mAddr;
    private static final long OUI_MASK = MacAddress.fromString("ff:ff:ff:0:0:0").mAddr;
    private static final long NIC_MASK = MacAddress.fromString("0:0:0:ff:ff:ff").mAddr;
    private static final MacAddress BASE_GOOGLE_MAC = MacAddress.fromString("da:a1:19:0:0:0");

    // Internal representation of the MAC address as a single 8 byte long.
    // The encoding scheme sets the two most significant bytes to 0. The 6 bytes of the
    // MAC address are encoded in the 6 least significant bytes of the long, where the first
    // byte of the array is mapped to the 3rd highest logical byte of the long, the second
    // byte of the array is mapped to the 4th highest logical byte of the long, and so on.
    private final long mAddr;

    private MacAddress(long addr) {
        mAddr = (VALID_LONG_MASK & addr);
    }

    /**
     * Returns the type of this address.
     *
     * @return the int constant representing the MAC address type of this MacAddress.
     */
    public @MacAddressType int getAddressType() {
        if (equals(BROADCAST_ADDRESS)) {
            return TYPE_BROADCAST;
        }
        if (isMulticastAddress()) {
            return TYPE_MULTICAST;
        }
        return TYPE_UNICAST;
    }

    /**
     * @return true if this MacAddress is a multicast address.
     * @hide
     */
    public boolean isMulticastAddress() {
        return (mAddr & MULTICAST_MASK) != 0;
    }

    /**
     * @return true if this MacAddress is a locally assigned address.
     */
    public boolean isLocallyAssigned() {
        return (mAddr & LOCALLY_ASSIGNED_MASK) != 0;
    }

    /**
     * @return a byte array representation of this MacAddress.
     */
    public @NonNull byte[] toByteArray() {
        return byteAddrFromLongAddr(mAddr);
    }

    @Override
    public @NonNull String toString() {
        return stringAddrFromLongAddr(mAddr);
    }

    /**
     * @return a String representation of the OUI part of this MacAddress made of 3 hexadecimal
     * numbers in [0,ff] joined by ':' characters.
     */
    public @NonNull String toOuiString() {
        return String.format(
                "%02x:%02x:%02x", (mAddr >> 40) & 0xff, (mAddr >> 32) & 0xff, (mAddr >> 24) & 0xff);
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

    /**
     * Returns true if the given byte array is an valid MAC address.
     * A valid byte array representation for a MacAddress is a non-null array of length 6.
     *
     * @param addr a byte array.
     * @return true if the given byte array is not null and has the length of a MAC address.
     *
     * @hide
     */
    public static boolean isMacAddress(byte[] addr) {
        return addr != null && addr.length == ETHER_ADDR_LEN;
    }

    /**
     * Returns the MAC address type of the MAC address represented by the given byte array,
     * or null if the given byte array does not represent a MAC address.
     * A valid byte array representation for a MacAddress is a non-null array of length 6.
     *
     * @param addr a byte array representing a MAC address.
     * @return the int constant representing the MAC address type of the MAC address represented
     * by the given byte array, or type UNKNOWN if the byte array is not a valid MAC address.
     *
     * @hide
     */
    public static int macAddressType(byte[] addr) {
        if (!isMacAddress(addr)) {
            return TYPE_UNKNOWN;
        }
        return MacAddress.fromBytes(addr).getAddressType();
    }

    /**
     * Converts a String representation of a MAC address to a byte array representation.
     * A valid String representation for a MacAddress is a series of 6 values in the
     * range [0,ff] printed in hexadecimal and joined by ':' characters.
     *
     * @param addr a String representation of a MAC address.
     * @return the byte representation of the MAC address.
     * @throws IllegalArgumentException if the given String is not a valid representation.
     *
     * @hide
     */
    public static @NonNull byte[] byteAddrFromStringAddr(String addr) {
        Preconditions.checkNotNull(addr);
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

    /**
     * Converts a byte array representation of a MAC address to a String representation made
     * of 6 hexadecimal numbers in [0,ff] joined by ':' characters.
     * A valid byte array representation for a MacAddress is a non-null array of length 6.
     *
     * @param addr a byte array representation of a MAC address.
     * @return the String representation of the MAC address.
     * @throws IllegalArgumentException if the given byte array is not a valid representation.
     *
     * @hide
     */
    public static @NonNull String stringAddrFromByteAddr(byte[] addr) {
        if (!isMacAddress(addr)) {
            return null;
        }
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                addr[0], addr[1], addr[2], addr[3], addr[4], addr[5]);
    }

    private static byte[] byteAddrFromLongAddr(long addr) {
        byte[] bytes = new byte[ETHER_ADDR_LEN];
        int index = ETHER_ADDR_LEN;
        while (index-- > 0) {
            bytes[index] = (byte) addr;
            addr = addr >> 8;
        }
        return bytes;
    }

    private static long longAddrFromByteAddr(byte[] addr) {
        Preconditions.checkNotNull(addr);
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

    // Internal conversion function equivalent to longAddrFromByteAddr(byteAddrFromStringAddr(addr))
    // that avoids the allocation of an intermediary byte[].
    private static long longAddrFromStringAddr(String addr) {
        Preconditions.checkNotNull(addr);
        String[] parts = addr.split(":");
        if (parts.length != ETHER_ADDR_LEN) {
            throw new IllegalArgumentException(addr + " was not a valid MAC address");
        }
        long longAddr = 0;
        for (int i = 0; i < parts.length; i++) {
            int x = Integer.valueOf(parts[i], 16);
            if (x < 0 || 0xff < x) {
                throw new IllegalArgumentException(addr + "was not a valid MAC address");
            }
            longAddr = x + (longAddr << 8);
        }
        return longAddr;
    }

    // Internal conversion function equivalent to stringAddrFromByteAddr(byteAddrFromLongAddr(addr))
    // that avoids the allocation of an intermediary byte[].
    private static @NonNull String stringAddrFromLongAddr(long addr) {
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                (addr >> 40) & 0xff,
                (addr >> 32) & 0xff,
                (addr >> 24) & 0xff,
                (addr >> 16) & 0xff,
                (addr >> 8) & 0xff,
                addr & 0xff);
    }

    /**
     * Creates a MacAddress from the given String representation. A valid String representation
     * for a MacAddress is a series of 6 values in the range [0,ff] printed in hexadecimal
     * and joined by ':' characters.
     *
     * @param addr a String representation of a MAC address.
     * @return the MacAddress corresponding to the given String representation.
     * @throws IllegalArgumentException if the given String is not a valid representation.
     */
    public static @NonNull MacAddress fromString(@NonNull String addr) {
        return new MacAddress(longAddrFromStringAddr(addr));
    }

    /**
     * Creates a MacAddress from the given byte array representation.
     * A valid byte array representation for a MacAddress is a non-null array of length 6.
     *
     * @param addr a byte array representation of a MAC address.
     * @return the MacAddress corresponding to the given byte array representation.
     * @throws IllegalArgumentException if the given byte array is not a valid representation.
     */
    public static @NonNull MacAddress fromBytes(@NonNull byte[] addr) {
        return new MacAddress(longAddrFromByteAddr(addr));
    }

    /**
     * Returns a generated MAC address whose 24 least significant bits constituting the
     * NIC part of the address are randomly selected and has Google OUI base.
     *
     * The locally assigned bit is always set to 1. The multicast bit is always set to 0.
     *
     * @return a random locally assigned, unicast MacAddress with Google OUI.
     *
     * @hide
     */
    public static @NonNull MacAddress createRandomUnicastAddressWithGoogleBase() {
        return createRandomUnicastAddress(BASE_GOOGLE_MAC, new SecureRandom());
    }

    /**
     * Returns a generated MAC address whose 46 bits, excluding the locally assigned bit and the
     * unicast bit, are randomly selected.
     *
     * The locally assigned bit is always set to 1. The multicast bit is always set to 0.
     *
     * @return a random locally assigned, unicast MacAddress.
     *
     * @hide
     */
    public static @NonNull MacAddress createRandomUnicastAddress() {
        SecureRandom r = new SecureRandom();
        long addr = r.nextLong() & VALID_LONG_MASK;
        addr |= LOCALLY_ASSIGNED_MASK;
        addr &= ~MULTICAST_MASK;
        return new MacAddress(addr);
    }

    /**
     * Returns a randomly generated MAC address using the given Random object and the same
     * OUI values as the given MacAddress.
     *
     * The locally assigned bit is always set to 1. The multicast bit is always set to 0.
     *
     * @param base a base MacAddress whose OUI is used for generating the random address.
     * @param r a standard Java Random object used for generating the random address.
     * @return a random locally assigned MacAddress.
     *
     * @hide
     */
    public static @NonNull MacAddress createRandomUnicastAddress(MacAddress base, Random r) {
        long addr = (base.mAddr & OUI_MASK) | (NIC_MASK & r.nextLong());
        addr |= LOCALLY_ASSIGNED_MASK;
        addr &= ~MULTICAST_MASK;
        return new MacAddress(addr);
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

    /**
     * Checks if this MAC Address matches the provided range.
     *
     * @param baseAddress MacAddress representing the base address to compare with.
     * @param mask MacAddress representing the mask to use during comparison.
     * @return true if this MAC Address matches the given range.
     *
     * @hide
     */
    public boolean matches(@NonNull MacAddress baseAddress, @NonNull MacAddress mask) {
        Preconditions.checkNotNull(baseAddress);
        Preconditions.checkNotNull(mask);
        return (mAddr & mask.mAddr) == (baseAddress.mAddr & mask.mAddr);
    }

    /**
     * Create a link-local Inet6Address from the MAC address. The EUI-48 MAC address is converted
     * to an EUI-64 MAC address per RFC 4291. The resulting EUI-64 is used to construct a link-local
     * IPv6 address per RFC 4862.
     *
     * @return A link-local Inet6Address constructed from the MAC address.
     * @hide
     */
    public @Nullable Inet6Address getLinkLocalIpv6FromEui48Mac() {
        byte[] macEui48Bytes = toByteArray();
        byte[] addr = new byte[16];

        addr[0] = (byte) 0xfe;
        addr[1] = (byte) 0x80;
        addr[8] = (byte) (macEui48Bytes[0] ^ (byte) 0x02); // flip the link-local bit
        addr[9] = macEui48Bytes[1];
        addr[10] = macEui48Bytes[2];
        addr[11] = (byte) 0xff;
        addr[12] = (byte) 0xfe;
        addr[13] = macEui48Bytes[3];
        addr[14] = macEui48Bytes[4];
        addr[15] = macEui48Bytes[5];

        try {
            return Inet6Address.getByAddress(null, addr, 0);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
