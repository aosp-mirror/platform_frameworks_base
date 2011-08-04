/* //device/java/android/android/os/INetworkManagementService.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

import android.net.InterfaceConfiguration;
import android.net.INetworkManagementEventObserver;
import android.net.NetworkStats;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;

/**
 * @hide
 */
interface INetworkManagementService
{
    /**
     ** GENERAL
     **/

    /**
     * Register an observer to receive events
     */
    void registerObserver(INetworkManagementEventObserver obs);

    /**
     * Unregister an observer from receiving events.
     */
    void unregisterObserver(INetworkManagementEventObserver obs);

    /**
     * Returns a list of currently known network interfaces
     */
    String[] listInterfaces();

    /**
     * Retrieves the specified interface config
     *
     */
    InterfaceConfiguration getInterfaceConfig(String iface);

    /**
     * Sets the configuration of the specified interface
     */
    void setInterfaceConfig(String iface, in InterfaceConfiguration cfg);

    /**
     * Clear all IP addresses on the specified interface
     */
    void clearInterfaceAddresses(String iface);

    /**
     * Retrieves the network routes currently configured on the specified
     * interface
     */
    RouteInfo[] getRoutes(String iface);

    /**
     * Add the specified route to the interface.
     */
    void addRoute(String iface, in RouteInfo route);

    /**
     * Remove the specified route from the interface.
     */
    void removeRoute(String iface, in RouteInfo route);

    /**
     * Shuts down the service
     */
    void shutdown();

    /**
     ** TETHERING RELATED
     **/

    /**
     * Returns true if IP forwarding is enabled
     */
    boolean getIpForwardingEnabled();

    /**
     * Enables/Disables IP Forwarding
     */
    void setIpForwardingEnabled(boolean enabled);

    /**
     * Start tethering services with the specified dhcp server range
     * arg is a set of start end pairs defining the ranges.
     */
    void startTethering(in String[] dhcpRanges);

    /**
     * Stop currently running tethering services
     */
    void stopTethering();

    /**
     * Returns true if tethering services are started
     */
    boolean isTetheringStarted();

    /**
     * Tethers the specified interface
     */
    void tetherInterface(String iface);

    /**
     * Untethers the specified interface
     */
    void untetherInterface(String iface);

    /**
     * Returns a list of currently tethered interfaces
     */
    String[] listTetheredInterfaces();

    /**
     * Sets the list of DNS forwarders (in order of priority)
     */
    void setDnsForwarders(in String[] dns);

    /**
     * Returns the list of DNS fowarders (in order of priority)
     */
    String[] getDnsForwarders();

    /**
     *  Enables Network Address Translation between two interfaces.
     *  The address and netmask of the external interface is used for
     *  the NAT'ed network.
     */
    void enableNat(String internalInterface, String externalInterface);

    /**
     *  Disables Network Address Translation between two interfaces.
     */
    void disableNat(String internalInterface, String externalInterface);

    /**
     ** PPPD
     **/

    /**
     * Returns the list of currently known TTY devices on the system
     */
    String[] listTtys();

    /**
     * Attaches a PPP server daemon to the specified TTY with the specified
     * local/remote addresses.
     */
    void attachPppd(String tty, String localAddr, String remoteAddr, String dns1Addr,
            String dns2Addr);

    /**
     * Detaches a PPP server daemon from the specified TTY.
     */
    void detachPppd(String tty);

    /**
     * Start Wifi Access Point
     */
    void startAccessPoint(in WifiConfiguration wifiConfig, String wlanIface, String softapIface);

    /**
     * Stop Wifi Access Point
     */
    void stopAccessPoint(String wlanIface);

    /**
     * Set Access Point config
     */
    void setAccessPoint(in WifiConfiguration wifiConfig, String wlanIface, String softapIface);

    /**
     ** DATA USAGE RELATED
     **/

    /**
     * Return global network statistics summarized at an interface level,
     * without any UID-level granularity.
     */
    NetworkStats getNetworkStatsSummary();

    /**
     * Return detailed network statistics with UID-level granularity,
     * including interface and tag details.
     */
    NetworkStats getNetworkStatsDetail();

    /**
     * Return detailed network statistics for the requested UID,
     * including interface and tag details.
     */
    NetworkStats getNetworkStatsUidDetail(int uid);

    /**
     * Set quota for an interface.
     */
    void setInterfaceQuota(String iface, long quotaBytes);

    /**
     * Remove quota for an interface.
     */
    void removeInterfaceQuota(String iface);

    /**
     * Set alert for an interface; requires that iface already has quota.
     */
    void setInterfaceAlert(String iface, long alertBytes);

    /**
     * Remove alert for an interface.
     */
    void removeInterfaceAlert(String iface);

    /**
     * Set alert across all interfaces.
     */
    void setGlobalAlert(long alertBytes);

    /**
     * Control network activity of a UID over interfaces with a quota limit.
     */
    void setUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces);

    /**
     * Return status of bandwidth control module.
     */
    boolean isBandwidthControlEnabled();

    /**
     * Configures bandwidth throttling on an interface.
     */
    void setInterfaceThrottle(String iface, int rxKbps, int txKbps);

    /**
     * Returns the currently configured RX throttle values
     * for the specified interface
     */
    int getInterfaceRxThrottle(String iface);

    /**
     * Returns the currently configured TX throttle values
     * for the specified interface
     */
    int getInterfaceTxThrottle(String iface);

    /**
     * Sets the name of the default interface in the DNS resolver.
     */
    void setDefaultInterfaceForDns(String iface);

    /**
     * Bind name servers to an interface in the DNS resolver.
     */
    void setDnsServersForInterface(String iface, in String[] servers);

    /**
     * Flush the DNS cache associated with the default interface.
     */
    void flushDefaultDnsCache();

    /**
     * Flush the DNS cache associated with the specified interface.
     */
    void flushInterfaceDnsCache(String iface);
}
