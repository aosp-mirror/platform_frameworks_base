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

import android.os.Parcelable;
import android.os.Parcel;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Identifies the Network.
 * @hide
 */
public class Network implements Parcelable {

    public final int netId;

    public Network(int netId) {
        this.netId = netId;
    }

    public Network(Network that) {
        this.netId = that.netId;
    }

    /**
     * Operates the same as {@code InetAddress.getAllByName} except that host
     * resolution is done on this network.
     *
     * @param host the hostname or literal IP string to be resolved.
     * @return the array of addresses associated with the specified host.
     * @throws UnknownHostException if the address lookup fails.
     */
    public InetAddress[] getAllByName(String host) throws UnknownHostException {
        return InetAddress.getAllByNameOnNet(host, netId);
    }

    /**
     * Operates the same as {@code InetAddress.getByName} except that host
     * resolution is done on this network.
     *
     * @param host
     *            the hostName to be resolved to an address or {@code null}.
     * @return the {@code InetAddress} instance representing the host.
     * @throws UnknownHostException
     *             if the address lookup fails.
     */
    public InetAddress getByName(String host) throws UnknownHostException {
        return InetAddress.getByNameOnNet(host, netId);
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(netId);
    }

    public static final Creator<Network> CREATOR =
        new Creator<Network>() {
            public Network createFromParcel(Parcel in) {
                int netId = in.readInt();

                return new Network(netId);
            }

            public Network[] newArray(int size) {
                return new Network[size];
            }
    };
}
