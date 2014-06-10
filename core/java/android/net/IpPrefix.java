/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * This class represents an IP prefix, i.e., a contiguous block of IP addresses aligned on a
 * power of two boundary (also known as an "IP subnet"). A prefix is specified by two pieces of
 * information:
 *
 * <ul>
 * <li>A starting IP address (IPv4 or IPv6). This is the first IP address of the prefix.
 * <li>A prefix length. This specifies the length of the prefix by specifing the number of bits
 *     in the IP address, starting from the most significant bit in network byte order, that
 *     are constant for all addresses in the prefix.
 * </ul>
 *
 * For example, the prefix <code>192.0.2.0/24</code> covers the 256 IPv4 addresses from
 * <code>192.0.2.0</code> to <code>192.0.2.255</code>, inclusive, and the prefix
 * <code>2001:db8:1:2</code>  covers the 2^64 IPv6 addresses from <code>2001:db8:1:2::</code> to
 * <code>2001:db8:1:2:ffff:ffff:ffff:ffff</code>, inclusive.
 *
 * Objects of this class are immutable.
 */
public class IpPrefix implements Parcelable {
    private final byte[] address;  // network byte order
    private final int prefixLength;

    /**
     * Constructs a new {@code IpPrefix} from a byte array containing an IPv4 or IPv6 address in
     * network byte order and a prefix length.
     *
     * @param address the IP address. Must be non-null and exactly 4 or 16 bytes long.
     * @param prefixLength the prefix length. Must be &gt;= 0 and &lt;= (32 or 128) (IPv4 or IPv6).
     *
     * @hide
     */
    public IpPrefix(byte[] address, int prefixLength) {
        if (address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException(
                    "IpPrefix has " + address.length + " bytes which is neither 4 nor 16");
        }
        if (prefixLength < 0 || prefixLength > (address.length * 8)) {
            throw new IllegalArgumentException("IpPrefix with " + address.length +
                    " bytes has invalid prefix length " + prefixLength);
        }
        this.address = address.clone();
        this.prefixLength = prefixLength;
        // TODO: Validate that the non-prefix bits are zero
    }

    /**
     * @hide
     */
    public IpPrefix(InetAddress address, int prefixLength) {
        this(address.getAddress(), prefixLength);
    }

    /**
     * Compares this {@code IpPrefix} object against the specified object in {@code obj}. Two
     * objects are equal if they have the same startAddress and prefixLength.
     *
     * @param obj the object to be tested for equality.
     * @return {@code true} if both objects are equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof IpPrefix)) {
            return false;
        }
        IpPrefix that = (IpPrefix) obj;
        return Arrays.equals(this.address, that.address) && this.prefixLength == that.prefixLength;
    }

    /**
     * Gets the hashcode of the represented IP prefix.
     *
     * @return the appropriate hashcode value.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(address) + 11 * prefixLength;
    }

    /**
     * Returns a copy of the first IP address in the prefix. Modifying the returned object does not
     * change this object's contents.
     *
     * @return the address in the form of a byte array.
     */
    public InetAddress getAddress() {
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            // Cannot happen. InetAddress.getByAddress can only throw an exception if the byte
            // array is the wrong length, but we check that in the constructor.
            return null;
        }
    }

    /**
     * Returns a copy of the IP address bytes in network order (the highest order byte is the zeroth
     * element). Modifying the returned array does not change this object's contents.
     *
     * @return the address in the form of a byte array.
     */
    public byte[] getRawAddress() {
        return address.clone();
    }

    /**
     * Returns the prefix length of this {@code IpAddress}.
     *
     * @return the prefix length.
     */
    public int getPrefixLength() {
        return prefixLength;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(address);
        dest.writeInt(prefixLength);
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<IpPrefix> CREATOR =
            new Creator<IpPrefix>() {
                public IpPrefix createFromParcel(Parcel in) {
                    byte[] address = in.createByteArray();
                    int prefixLength = in.readInt();
                    return new IpPrefix(address, prefixLength);
                }

                public IpPrefix[] newArray(int size) {
                    return new IpPrefix[size];
                }
            };
}
