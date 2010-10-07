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
    private final InetAddress address;

    /**
     * Network prefix
     */
    private final int prefix;

    public LinkAddress(InetAddress address, InetAddress mask) {
        this.address = address;
        this.prefix = computeprefix(mask);
    }

    public LinkAddress(InetAddress address, int prefix) {
        this.address = address;
        this.prefix = prefix;
    }

    public LinkAddress(InterfaceAddress interfaceAddress) {
        this.address = interfaceAddress.getAddress();
        this.prefix = interfaceAddress.getNetworkPrefixLength();
    }

    private static int computeprefix(InetAddress mask) {
        int count = 0;
        for (byte b : mask.getAddress()) {
            for (int i = 0; i < 8; ++i) {
                if ((b & (1 << i)) != 0) {
                    ++count;
                }
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return (address == null ? "" : (address.getHostAddress() + "/" + prefix));
    }

    /**
     * Compares this {@code LinkAddress} instance against the specified address
     * in {@code obj}. Two addresses are equal if their InetAddress and prefix
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
            this.prefix == linkAddress.prefix;
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
    public int getNetworkPrefix() {
        return prefix;
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
            dest.writeInt(prefix);
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
                int prefix = 0;
                if (in.readByte() == 1) {
                    try {
                        address = InetAddress.getByAddress(in.createByteArray());
                        prefix = in.readInt();
                    } catch (UnknownHostException e) { }
                }
                return new LinkAddress(address, prefix);
            }

            public LinkAddress[] newArray(int size) {
                return new LinkAddress[size];
            }
        };
}
