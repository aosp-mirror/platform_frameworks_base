/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.UnknownHostException;

/**
 * Identifies an address of a network link
 * @hide
 */
public class LinkAddress implements Parcelable {
    /**
     * IPv4 or IPv6 address.
     */
    private InetAddress address;

    /**
     * Network prefix length
     */
    private int prefixLength;

    private void init(InetAddress address, int prefixLength) {
        if (address == null || prefixLength < 0 ||
                ((address instanceof Inet4Address) && prefixLength > 32) ||
                (prefixLength > 128)) {
            throw new IllegalArgumentException("Bad LinkAddress params " + address +
                    "/" + prefixLength);
        }
        this.address = address;
        this.prefixLength = prefixLength;
    }

    public LinkAddress(InetAddress address, int prefixLength) {
        init(address, prefixLength);
    }

    public LinkAddress(InterfaceAddress interfaceAddress) {
        init(interfaceAddress.getAddress(),
             interfaceAddress.getNetworkPrefixLength());
    }

    /**
     * Constructs a new {@code LinkAddress} from a string such as "192.0.2.5/24" or
     * "2001:db8::1/64".
     * @param string The string to parse.
     */
    public LinkAddress(String address) {
        InetAddress inetAddress = null;
        int prefixLength = -1;
        try {
            String [] pieces = address.split("/", 2);
            prefixLength = Integer.parseInt(pieces[1]);
            inetAddress = InetAddress.parseNumericAddress(pieces[0]);
        } catch (NullPointerException e) {            // Null string.
        } catch (ArrayIndexOutOfBoundsException e) {  // No prefix length.
        } catch (NumberFormatException e) {           // Non-numeric prefix.
        } catch (IllegalArgumentException e) {        // Invalid IP address.
        }

        if (inetAddress == null || prefixLength == -1) {
            throw new IllegalArgumentException("Bad LinkAddress params " + address);
        }

        init(inetAddress, prefixLength);
    }

    @Override
    public String toString() {
        return (address == null ? "" : (address.getHostAddress() + "/" + prefixLength));
    }

    /**
     * Compares this {@code LinkAddress} instance against the specified address
     * in {@code obj}. Two addresses are equal if their InetAddress and prefixLength
     * are equal
     *
     * @param obj the object to be tested for equality.
     * @return {@code true} if both objects are equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LinkAddress)) {
            return false;
        }
        LinkAddress linkAddress = (LinkAddress) obj;
        return this.address.equals(linkAddress.address) &&
            this.prefixLength == linkAddress.prefixLength;
    }

    @Override
    /*
     * generate hashcode based on significant fields
     */
    public int hashCode() {
        return ((null == address) ? 0 : address.hashCode()) + prefixLength;
    }

    /**
     * Returns the InetAddress for this address.
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Get network prefix length
     */
    public int getNetworkPrefixLength() {
        return prefixLength;
    }

    /**
     * Implement the Parcelable interface
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
        if (address != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(address.getAddress());
            dest.writeInt(prefixLength);
        } else {
            dest.writeByte((byte)0);
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<LinkAddress> CREATOR =
        new Creator<LinkAddress>() {
            public LinkAddress createFromParcel(Parcel in) {
                InetAddress address = null;
                int prefixLength = 0;
                if (in.readByte() == 1) {
                    try {
                        address = InetAddress.getByAddress(in.createByteArray());
                        prefixLength = in.readInt();
                    } catch (UnknownHostException e) { }
                }
                return new LinkAddress(address, prefixLength);
            }

            public LinkAddress[] newArray(int size) {
                return new LinkAddress[size];
            }
        };
}
