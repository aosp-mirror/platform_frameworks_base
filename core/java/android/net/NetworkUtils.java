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
    public native static int addHostRoute(String interfaceName, int hostaddr);

    /** Add a default route for the named interface. */
    public native static int setDefaultRoute(String interfaceName, int gwayAddr);

    /** Return the gateway address for the default route for the named interface. */
    public native static int getDefaultRoute(String interfaceName);

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

    /**
     * Look up a host name and return the result as an int. Works if the argument
     * is an IP address in dot notation. Obviously, this can only be used for IPv4
     * addresses.
     * @param hostname the name of the host (or the IP address)
     * @return the IP address as an {@code int} in network byte order
     */
    public static int lookupHost(String hostname) {
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(hostname);
        } catch (UnknownHostException e) {
            return -1;
        }
        byte[] addrBytes;
        int addr;
        addrBytes = inetAddress.getAddress();
        addr = ((addrBytes[3] & 0xff) << 24)
                | ((addrBytes[2] & 0xff) << 16)
                | ((addrBytes[1] & 0xff) << 8)
                |  (addrBytes[0] & 0xff);
        return addr;
    }
}
