/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.net.UnknownHostException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;

import java.util.Collection;

/**
 * A simple container for route information.
 *
 * @hide
 */
public class RouteInfo implements Parcelable {
    /**
     * The IP destination address for this route.
     */
    private final LinkAddress mDestination;

    /**
     * The gateway address for this route.
     */
    private final InetAddress mGateway;

    private final boolean mIsDefault;
    private final boolean mIsHost;

    public RouteInfo(LinkAddress destination, InetAddress gateway) {
        if (destination == null) {
            if (gateway != null) {
                if (gateway instanceof Inet4Address) {
                    destination = new LinkAddress(Inet4Address.ANY, 0);
                } else {
                    destination = new LinkAddress(Inet6Address.ANY, 0);
                }
            } else {
                // no destination, no gateway. invalid.
                throw new RuntimeException("Invalid arguments passed in.");
            }
        }
        if (gateway == null) {
            if (destination.getAddress() instanceof Inet4Address) {
                gateway = Inet4Address.ANY;
            } else {
                gateway = Inet6Address.ANY;
            }
        }
        mDestination = new LinkAddress(NetworkUtils.getNetworkPart(destination.getAddress(),
                destination.getNetworkPrefixLength()), destination.getNetworkPrefixLength());
        mGateway = gateway;
        mIsDefault = isDefault();
        mIsHost = isHost();
    }

    public RouteInfo(InetAddress gateway) {
        this(null, gateway);
    }

    public static RouteInfo makeHostRoute(InetAddress host) {
        return makeHostRoute(host, null);
    }

    public static RouteInfo makeHostRoute(InetAddress host, InetAddress gateway) {
        if (host == null) return null;

        if (host instanceof Inet4Address) {
            return new RouteInfo(new LinkAddress(host, 32), gateway);
        } else {
            return new RouteInfo(new LinkAddress(host, 128), gateway);
        }
    }

    private boolean isHost() {
        return (mGateway.equals(Inet4Address.ANY) || mGateway.equals(Inet6Address.ANY));
    }

    private boolean isDefault() {
        boolean val = false;
        if (mGateway != null) {
            if (mGateway instanceof Inet4Address) {
                val = (mDestination == null || mDestination.getNetworkPrefixLength() == 0);
            } else {
                val = (mDestination == null || mDestination.getNetworkPrefixLength() == 0);
            }
        }
        return val;
    }


    public LinkAddress getDestination() {
        return mDestination;
    }

    public InetAddress getGateway() {
        return mGateway;
    }

    public boolean isDefaultRoute() {
        return mIsDefault;
    }

    public boolean isHostRoute() {
        return mIsHost;
    }

    public String toString() {
        String val = "";
        if (mDestination != null) val = mDestination.toString();
        if (mGateway != null) val += " -> " + mGateway.getHostAddress();
        return val;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (mDestination == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeByteArray(mDestination.getAddress().getAddress());
            dest.writeInt(mDestination.getNetworkPrefixLength());
        }

        if (mGateway == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeByteArray(mGateway.getAddress());
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof RouteInfo)) return false;

        RouteInfo target = (RouteInfo) obj;

        boolean sameDestination = ( mDestination == null) ?
                target.getDestination() == null
                : mDestination.equals(target.getDestination());

        boolean sameAddress = (mGateway == null) ?
                target.getGateway() == null
                : mGateway.equals(target.getGateway());

        return sameDestination && sameAddress
            && mIsDefault == target.mIsDefault;
    }

    @Override
    public int hashCode() {
        return (mDestination == null ? 0 : mDestination.hashCode())
            + (mGateway == null ? 0 :mGateway.hashCode())
            + (mIsDefault ? 3 : 7);
    }

    public static final Creator<RouteInfo> CREATOR =
        new Creator<RouteInfo>() {
        public RouteInfo createFromParcel(Parcel in) {
            InetAddress destAddr = null;
            int prefix = 0;
            InetAddress gateway = null;

            if (in.readByte() == 1) {
                byte[] addr = in.createByteArray();
                prefix = in.readInt();

                try {
                    destAddr = InetAddress.getByAddress(addr);
                } catch (UnknownHostException e) {}
            }

            if (in.readByte() == 1) {
                byte[] addr = in.createByteArray();

                try {
                    gateway = InetAddress.getByAddress(addr);
                } catch (UnknownHostException e) {}
            }

            LinkAddress dest = null;

            if (destAddr != null) {
                dest = new LinkAddress(destAddr, prefix);
            }

            return new RouteInfo(dest, gateway);
        }

        public RouteInfo[] newArray(int size) {
            return new RouteInfo[size];
        }
    };

    private boolean matches(InetAddress destination) {
        if (destination == null) return false;

        // if the destination is present and the route is default.
        // return true
        if (isDefault()) return true;

        // match the route destination and destination with prefix length
        InetAddress dstNet = NetworkUtils.getNetworkPart(destination,
                mDestination.getNetworkPrefixLength());

        return mDestination.getAddress().equals(dstNet);
    }

    /**
     * Find the route from a Collection of routes that best matches a given address.
     * May return null if no routes are applicable.
     * @param routes a Collection of RouteInfos to chose from
     * @param dest the InetAddress your trying to get to
     * @return the RouteInfo from the Collection that best fits the given address
     */
    public static RouteInfo selectBestRoute(Collection<RouteInfo> routes, InetAddress dest) {
        if ((routes == null) || (dest == null)) return null;

        RouteInfo bestRoute = null;
        // pick a longest prefix match under same address type
        for (RouteInfo route : routes) {
            if (NetworkUtils.addressTypeMatches(route.mDestination.getAddress(), dest)) {
                if ((bestRoute != null) &&
                        (bestRoute.mDestination.getNetworkPrefixLength() >=
                        route.mDestination.getNetworkPrefixLength())) {
                    continue;
                }
                if (route.matches(dest)) bestRoute = route;
            }
        }
        return bestRoute;
    }
}
