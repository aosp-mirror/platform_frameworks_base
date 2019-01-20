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

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Objects;

/**
 * Represents a network route.
 * <p>
 * This is used both to describe static network configuration and live network
 * configuration information.
 *
 * A route contains three pieces of information:
 * <ul>
 * <li>a destination {@link IpPrefix} specifying the network destinations covered by this route.
 *     If this is {@code null} it indicates a default route of the address family (IPv4 or IPv6)
 *     implied by the gateway IP address.
 * <li>a gateway {@link InetAddress} indicating the next hop to use.  If this is {@code null} it
 *     indicates a directly-connected route.
 * <li>an interface (which may be unspecified).
 * </ul>
 * Either the destination or the gateway may be {@code null}, but not both.  If the
 * destination and gateway are both specified, they must be of the same address family
 * (IPv4 or IPv6).
 */
public final class RouteInfo implements Parcelable {
    /**
     * The IP destination address for this route.
     */
    private final IpPrefix mDestination;

    /**
     * The gateway address for this route.
     */
    @UnsupportedAppUsage
    private final InetAddress mGateway;

    /**
     * The interface for this route.
     */
    private final String mInterface;


    /** Unicast route. @hide */
    @SystemApi
    @TestApi
    public static final int RTN_UNICAST = 1;

    /** Unreachable route. @hide */
    @SystemApi
    @TestApi
    public static final int RTN_UNREACHABLE = 7;

    /** Throw route. @hide */
    @SystemApi
    @TestApi
    public static final int RTN_THROW = 9;

    /**
     * The type of this route; one of the RTN_xxx constants above.
     */
    private final int mType;

    // Derived data members.
    // TODO: remove these.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final boolean mIsHost;
    private final boolean mHasGateway;

    /**
     * Constructs a RouteInfo object.
     *
     * If destination is null, then gateway must be specified and the
     * constructed route is either the IPv4 default route <code>0.0.0.0</code>
     * if the gateway is an instance of {@link Inet4Address}, or the IPv6 default
     * route <code>::/0</code> if gateway is an instance of
     * {@link Inet6Address}.
     * <p>
     * destination and gateway may not both be null.
     *
     * @param destination the destination prefix
     * @param gateway the IP address to route packets through
     * @param iface the interface name to send packets on
     *
     * @hide
     */
    public RouteInfo(IpPrefix destination, InetAddress gateway, String iface, int type) {
        switch (type) {
            case RTN_UNICAST:
            case RTN_UNREACHABLE:
            case RTN_THROW:
                // TODO: It would be nice to ensure that route types that don't have nexthops or
                // interfaces, such as unreachable or throw, can't be created if an interface or
                // a gateway is specified. This is a bit too complicated to do at the moment
                // because:
                //
                // - LinkProperties sets the interface on routes added to it, and modifies the
                //   interfaces of all the routes when its interface name changes.
                // - Even when the gateway is null, we store a non-null gateway here.
                //
                // For now, we just rely on the code that sets routes to do things properly.
                break;
            default:
                throw new IllegalArgumentException("Unknown route type " + type);
        }

        if (destination == null) {
            if (gateway != null) {
                if (gateway instanceof Inet4Address) {
                    destination = new IpPrefix(Inet4Address.ANY, 0);
                } else {
                    destination = new IpPrefix(Inet6Address.ANY, 0);
                }
            } else {
                // no destination, no gateway. invalid.
                throw new IllegalArgumentException("Invalid arguments passed in: " + gateway + "," +
                                                   destination);
            }
        }
        // TODO: set mGateway to null if there is no gateway. This is more correct, saves space, and
        // matches the documented behaviour. Before we can do this we need to fix all callers (e.g.,
        // ConnectivityService) to stop doing things like r.getGateway().equals(), ... .
        if (gateway == null) {
            if (destination.getAddress() instanceof Inet4Address) {
                gateway = Inet4Address.ANY;
            } else {
                gateway = Inet6Address.ANY;
            }
        }
        mHasGateway = (!gateway.isAnyLocalAddress());

        if ((destination.getAddress() instanceof Inet4Address &&
                 (gateway instanceof Inet4Address == false)) ||
                (destination.getAddress() instanceof Inet6Address &&
                 (gateway instanceof Inet6Address == false))) {
            throw new IllegalArgumentException("address family mismatch in RouteInfo constructor");
        }
        mDestination = destination;  // IpPrefix objects are immutable.
        mGateway = gateway;          // InetAddress objects are immutable.
        mInterface = iface;          // Strings are immutable.
        mType = type;
        mIsHost = isHost();
    }

    /**
     *  @hide
     */
    @UnsupportedAppUsage
    public RouteInfo(IpPrefix destination, InetAddress gateway, String iface) {
        this(destination, gateway, iface, RTN_UNICAST);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public RouteInfo(LinkAddress destination, InetAddress gateway, String iface) {
        this(destination == null ? null :
                new IpPrefix(destination.getAddress(), destination.getPrefixLength()),
                gateway, iface);
    }

    /**
     * Constructs a {@code RouteInfo} object.
     *
     * If destination is null, then gateway must be specified and the
     * constructed route is either the IPv4 default route <code>0.0.0.0</code>
     * if the gateway is an instance of {@link Inet4Address}, or the IPv6 default
     * route <code>::/0</code> if gateway is an instance of {@link Inet6Address}.
     * <p>
     * Destination and gateway may not both be null.
     *
     * @param destination the destination address and prefix in an {@link IpPrefix}
     * @param gateway the {@link InetAddress} to route packets through
     *
     * @hide
     */
    public RouteInfo(IpPrefix destination, InetAddress gateway) {
        this(destination, gateway, null);
    }

    /**
     * @hide
     *
     * TODO: Remove this.
     */
    @UnsupportedAppUsage
    public RouteInfo(LinkAddress destination, InetAddress gateway) {
        this(destination, gateway, null);
    }

    /**
     * Constructs a default {@code RouteInfo} object.
     *
     * @param gateway the {@link InetAddress} to route packets through
     *
     * @hide
     */
    @UnsupportedAppUsage
    public RouteInfo(InetAddress gateway) {
        this((IpPrefix) null, gateway, null);
    }

    /**
     * Constructs a {@code RouteInfo} object representing a direct connected subnet.
     *
     * @param destination the {@link IpPrefix} describing the address and prefix
     *                    length of the subnet.
     *
     * @hide
     */
    public RouteInfo(IpPrefix destination) {
        this(destination, null, null);
    }

    /**
     * @hide
     */
    public RouteInfo(LinkAddress destination) {
        this(destination, null, null);
    }

    /**
     * @hide
     */
    public RouteInfo(IpPrefix destination, int type) {
        this(destination, null, null, type);
    }

    /**
     * @hide
     */
    public static RouteInfo makeHostRoute(InetAddress host, String iface) {
        return makeHostRoute(host, null, iface);
    }

    /**
     * @hide
     */
    public static RouteInfo makeHostRoute(InetAddress host, InetAddress gateway, String iface) {
        if (host == null) return null;

        if (host instanceof Inet4Address) {
            return new RouteInfo(new IpPrefix(host, 32), gateway, iface);
        } else {
            return new RouteInfo(new IpPrefix(host, 128), gateway, iface);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private boolean isHost() {
        return (mDestination.getAddress() instanceof Inet4Address &&
                mDestination.getPrefixLength() == 32) ||
               (mDestination.getAddress() instanceof Inet6Address &&
                mDestination.getPrefixLength() == 128);
    }

    /**
     * Retrieves the destination address and prefix length in the form of an {@link IpPrefix}.
     *
     * @return {@link IpPrefix} specifying the destination.  This is never {@code null}.
     */
    public IpPrefix getDestination() {
        return mDestination;
    }

    /**
     * TODO: Convert callers to use IpPrefix and then remove.
     * @hide
     */
    public LinkAddress getDestinationLinkAddress() {
        return new LinkAddress(mDestination.getAddress(), mDestination.getPrefixLength());
    }

    /**
     * Retrieves the gateway or next hop {@link InetAddress} for this route.
     *
     * @return {@link InetAddress} specifying the gateway or next hop.  This may be
     *                             {@code null} for a directly-connected route."
     */
    public InetAddress getGateway() {
        return mGateway;
    }

    /**
     * Retrieves the interface used for this route if specified, else {@code null}.
     *
     * @return The name of the interface used for this route.
     */
    public String getInterface() {
        return mInterface;
    }

    /**
     * Retrieves the type of this route.
     *
     * @return The type of this route; one of the {@code RTN_xxx} constants defined in this class.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public int getType() {
        return mType;
    }

    /**
     * Indicates if this route is a default route (ie, has no destination specified).
     *
     * @return {@code true} if the destination has a prefix length of 0.
     */
    public boolean isDefaultRoute() {
        return mType == RTN_UNICAST && mDestination.getPrefixLength() == 0;
    }

    /**
     * Indicates if this route is an IPv4 default route.
     * @hide
     */
    public boolean isIPv4Default() {
        return isDefaultRoute() && mDestination.getAddress() instanceof Inet4Address;
    }

    /**
     * Indicates if this route is an IPv6 default route.
     * @hide
     */
    public boolean isIPv6Default() {
        return isDefaultRoute() && mDestination.getAddress() instanceof Inet6Address;
    }

    /**
     * Indicates if this route is a host route (ie, matches only a single host address).
     *
     * @return {@code true} if the destination has a prefix length of 32 or 128 for IPv4 or IPv6,
     * respectively.
     * @hide
     */
    public boolean isHostRoute() {
        return mIsHost;
    }

    /**
     * Indicates if this route has a next hop ({@code true}) or is directly-connected
     * ({@code false}).
     *
     * @return {@code true} if a gateway is specified
     */
    public boolean hasGateway() {
        return mHasGateway;
    }

    /**
     * Determines whether the destination and prefix of this route includes the specified
     * address.
     *
     * @param destination A {@link InetAddress} to test to see if it would match this route.
     * @return {@code true} if the destination and prefix length cover the given address.
     */
    public boolean matches(InetAddress destination) {
        return mDestination.contains(destination);
    }

    /**
     * Find the route from a Collection of routes that best matches a given address.
     * May return null if no routes are applicable.
     * @param routes a Collection of RouteInfos to chose from
     * @param dest the InetAddress your trying to get to
     * @return the RouteInfo from the Collection that best fits the given address
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static RouteInfo selectBestRoute(Collection<RouteInfo> routes, InetAddress dest) {
        if ((routes == null) || (dest == null)) return null;

        RouteInfo bestRoute = null;
        // pick a longest prefix match under same address type
        for (RouteInfo route : routes) {
            if (NetworkUtils.addressTypeMatches(route.mDestination.getAddress(), dest)) {
                if ((bestRoute != null) &&
                        (bestRoute.mDestination.getPrefixLength() >=
                        route.mDestination.getPrefixLength())) {
                    continue;
                }
                if (route.matches(dest)) bestRoute = route;
            }
        }
        return bestRoute;
    }

    /**
     * Returns a human-readable description of this object.
     */
    public String toString() {
        String val = "";
        if (mDestination != null) val = mDestination.toString();
        if (mType == RTN_UNREACHABLE) {
            val += " unreachable";
        } else if (mType == RTN_THROW) {
            val += " throw";
        } else {
            val += " ->";
            if (mGateway != null) val += " " + mGateway.getHostAddress();
            if (mInterface != null) val += " " + mInterface;
            if (mType != RTN_UNICAST) {
                val += " unknown type " + mType;
            }
        }
        return val;
    }

    /**
     * Compares this RouteInfo object against the specified object and indicates if they are equal.
     * @return {@code true} if the objects are equal, {@code false} otherwise.
     */
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof RouteInfo)) return false;

        RouteInfo target = (RouteInfo) obj;

        return Objects.equals(mDestination, target.getDestination()) &&
                Objects.equals(mGateway, target.getGateway()) &&
                Objects.equals(mInterface, target.getInterface()) &&
                mType == target.getType();
    }

    /**
     *  Returns a hashcode for this <code>RouteInfo</code> object.
     */
    public int hashCode() {
        return (mDestination.hashCode() * 41)
                + (mGateway == null ? 0 :mGateway.hashCode() * 47)
                + (mInterface == null ? 0 :mInterface.hashCode() * 67)
                + (mType * 71);
    }

    /**
     * Implement the Parcelable interface
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mDestination, flags);
        byte[] gatewayBytes = (mGateway == null) ? null : mGateway.getAddress();
        dest.writeByteArray(gatewayBytes);
        dest.writeString(mInterface);
        dest.writeInt(mType);
    }

    /**
     * Implement the Parcelable interface.
     */
    public static final Creator<RouteInfo> CREATOR =
        new Creator<RouteInfo>() {
        public RouteInfo createFromParcel(Parcel in) {
            IpPrefix dest = in.readParcelable(null);

            InetAddress gateway = null;
            byte[] addr = in.createByteArray();
            try {
                gateway = InetAddress.getByAddress(addr);
            } catch (UnknownHostException e) {}

            String iface = in.readString();
            int type = in.readInt();

            return new RouteInfo(dest, gateway, iface, type);
        }

        public RouteInfo[] newArray(int size) {
            return new RouteInfo[size];
        }
    };
}
