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

package android.telephony.data;

import android.annotation.SystemApi;
import android.net.NetworkUtils;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * This class represents a Network Interface address. In short it's an IP address, a subnet mask
 * when the address is an IPv4 one. An IP address and a network prefix length in the case of IPv6
 * address.
 *
 * @hide
 */
@SystemApi
public final class InterfaceAddress implements Parcelable {

    private final InetAddress mInetAddress;

    private final int mPrefixLength;

    /**
     * @param inetAddress A {@link InetAddress} of the address
     * @param prefixLength The network prefix length for this address.
     */
    public InterfaceAddress(InetAddress inetAddress, int prefixLength) {
        mInetAddress = inetAddress;
        mPrefixLength = prefixLength;
    }

    /**
     * @param address The address in string format
     * @param prefixLength The network prefix length for this address.
     * @throws UnknownHostException
     */
    public InterfaceAddress(String address, int prefixLength) throws UnknownHostException {
        InetAddress ia;
        try {
            ia = NetworkUtils.numericToInetAddress(address);
        } catch (IllegalArgumentException e) {
            throw new UnknownHostException("Non-numeric ip addr=" + address);
        }
        mInetAddress = ia;
        mPrefixLength = prefixLength;
    }

    public InterfaceAddress(Parcel source) {
        mInetAddress = (InetAddress) source.readSerializable();
        mPrefixLength = source.readInt();
    }

    /**
     * @return an InetAddress for this address.
     */
    public InetAddress getAddress() { return mInetAddress; }

    /**
     * @return The network prefix length for this address.
     */
    public int getNetworkPrefixLength() { return mPrefixLength; }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return mInetAddress + "/" + mPrefixLength;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeSerializable(mInetAddress);
        dest.writeInt(mPrefixLength);
    }

    public static final Parcelable.Creator<InterfaceAddress> CREATOR =
            new Parcelable.Creator<InterfaceAddress>() {
        @Override
        public InterfaceAddress createFromParcel(Parcel source) {
            return new InterfaceAddress(source);
        }

        @Override
        public InterfaceAddress[] newArray(int size) {
            return new InterfaceAddress[size];
        }
    };
}
