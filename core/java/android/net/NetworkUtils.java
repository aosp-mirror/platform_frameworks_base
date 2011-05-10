/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;

import android.util.Log;

/**
 * Native methods for managing network interfaces.
 *
 * {@hide}
 */
public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    /** Bring the named network interface up. */
    public native static int enableInterface(String interfaceName);

    /** Bring the named network interface down. */
    public native static int disableInterface(String interfaceName);

    /**
     * Add a route to the routing table.
     *
     * @param interfaceName the interface to route through.
     * @param dst the network or host to route to. May be IPv4 or IPv6, e.g.
     * "0.0.0.0" or "2001:4860::".
     * @param prefixLength the prefix length of the route.
     * @param gw the gateway to use, e.g., "192.168.251.1". If null,
     * indicates a directly-connected route.
     */
    public native static int addRoute(String interfaceName, String dst,
          int prefixLength, String gw);

    /** Return the gateway address for the default route for the named interface. */
    public static InetAddress getDefaultRoute(String interfaceName) {
        int addr = getDefaultRouteNative(interfaceName);
        return intToInetAddress(addr);
    }
    private native static int getDefaultRouteNative(String interfaceName);

    /** Remove host routes that uses the named interface. */
    public native static int removeHostRoutes(String interfaceName);

    /** Remove the default route for the named interface. */
    public native static int removeDefaultRoute(String interfaceName);

    /** Reset any sockets that are connected via the named interface. */
    public native static int resetConnections(String interfaceName);

    /**
     * Start the DHCP client daemon, in order to have it request addresses
     * for the named interface, and then configure the interface with those
     * addresses. This call blocks until it obtains a result (either success
     * or failure) from the daemon.
     * @param interfaceName the name of the interface to configure
     * @param ipInfo if the request succeeds, this object is filled in with
     * the IP address information.
     * @return {@code true} for success, {@code false} for failure
     */
    public native static boolean runDhcp(String interfaceName, DhcpInfoInternal ipInfo);

    /**
     * Initiate renewal on the Dhcp client daemon. This call blocks until it obtains
     * a result (either success or failure) from the daemon.
     * @param interfaceName the name of the interface to configure
     * @param ipInfo if the request succeeds, this object is filled in with
     * the IP address information.
     * @return {@code true} for success, {@code false} for failure
     */
    public native static boolean runDhcpRenew(String interfaceName, DhcpInfoInternal ipInfo);

    /**
     * Shut down the DHCP client daemon.
     * @param interfaceName the name of the interface for which the daemon
     * should be stopped
     * @return {@code true} for success, {@code false} for failure
     */
    public native static boolean stopDhcp(String interfaceName);

    /**
     * Release the current DHCP lease.
     * @param interfaceName the name of the interface for which the lease should
     * be released
     * @return {@code true} for success, {@code false} for failure
     */
    public native static boolean releaseDhcpLease(String interfaceName);

    /**
     * Return the last DHCP-related error message that was recorded.
     * <p/>NOTE: This string is not localized, but currently it is only
     * used in logging.
     * @return the most recent error message, if any
     */
    public native static String getDhcpError();

    /**
     * Convert a IPv4 address from an integer to an InetAddress.
     * @param hostAddress an int corresponding to the IPv4 address in network byte order
     */
    public static InetAddress intToInetAddress(int hostAddress) {
        byte[] addressBytes = { (byte)(0xff & hostAddress),
                                (byte)(0xff & (hostAddress >> 8)),
                                (byte)(0xff & (hostAddress >> 16)),
                                (byte)(0xff & (hostAddress >> 24)) };

        try {
           return InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
           throw new AssertionError();
        }
    }

    /**
     * Convert a IPv4 address from an InetAddress to an integer
     * @param inetAddr is an InetAddress corresponding to the IPv4 address
     * @return the IP address as an integer in network byte order
     */
    public static int inetAddressToInt(InetAddress inetAddr)
            throws IllegalArgumentException {
        byte [] addr = inetAddr.getAddress();
        if (addr.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address");
        }
        return ((addr[3] & 0xff) << 24) | ((addr[2] & 0xff) << 16) |
                ((addr[1] & 0xff) << 8) | (addr[0] & 0xff);
    }

    /**
     * Convert a network prefix length to an IPv4 netmask integer
     * @param prefixLength
     * @return the IPv4 netmask as an integer in network byte order
     */
    public static int prefixLengthToNetmaskInt(int prefixLength)
            throws IllegalArgumentException {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Invalid prefix length (0 <= prefix <= 32)");
        }
        int value = 0xffffffff << (32 - prefixLength);
        return Integer.reverseBytes(value);
    }

    /**
     * Create an InetAddress from a string where the string must be a standard
     * representation of a V4 or V6 address.  Avoids doing a DNS lookup on failure
     * but it will throw an IllegalArgumentException in that case.
     * @param addrString
     * @return the InetAddress
     * @hide
     */
    public static InetAddress numericToInetAddress(String addrString)
            throws IllegalArgumentException {
        return InetAddress.parseNumericAddress(addrString);
    }

    /**
     * Add a default route through the specified gateway.
     * @param interfaceName interface on which the route should be added
     * @param gw the IP address of the gateway to which the route is desired,
     * @return {@code true} on success, {@code false} on failure
     */
    public static boolean addDefaultRoute(String interfaceName, InetAddress gw) {
        String dstStr;
        String gwStr = gw.getHostAddress();

        if (gw instanceof Inet4Address) {
            dstStr = "0.0.0.0";
        } else if (gw instanceof Inet6Address) {
            dstStr = "::";
        } else {
            Log.w(TAG, "addDefaultRoute failure: address is neither IPv4 nor IPv6" +
                       "(" + gwStr + ")");
            return false;
        }
        return addRoute(interfaceName, dstStr, 0, gwStr) == 0;
    }

    /**
     * Add a host route.
     * @param interfaceName interface on which the route should be added
     * @param dst the IP address of the host to which the route is desired,
     * this should not be null.
     * @param gw the IP address of the gateway to which the route is desired,
     * if null, indicates a directly-connected route.
     * @return {@code true} on success, {@code false} on failure
     */
    public static boolean addHostRoute(String interfaceName, InetAddress dst,
          InetAddress gw) {
        if (dst == null) {
            Log.w(TAG, "addHostRoute: dst should not be null");
            return false;
        }

        int prefixLength;
        String dstStr = dst.getHostAddress();
        String gwStr = (gw != null) ? gw.getHostAddress() : null;

        if (dst instanceof Inet4Address) {
            prefixLength = 32;
        } else if (dst instanceof Inet6Address) {
            prefixLength = 128;
        } else {
            Log.w(TAG, "addHostRoute failure: address is neither IPv4 nor IPv6" +
                       "(" + dst + ")");
            return false;
        }
        return addRoute(interfaceName, dstStr, prefixLength, gwStr) == 0;
    }

    /**
     * Get InetAddress masked with prefixLength.  Will never return null.
     * @param IP address which will be masked with specified prefixLength
     * @param prefixLength the prefixLength used to mask the IP
     */
    public static InetAddress getNetworkPart(InetAddress address, int prefixLength) {
        if (address == null) {
            throw new RuntimeException("getNetworkPart doesn't accept null address");
        }

        byte[] array = address.getAddress();

        if (prefixLength < 0 || prefixLength > array.length * 8) {
            throw new RuntimeException("getNetworkPart - bad prefixLength");
        }

        int offset = prefixLength / 8;
        int reminder = prefixLength % 8;
        byte mask = (byte)(0xFF << (8 - reminder));

        if (offset < array.length) array[offset] = (byte)(array[offset] & mask);

        offset++;

        for (; offset < array.length; offset++) {
            array[offset] = 0;
        }

        InetAddress netPart = null;
        try {
            netPart = InetAddress.getByAddress(array);
        } catch (UnknownHostException e) {
            throw new RuntimeException("getNetworkPart error - " + e.toString());
        }
        return netPart;
    }

    /**
     * Check if IP address type is consistent between two InetAddress.
     * @return true if both are the same type.  False otherwise.
     */
    public static boolean addressTypeMatches(InetAddress left, InetAddress right) {
        return (((left instanceof Inet4Address) && (right instanceof Inet4Address)) ||
                ((left instanceof Inet6Address) && (right instanceof Inet6Address)));
    }
}
