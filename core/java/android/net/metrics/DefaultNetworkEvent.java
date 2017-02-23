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

import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event recorded by ConnectivityService when there is a change in the default network.
 * {@hide}
 */
public final class DefaultNetworkEvent implements Parcelable {
    // The ID of the network that has become the new default or NETID_UNSET if none.
    public final int netId;
    // The list of transport types of the new default network, for example TRANSPORT_WIFI, as
    // defined in NetworkCapabilities.java.
    public final int[] transportTypes;
    // The ID of the network that was the default before or NETID_UNSET if none.
    public final int prevNetId;
    // Whether the previous network had IPv4/IPv6 connectivity.
    public final boolean prevIPv4;
    public final boolean prevIPv6;

    public DefaultNetworkEvent(int netId, int[] transportTypes,
                int prevNetId, boolean prevIPv4, boolean prevIPv6) {
        this.netId = netId;
        this.transportTypes = transportTypes;
        this.prevNetId = prevNetId;
        this.prevIPv4 = prevIPv4;
        this.prevIPv6 = prevIPv6;
    }

    private DefaultNetworkEvent(Parcel in) {
        this.netId = in.readInt();
        this.transportTypes = in.createIntArray();
        this.prevNetId = in.readInt();
        this.prevIPv4 = (in.readByte() > 0);
        this.prevIPv6 = (in.readByte() > 0);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(netId);
        out.writeIntArray(transportTypes);
        out.writeInt(prevNetId);
        out.writeByte(prevIPv4 ? (byte) 1 : (byte) 0);
        out.writeByte(prevIPv6 ? (byte) 1 : (byte) 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
      String prevNetwork = String.valueOf(prevNetId);
      String newNetwork = String.valueOf(netId);
      if (prevNetId != 0) {
          prevNetwork += ":" + ipSupport();
      }
      if (netId != 0) {
          newNetwork += ":" + NetworkCapabilities.transportNamesOf(transportTypes);
      }
      return String.format("DefaultNetworkEvent(%s -> %s)", prevNetwork, newNetwork);
    }

    private String ipSupport() {
        if (prevIPv4 && prevIPv6) {
            return "DUAL";
        }
        if (prevIPv6) {
            return "IPv6";
        }
        if (prevIPv4) {
            return "IPv4";
        }
        return "NONE";
    }

    public static final Parcelable.Creator<DefaultNetworkEvent> CREATOR
        = new Parcelable.Creator<DefaultNetworkEvent>() {
        public DefaultNetworkEvent createFromParcel(Parcel in) {
            return new DefaultNetworkEvent(in);
        }

        public DefaultNetworkEvent[] newArray(int size) {
            return new DefaultNetworkEvent[size];
        }
    };
}
