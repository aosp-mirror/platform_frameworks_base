/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi.aware;

import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Wi-Fi Aware-specific network information. The information can be extracted from the
 * {@link android.net.NetworkCapabilities} of the network using
 * {@link NetworkCapabilities#getTransportInfo()}.
 * The {@link NetworkCapabilities} is provided by the connectivity service to apps, e.g. received
 * through the
 * {@link android.net.ConnectivityManager.NetworkCallback#onCapabilitiesChanged(android.net.Network,
 * android.net.NetworkCapabilities)} callback.
 * <p>
 * The Wi-Fi Aware-specific network information include the peer's scoped link-local IPv6 address
 * for the Wi-Fi Aware link. The scoped link-local IPv6 can then be used to create a
 * {@link java.net.Socket} connection to the peer.
 */
public final class WifiAwareNetworkInfo implements TransportInfo, Parcelable {
    private Inet6Address mIpv6Addr;

    /** @hide */
    public WifiAwareNetworkInfo(Inet6Address ipv6Addr) {
        mIpv6Addr = ipv6Addr;
    }

    /**
     * Get the scoped link-local IPv6 address of the Wi-Fi Aware peer (not of the local device!).
     *
     * @return An IPv6 address.
     */
    @Nullable
    public Inet6Address getPeerIpv6Addr() {
        return mIpv6Addr;
    }

    // parcelable methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(mIpv6Addr.getAddress());
        NetworkInterface ni = mIpv6Addr.getScopedInterface();
        dest.writeString(ni == null ? null : ni.getName());
    }

    public static final Creator<WifiAwareNetworkInfo> CREATOR =
            new Creator<WifiAwareNetworkInfo>() {
                @Override
                public WifiAwareNetworkInfo createFromParcel(Parcel in) {
                    Inet6Address ipv6Addr;
                    try {
                        byte[] addr = in.createByteArray();
                        String interfaceName = in.readString();
                        NetworkInterface ni = null;
                        if (interfaceName != null) {
                            try {
                                ni = NetworkInterface.getByName(interfaceName);
                            } catch (SocketException e) {
                                e.printStackTrace();
                            }
                        }
                        ipv6Addr = Inet6Address.getByAddress(null, addr, ni);
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                        return null;
                    }

                    return new WifiAwareNetworkInfo(ipv6Addr);
                }

                @Override
                public WifiAwareNetworkInfo[] newArray(int size) {
                    return new WifiAwareNetworkInfo[size];
                }
            };


    // object methods

    @Override
    public String toString() {
        return new StringBuilder("AwareNetworkInfo: IPv6=").append(mIpv6Addr).toString();
    }

    /** @hide */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof WifiAwareNetworkInfo)) {
            return false;
        }

        WifiAwareNetworkInfo lhs = (WifiAwareNetworkInfo) obj;
        return Objects.equals(mIpv6Addr, lhs.mIpv6Addr);
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(mIpv6Addr);
    }
}
