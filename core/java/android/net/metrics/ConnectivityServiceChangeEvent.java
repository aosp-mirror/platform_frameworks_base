/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.metrics;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@hide}
 */
public class ConnectivityServiceChangeEvent extends IpConnectivityEvent implements Parcelable {
    public static final String TAG = "ConnectivityServiceChangeEvent";

    // The ID of the network that has become the new default or NETID_UNSET if none.
    private final int mNetId;
    // The list of transport types of the new default network, for example TRANSPORT_WIFI, as
    // defined in NetworkCapabilities.java.
    private final int[] mTransportTypes;
    // The ID of the network that was the default before or NETID_UNSET if none.
    private final int mPrevNetId;
    // Whether the previous network had IPv4/IPv6 connectivity.
    private final boolean mPrevIPv4;
    private final boolean mPrevIPv6;

    public ConnectivityServiceChangeEvent(int netId, int[] transportTypes,
                int prevNetId, boolean prevIPv4, boolean prevIPv6) {
        mNetId = netId;
        mTransportTypes = transportTypes;
        mPrevNetId = prevNetId;
        mPrevIPv4 = prevIPv4;
        mPrevIPv6 = prevIPv6;
    }

    public ConnectivityServiceChangeEvent(Parcel in) {
        mNetId = in.readInt();
        mTransportTypes = in.createIntArray();
        mPrevNetId = in.readInt();
        mPrevIPv4 = (in.readByte() > 0);
        mPrevIPv6 = (in.readByte() > 0);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mNetId);
        out.writeIntArray(mTransportTypes);
        out.writeInt(mPrevNetId);
        out.writeByte(mPrevIPv4 ? (byte) 1 : (byte) 0);
        out.writeByte(mPrevIPv6 ? (byte) 1 : (byte) 0);
    }

    public static final Parcelable.Creator<ConnectivityServiceChangeEvent> CREATOR
        = new Parcelable.Creator<ConnectivityServiceChangeEvent>() {
        public ConnectivityServiceChangeEvent createFromParcel(Parcel in) {
            return new ConnectivityServiceChangeEvent(in);
        }

        public ConnectivityServiceChangeEvent[] newArray(int size) {
            return new ConnectivityServiceChangeEvent[size];
        }
    };

    public static void logEvent(int netId, int[] transportTypes,
            int prevNetId, boolean prevIPv4, boolean prevIPv6) {
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_CONSRV_DEFAULT_NET_CHANGE,
                new ConnectivityServiceChangeEvent(
                        netId, transportTypes, prevNetId, prevIPv4, prevIPv6));
    }
};
