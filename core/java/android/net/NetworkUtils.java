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
import java.net.UnknownHostException;

/**
 * Native methods for managing network interfaces.
 *
 * {@hide}
 */
public class NetworkUtils {
    /** Bring the named network interface up. */
    public native static int enableInterface(String interfaceName);

    /** Bring the named network interface down. */
    public native static int disableInterface(String interfaceName);

    /** Add a route to the specified host via the named interface. */
    public static int addHostRoute(String interfaceName, InetAddress hostaddr) {
        int v4Int = v4StringToInt(hostaddr.getHostAddress());
        if (v4Int != 0) {
            return addHostRouteNative(interfaceName, v4Int);
        } else {
            return -1;
        }
    }
    private native static int addHostRouteNative(String interfaceName, int hostaddr);

    /** Add a default route for the named interface. */
    public static int setDefaultRoute(String interfaceName, InetAddress gwayAddr) {
        int v4Int = v4StringToInt(gwayAddr.getHostAddress());
        if (v4Int != 0) {
            return setDefaultRouteNative(interfaceName, v4Int);
        } else {
            return -1;
        }
    }
    private native static int setDefaultRouteNative(String interfaceName, int hostaddr);

    /** Return the gateway address for the default route for the named interface. */
    public static InetAddress getDefaultRoute(String interfaceName) {
        int addr = getDefaultRouteNative(interfaceName);
        try {
            return InetAddress.getByAddress(v4IntToArray(addr));
        } catch (UnknownHostException e) {
            return null;
        }
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
    public native static boolean runDhcp(String interfaceName, DhcpInfo ipInfo);

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
     * When static IP configuration has been specified, configure the network
     * interface according to the values supplied.
     * @param interfaceName the name of the interface to configure
     * @param ipInfo the IP address, default gateway, and DNS server addresses
     * with which to configure the interface.
     * @return {@code true} for success, {@code false} for failure
     */
    public static boolean configureInterface(String interfaceName, DhcpInfo ipInfo) {
        return configureNative(interfaceName,
            ipInfo.ipAddress,
            ipInfo.netmask,
            ipInfo.gateway,
            ipInfo.dns1,
            ipInfo.dns2);
    }

    private native static boolean configureNative(
        String interfaceName, int ipAddress, int netmask, int gateway, int dns1, int dns2);

    // The following two functions are glue to tie the old int-based address scheme
    // to the new InetAddress scheme.  They should go away when we go fully to InetAddress
    // TODO - remove when we switch fully to InetAddress
    public static byte[] v4IntToArray(int addr) {
        byte[] addrBytes = new byte[4];
        addrBytes[0] = (byte)(addr & 0xff);
        addrBytes[1] = (byte)((addr >> 8) & 0xff);
        addrBytes[2] = (byte)((addr >> 16) & 0xff);
        addrBytes[3] = (byte)((addr >> 24) & 0xff);
        return addrBytes;
    }

    public static int v4StringToInt(String str) {
        int result = 0;
        String[] array = str.split("\\.");
        if (array.length != 4) return 0;
        try {
            result = Integer.parseInt(array[3]);
            result = (result << 8) + Integer.parseInt(array[2]);
            result = (result << 8) + Integer.parseInt(array[1]);
            result = (result << 8) + Integer.parseInt(array[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
        return result;
    }
}
