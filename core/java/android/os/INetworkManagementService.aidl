/* //device/java/android/android/os/INetworkManagementService.aidl
**
** Copyright 2007, The Android Open Source Project
** Copyright (c) 2010-2013, The Linux Foundation. All rights reserved.
**
** Not a Contribution. Apache license notifications and license are
** retained for attribution purposes only.
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
import android.net.LinkAddress;
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
     * Set interface down
     */
    void setInterfaceDown(String iface);

    /**
     * Set interface up
     */
    void setInterfaceUp(String iface);

    /**
     * Set interface IPv6 privacy extensions
     */
    void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable);

    /**
     * Disable IPv6 on an interface
     */
    void disableIpv6(String iface);

    /**
     * Enable IPv6 on an interface
     */
    void enableIpv6(String iface);

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
     * Add the specified route to a secondary interface
     * This will go into a special route table to be accessed
     * via ip rules
     */
    void addSecondaryRoute(String iface, in RouteInfo route);

    /**
     * Remove the specified secondary route.
     */
    void removeSecondaryRoute(String iface, in RouteInfo route);

    /**
     * Set the specified MTU size
     */
    void setMtu(String iface, int mtu);

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
     * Load firmware for operation in the given mode. Currently the three
     * modes supported are "AP", "STA" and "P2P".
     */
    void wifiFirmwareReload(String wlanIface, String mode);

    /**
     * Start Wifi Access Point
     */
    void startAccessPoint(in WifiConfiguration wifiConfig, String iface);

    /**
     * Stop Wifi Access Point
     */
    void stopAccessPoint(String iface);

    /**
     * Set Access Point config
     */
    void setAccessPoint(in WifiConfiguration wifiConfig, String iface);

    /**
     ** DATA USAGE RELATED
     **/

    /**
     * Return global network statistics summarized at an interface level,
     * without any UID-level granularity.
     */
    NetworkStats getNetworkStatsSummaryDev();
    NetworkStats getNetworkStatsSummaryXt();

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
     * Return summary of network statistics all tethering interfaces.
     */
    NetworkStats getNetworkStatsTethering();

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
     * Sets idletimer for an interface.
     *
     * This either initializes a new idletimer or increases its
     * reference-counting if an idletimer already exists for given
     * {@code iface}.
     *
     * {@code label} usually represents the network type of {@code iface}.
     * Caller should ensure that {@code label} for an {@code iface} remains the
     * same for all calls to addIdleTimer.
     *
     * Every {@code addIdleTimer} should be paired with a
     * {@link removeIdleTimer} to cleanup when the network disconnects.
     */
    void addIdleTimer(String iface, int timeout, String label);

    /**
     * Removes idletimer for an interface.
     */
    void removeIdleTimer(String iface);

    /**
     * Sets the name of the default interface in the DNS resolver.
     */
    void setDefaultInterfaceForDns(String iface);

    /**
     * Bind name servers to an interface in the DNS resolver.
     */
    void setDnsServersForInterface(String iface, in String[] servers, String domains);

    /**
     * Flush the DNS cache associated with the default interface.
     */
    void flushDefaultDnsCache();

    /**
     * Flush the DNS cache associated with the specified interface.
     */
    void flushInterfaceDnsCache(String iface);

    void setFirewallEnabled(boolean enabled);
    boolean isFirewallEnabled();
    void setFirewallInterfaceRule(String iface, boolean allow);
    void setFirewallEgressSourceRule(String addr, boolean allow);
    void setFirewallEgressDestRule(String addr, int port, boolean allow);
    void setFirewallUidRule(int uid, boolean allow);

    /**
     * Set all packets from users [uid_start,uid_end] to go through interface iface
     * iface must already be set for marked forwarding by {@link setMarkedForwarding}
     */
    void setUidRangeRoute(String iface, int uid_start, int uid_end);

    /**
     * Clears the special routing rules for users [uid_start,uid_end]
     */
    void clearUidRangeRoute(String iface, int uid_start, int uid_end);

    /**
     * Setup an interface for routing packets marked by {@link setUidRangeRoute}
     *
     * This sets up a dedicated routing table for packets marked for {@code iface} and adds
     * source-NAT rules so that the marked packets have the correct source address.
     */
    void setMarkedForwarding(String iface);

    /**
     * Removes marked forwarding for an interface
     */
    void clearMarkedForwarding(String iface);

    /**
     * Get the SO_MARK associated with routing packets for user {@code uid}
     */
    int getMarkForUid(int uid);

    /**
     * Get the SO_MARK associated with protecting packets from VPN routing rules
     */
    int getMarkForProtect();

    /**
     * Route all traffic in {@code route} to {@code iface} setup for marked forwarding
     */
    void setMarkedForwardingRoute(String iface, in RouteInfo route);

    /**
     * Clear routes set by {@link setMarkedForwardingRoute}
     */
    void clearMarkedForwardingRoute(String iface, in RouteInfo route);

    /**
     * Exempts {@code host} from the routing set up by {@link setMarkedForwardingRoute}
     * All connects to {@code host} will use the global routing table
     */
    void setHostExemption(in LinkAddress host);

    /**
     * Clears an exemption set by {@link setHostExemption}
     */
    void clearHostExemption(in LinkAddress host);

    /**
     * Set a process (pid) to use the name servers associated with the specified interface.
     */
    void setDnsInterfaceForPid(String iface, int pid);

    /**
     * Clear a process (pid) from being associated with an interface.
     */
    void clearDnsInterfaceForPid(int pid);

    /**
    * Set a range of user ids to use the name servers associated with the specified interface.
    */
    void setDnsInterfaceForUidRange(String iface, int uid_start, int uid_end);

    /**
    * Clear a user range from being associated with an interface.
    */
    void clearDnsInterfaceForUidRange(int uid_start, int uid_end);

    /**
    * Clear the mappings from pid to Dns interface and from uid range to Dns interface.
    */
    void clearDnsInterfaceMaps();

    /**
     * Start the clatd (464xlat) service
     */
    void startClatd(String interfaceName);

    /**
     * Stop the clatd (464xlat) service
     */
    void stopClatd();

    /**
     * Determine whether the clatd (464xlat) service has been started
     */
    boolean isClatdStarted();

   /**
    ** Policy Routing
    **/

   /**
    * Replaces a prexisting identical route with the new metric specified.
    * Adds a new route if none existed before.
    */
   boolean addRouteWithMetric(String iface, int metric, in RouteInfo route);

   /**
    * Replaces a source policy route for the given iface in a custom routing
    * table denoted by routeId, if it already exists.
    * Adds a new route if it did not exist.
    */
   boolean replaceSrcRoute(String iface, in byte[] ip, in byte[] gateway, int routeId);

   /**
    * Deletes a source policy route for the given route identifier and source
    * address from a custom routing table denoted by routeId
    */
   boolean delSrcRoute(in byte[] ip, int routeId);
}
