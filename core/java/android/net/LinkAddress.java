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

import static android.system.OsConstants.IFA_F_DADFAILED;
import static android.system.OsConstants.IFA_F_DEPRECATED;
import static android.system.OsConstants.IFA_F_OPTIMISTIC;
import static android.system.OsConstants.IFA_F_PERMANENT;
import static android.system.OsConstants.IFA_F_TENTATIVE;
import static android.system.OsConstants.RT_SCOPE_HOST;
import static android.system.OsConstants.RT_SCOPE_LINK;
import static android.system.OsConstants.RT_SCOPE_SITE;
import static android.system.OsConstants.RT_SCOPE_UNIVERSE;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Pair;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Identifies an IP address on a network link.
 *
 * A {@code LinkAddress} consists of:
 * <ul>
 * <li>An IP address and prefix length (e.g., {@code 2001:db8::1/64} or {@code 192.0.2.1/24}).
 * The address must be unicast, as multicast addresses cannot be assigned to interfaces.
 * <li>Address flags: A bitmask of {@code OsConstants.IFA_F_*} values representing properties
 * of the address (e.g., {@code android.system.OsConstants.IFA_F_OPTIMISTIC}).
 * <li>Address scope: One of the {@code OsConstants.IFA_F_*} values; defines the scope in which
 * the address is unique (e.g.,
 * {@code android.system.OsConstants.RT_SCOPE_LINK} or
 * {@code android.system.OsConstants.RT_SCOPE_UNIVERSE}).
 * </ul>
 */
public class LinkAddress implements Parcelable {

    /**
     * Indicates the deprecation or expiration time is unknown
     * @hide
     */
    @SystemApi
    public static final long LIFETIME_UNKNOWN = -1;

    /**
     * Indicates this address is permanent.
     * @hide
     */
    @SystemApi
    public static final long LIFETIME_PERMANENT = Long.MAX_VALUE;

    /**
     * IPv4 or IPv6 address.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private InetAddress address;

    /**
     * Prefix length.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int prefixLength;

    /**
     * Address flags. A bitmask of {@code IFA_F_*} values. Note that {@link #getFlags()} may not
     * return these exact values. For example, it may set or clear the {@code IFA_F_DEPRECATED}
     * flag depending on the current preferred lifetime.
     */
    private int flags;

    /**
     * Address scope. One of the RT_SCOPE_* constants.
     */
    private int scope;

    /**
     * The time, as reported by {@link SystemClock#elapsedRealtime}, when this LinkAddress will be
     * or was deprecated. At the time existing connections can still use this address until it
     * expires, but new connections should use the new address. {@link #LIFETIME_UNKNOWN} indicates
     * this information is not available. {@link #LIFETIME_PERMANENT} indicates this
     * {@link LinkAddress} will never be deprecated.
     */
    private long deprecationTime;

    /**
     * The time, as reported by {@link SystemClock#elapsedRealtime}, when this {@link LinkAddress}
     * will expire and be removed from the interface. {@link #LIFETIME_UNKNOWN} indicates this
     * information is not available. {@link #LIFETIME_PERMANENT} indicates this {@link LinkAddress}
     * will never expire.
     */
    private long expirationTime;

    /**
     * Utility function to determines the scope of a unicast address. Per RFC 4291 section 2.5 and
     * RFC 6724 section 3.2.
     * @hide
     */
    private static int scopeForUnicastAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()) {
            return RT_SCOPE_HOST;
        }

        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            return RT_SCOPE_LINK;
        }

        // isSiteLocalAddress() returns true for private IPv4 addresses, but RFC 6724 section 3.2
        // says that they are assigned global scope.
        if (!(addr instanceof Inet4Address) && addr.isSiteLocalAddress()) {
            return RT_SCOPE_SITE;
        }

        return RT_SCOPE_UNIVERSE;
    }

    /**
     * Utility function to check if |address| is a Unique Local IPv6 Unicast Address
     * (a.k.a. "ULA"; RFC 4193).
     *
     * Per RFC 4193 section 8, fc00::/7 identifies these addresses.
     */
    private boolean isIpv6ULA() {
        if (isIpv6()) {
            byte[] bytes = address.getAddress();
            return ((bytes[0] & (byte)0xfe) == (byte)0xfc);
        }
        return false;
    }

    /**
     * @return true if the address is IPv6.
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isIpv6() {
        return address instanceof Inet6Address;
    }

    /**
     * For backward compatibility.
     * This was annotated with @UnsupportedAppUsage in P, so we can't remove the method completely
     * just yet.
     * @return true if the address is IPv6.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isIPv6() {
        return isIpv6();
    }

    /**
     * @return true if the address is IPv4 or is a mapped IPv4 address.
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isIpv4() {
        return address instanceof Inet4Address;
    }

    /**
     * Utility function for the constructors.
     */
    private void init(InetAddress address, int prefixLength, int flags, int scope,
                      long deprecationTime, long expirationTime) {
        if (address == null ||
                address.isMulticastAddress() ||
                prefixLength < 0 ||
                (address instanceof Inet4Address && prefixLength > 32) ||
                (prefixLength > 128)) {
            throw new IllegalArgumentException("Bad LinkAddress params " + address +
                    "/" + prefixLength);
        }

        // deprecation time and expiration time must be both provided, or neither.
        if ((deprecationTime == LIFETIME_UNKNOWN) != (expirationTime == LIFETIME_UNKNOWN)) {
            throw new IllegalArgumentException(
                    "Must not specify only one of deprecation time and expiration time");
        }

        // deprecation time needs to be a positive value.
        if (deprecationTime != LIFETIME_UNKNOWN && deprecationTime < 0) {
            throw new IllegalArgumentException("invalid deprecation time " + deprecationTime);
        }

        // expiration time needs to be a positive value.
        if (expirationTime != LIFETIME_UNKNOWN && expirationTime < 0) {
            throw new IllegalArgumentException("invalid expiration time " + expirationTime);
        }

        // expiration time can't be earlier than deprecation time
        if (deprecationTime != LIFETIME_UNKNOWN && expirationTime != LIFETIME_UNKNOWN
                && expirationTime < deprecationTime) {
            throw new IllegalArgumentException("expiration earlier than deprecation ("
                    + deprecationTime + ", " + expirationTime + ")");
        }

        this.address = address;
        this.prefixLength = prefixLength;
        this.flags = flags;
        this.scope = scope;
        this.deprecationTime = deprecationTime;
        this.expirationTime = expirationTime;
    }

    /**
     * Constructs a new {@code LinkAddress} from an {@code InetAddress} and prefix length, with
     * the specified flags and scope. Flags and scope are not checked for validity.
     *
     * @param address The IP address.
     * @param prefixLength The prefix length. Must be &gt;= 0 and &lt;= (32 or 128) (IPv4 or IPv6).
     * @param flags A bitmask of {@code IFA_F_*} values representing properties of the address.
     * @param scope An integer defining the scope in which the address is unique (e.g.,
     *              {@link OsConstants#RT_SCOPE_LINK} or {@link OsConstants#RT_SCOPE_SITE}).
     * @hide
     */
    @SystemApi
    @TestApi
    public LinkAddress(@NonNull InetAddress address, @IntRange(from = 0, to = 128) int prefixLength,
            int flags, int scope) {
        init(address, prefixLength, flags, scope, LIFETIME_UNKNOWN, LIFETIME_UNKNOWN);
    }

    /**
     * Constructs a new {@code LinkAddress} from an {@code InetAddress}, prefix length, with
     * the specified flags, scope, deprecation time, and expiration time. Flags and scope are not
     * checked for validity. The value of the {@code IFA_F_DEPRECATED} and {@code IFA_F_PERMANENT}
     * flag will be adjusted based on the passed-in lifetimes.
     *
     * @param address The IP address.
     * @param prefixLength The prefix length. Must be &gt;= 0 and &lt;= (32 or 128) (IPv4 or IPv6).
     * @param flags A bitmask of {@code IFA_F_*} values representing properties of the address.
     * @param scope An integer defining the scope in which the address is unique (e.g.,
     *              {@link OsConstants#RT_SCOPE_LINK} or {@link OsConstants#RT_SCOPE_SITE}).
     * @param deprecationTime The time, as reported by {@link SystemClock#elapsedRealtime}, when
     *                        this {@link LinkAddress} will be or was deprecated. At the time
     *                        existing connections can still use this address until it expires, but
     *                        new connections should use the new address. {@link #LIFETIME_UNKNOWN}
     *                        indicates this information is not available.
     *                        {@link #LIFETIME_PERMANENT} indicates this {@link LinkAddress} will
     *                        never be deprecated.
     * @param expirationTime The time, as reported by {@link SystemClock#elapsedRealtime}, when this
     *                       {@link LinkAddress} will expire and be removed from the interface.
     *                       {@link #LIFETIME_UNKNOWN} indicates this information is not available.
     *                       {@link #LIFETIME_PERMANENT} indicates this {@link LinkAddress} will
     *                       never expire.
     * @hide
     */
    @SystemApi
    @TestApi
    public LinkAddress(@NonNull InetAddress address, @IntRange(from = 0, to = 128) int prefixLength,
                       int flags, int scope, long deprecationTime, long expirationTime) {
        init(address, prefixLength, flags, scope, deprecationTime, expirationTime);
    }

    /**
     * Constructs a new {@code LinkAddress} from an {@code InetAddress} and a prefix length.
     * The flags are set to zero and the scope is determined from the address.
     * @param address The IP address.
     * @param prefixLength The prefix length. Must be &gt;= 0 and &lt;= (32 or 128) (IPv4 or IPv6).
     * @hide
     */
    @SystemApi
    @TestApi
    public LinkAddress(@NonNull InetAddress address,
            @IntRange(from = 0, to = 128) int prefixLength) {
        this(address, prefixLength, 0, 0);
        this.scope = scopeForUnicastAddress(address);
    }

    /**
     * Constructs a new {@code LinkAddress} from an {@code InterfaceAddress}.
     * The flags are set to zero and the scope is determined from the address.
     * @param interfaceAddress The interface address.
     * @hide
     */
    public LinkAddress(@NonNull InterfaceAddress interfaceAddress) {
        this(interfaceAddress.getAddress(),
             interfaceAddress.getNetworkPrefixLength());
    }

    /**
     * Constructs a new {@code LinkAddress} from a string such as "192.0.2.5/24" or
     * "2001:db8::1/64". The flags are set to zero and the scope is determined from the address.
     * @param address The string to parse.
     * @hide
     */
    @SystemApi
    @TestApi
    public LinkAddress(@NonNull String address) {
        this(address, 0, 0);
        this.scope = scopeForUnicastAddress(this.address);
    }

    /**
     * Constructs a new {@code LinkAddress} from a string such as "192.0.2.5/24" or
     * "2001:db8::1/64", with the specified flags and scope.
     * @param address The string to parse.
     * @param flags The address flags.
     * @param scope The address scope.
     * @hide
     */
    @SystemApi
    @TestApi
    public LinkAddress(@NonNull String address, int flags, int scope) {
        // This may throw an IllegalArgumentException; catching it is the caller's responsibility.
        // TODO: consider rejecting mapped IPv4 addresses such as "::ffff:192.0.2.5/24".
        Pair<InetAddress, Integer> ipAndMask = NetworkUtils.parseIpAndMask(address);
        init(ipAndMask.first, ipAndMask.second, flags, scope, LIFETIME_UNKNOWN, LIFETIME_UNKNOWN);
    }

    /**
     * Returns a string representation of this address, such as "192.0.2.1/24" or "2001:db8::1/64".
     * The string representation does not contain the flags and scope, just the address and prefix
     * length.
     */
    @Override
    public String toString() {
        return address.getHostAddress() + "/" + prefixLength;
    }

    /**
     * Compares this {@code LinkAddress} instance against {@code obj}. Two addresses are equal if
     * their address, prefix length, flags and scope are equal. Thus, for example, two addresses
     * that have the same address and prefix length are not equal if one of them is deprecated and
     * the other is not.
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
        return this.address.equals(linkAddress.address)
                && this.prefixLength == linkAddress.prefixLength
                && this.flags == linkAddress.flags
                && this.scope == linkAddress.scope
                && this.deprecationTime == linkAddress.deprecationTime
                && this.expirationTime == linkAddress.expirationTime;
    }

    /**
     * Returns a hashcode for this address.
     */
    @Override
    public int hashCode() {
        return Objects.hash(address, prefixLength, flags, scope, deprecationTime, expirationTime);
    }

    /**
     * Determines whether this {@code LinkAddress} and the provided {@code LinkAddress}
     * represent the same address. Two {@code LinkAddresses} represent the same address
     * if they have the same IP address and prefix length, even if their properties are
     * different.
     *
     * @param other the {@code LinkAddress} to compare to.
     * @return {@code true} if both objects have the same address and prefix length, {@code false}
     * otherwise.
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isSameAddressAs(@Nullable LinkAddress other) {
        if (other == null) {
            return false;
        }
        return address.equals(other.address) && prefixLength == other.prefixLength;
    }

    /**
     * Returns the {@link InetAddress} of this {@code LinkAddress}.
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Returns the prefix length of this {@code LinkAddress}.
     */
    @IntRange(from = 0, to = 128)
    public int getPrefixLength() {
        return prefixLength;
    }

    /**
     * Returns the prefix length of this {@code LinkAddress}.
     * TODO: Delete all callers and remove in favour of getPrefixLength().
     * @hide
     */
    @UnsupportedAppUsage
    @IntRange(from = 0, to = 128)
    public int getNetworkPrefixLength() {
        return getPrefixLength();
    }

    /**
     * Returns the flags of this {@code LinkAddress}.
     */
    public int getFlags() {
        int flags = this.flags;
        if (deprecationTime != LIFETIME_UNKNOWN) {
            if (SystemClock.elapsedRealtime() >= deprecationTime) {
                flags |= IFA_F_DEPRECATED;
            } else {
                // If deprecation time is in the future, or permanent.
                flags &= ~IFA_F_DEPRECATED;
            }
        }

        if (expirationTime == LIFETIME_PERMANENT) {
            flags |= IFA_F_PERMANENT;
        } else if (expirationTime != LIFETIME_UNKNOWN) {
            // If we know this address expired or will expire in the future, then this address
            // should not be permanent.
            flags &= ~IFA_F_PERMANENT;
        }

        // Do no touch the original flags. Return the adjusted flags here.
        return flags;
    }

    /**
     * Returns the scope of this {@code LinkAddress}.
     */
    public int getScope() {
        return scope;
    }

    /**
     * Get the deprecation time, as reported by {@link SystemClock#elapsedRealtime}, when this
     * {@link LinkAddress} will be or was deprecated. At the time existing connections can still use
     * this address until it expires, but new connections should use the new address.
     *
     * @return The deprecation time in milliseconds. {@link #LIFETIME_UNKNOWN} indicates this
     * information is not available. {@link #LIFETIME_PERMANENT} indicates this {@link LinkAddress}
     * will never be deprecated.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public long getDeprecationTime() {
        return deprecationTime;
    }

    /**
     * Get the expiration time, as reported by {@link SystemClock#elapsedRealtime}, when this
     * {@link LinkAddress} will expire and be removed from the interface.
     *
     * @return The expiration time in milliseconds. {@link #LIFETIME_UNKNOWN} indicates this
     * information is not available. {@link #LIFETIME_PERMANENT} indicates this {@link LinkAddress}
     * will never expire.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public long getExpirationTime() {
        return expirationTime;
    }

    /**
     * Returns true if this {@code LinkAddress} is global scope and preferred (i.e., not currently
     * deprecated).
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isGlobalPreferred() {
        /**
         * Note that addresses flagged as IFA_F_OPTIMISTIC are
         * simultaneously flagged as IFA_F_TENTATIVE (when the tentative
         * state has cleared either DAD has succeeded or failed, and both
         * flags are cleared regardless).
         */
        int flags = getFlags();
        return (scope == RT_SCOPE_UNIVERSE
                && !isIpv6ULA()
                && (flags & (IFA_F_DADFAILED | IFA_F_DEPRECATED)) == 0L
                && ((flags & IFA_F_TENTATIVE) == 0L || (flags & IFA_F_OPTIMISTIC) != 0L));
    }

    /**
     * Implement the Parcelable interface.
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(address.getAddress());
        dest.writeInt(prefixLength);
        dest.writeInt(this.flags);
        dest.writeInt(scope);
        dest.writeLong(deprecationTime);
        dest.writeLong(expirationTime);
    }

    /**
     * Implement the Parcelable interface.
     */
    public static final @android.annotation.NonNull Creator<LinkAddress> CREATOR =
        new Creator<LinkAddress>() {
            public LinkAddress createFromParcel(Parcel in) {
                InetAddress address = null;
                try {
                    address = InetAddress.getByAddress(in.createByteArray());
                } catch (UnknownHostException e) {
                    // Nothing we can do here. When we call the constructor, we'll throw an
                    // IllegalArgumentException, because a LinkAddress can't have a null
                    // InetAddress.
                }
                int prefixLength = in.readInt();
                int flags = in.readInt();
                int scope = in.readInt();
                long deprecationTime = in.readLong();
                long expirationTime = in.readLong();
                return new LinkAddress(address, prefixLength, flags, scope, deprecationTime,
                        expirationTime);
            }

            public LinkAddress[] newArray(int size) {
                return new LinkAddress[size];
            }
        };
}
