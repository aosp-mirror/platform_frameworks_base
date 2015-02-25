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
import android.net.Network;
import android.net.NetworkStats;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.wifi.WifiConfiguration;
import android.os.INetworkActivityListener;

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
     * Enables or enables IPv6 ND offload.
     */
    void setInterfaceIpv6NdOffload(String iface, boolean enable);

    /**
     * Retrieves the network routes currently configured on the specified
     * interface
     */
    RouteInfo[] getRoutes(String iface);

    /**
     * Add the specified route to the interface.
     */
    void addRoute(int netId, in RouteInfo route);

    /**
     * Remove the specified route from the interface.
     */
    void removeRoute(int netId, in RouteInfo route);

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
    void setDnsForwarders(in Network network, in String[] dns);

    /**
     * Returns the list of DNS forwarders (in order of priority)
     */
    String[] getDnsForwarders();

    /**
     * Enables unidirectional packet forwarding from {@code fromIface} to
     * {@code toIface}.
     */
    void startInterfaceForwarding(String fromIface, String toIface);

    /**
     * Disables unidirectional packet forwarding from {@code fromIface} to
     * {@code toIface}.
     */
    void stopInterfaceForwarding(String fromIface, String toIface);

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
     * {@code type} is the type of the interface, such as TYPE_MOBILE.
     *
     * Every {@code addIdleTimer} should be paired with a
     * {@link removeIdleTimer} to cleanup when the network disconnects.
     */
    void addIdleTimer(String iface, int timeout, int type);

    /**
     * Removes idletimer for an interface.
     */
    void removeIdleTimer(String iface);

    /**
     * Bind name servers to a network in the DNS resolver.
     */
    void setDnsServersForNetwork(int netId, in String[] servers, String domains);

    /**
     * Flush the DNS cache associated with the specified network.
     */
    void flushNetworkDnsCache(int netId);

    void setFirewallEnabled(boolean enabled);
    boolean isFirewallEnabled();
    void setFirewallInterfaceRule(String iface, boolean allow);
    void setFirewallEgressSourceRule(String addr, boolean allow);
    void setFirewallEgressDestRule(String addr, int port, boolean allow);
    void setFirewallUidRule(int uid, boolean allow);

    /**
     * Set all packets from users in ranges to go through VPN specified by netId.
     */
    void addVpnUidRanges(int netId, in UidRange[] ranges);

    /**
     * Clears the special VPN rules for users in ranges and VPN specified by netId.
     */
    void removeVpnUidRanges(int netId, in UidRange[] ranges);

    /**
     * Start the clatd (464xlat) service on the given interface.
     */
    void startClatd(String interfaceName);

    /**
     * Stop the clatd (464xlat) service on the given interface.
     */
    void stopClatd(String interfaceName);

    /**
     * Determine whether the clatd (464xlat) service has been started on the given interface.
     */
    boolean isClatdStarted(String interfaceName);

    /**
     * Start listening for mobile activity state changes.
     */
    void registerNetworkActivityListener(INetworkActivityListener listener);

    /**
     * Stop listening for mobile activity state changes.
     */
    void unregisterNetworkActivityListener(INetworkActivityListener listener);

    /**
     * Check whether the mobile radio is currently active.
     */
    boolean isNetworkActive();

    /**
     * Setup a new physical network.
     */
    void createPhysicalNetwork(int netId);

    /**
     * Setup a new VPN.
     */
    void createVirtualNetwork(int netId, boolean hasDNS, boolean secure);

    /**
     * Remove a network.
     */
    void removeNetwork(int netId);

    /**
     * Add an interface to a network.
     */
    void addInterfaceToNetwork(String iface, int netId);

    /**
     * Remove an Interface from a network.
     */
    void removeInterfaceFromNetwork(String iface, int netId);

    void addLegacyRouteForNetId(int netId, in RouteInfo routeInfo, int uid);

    void setDefaultNetId(int netId);
    void clearDefaultNetId();

    void setPermission(String permission, in int[] uids);
    void clearPermission(in int[] uids);

    /**
     * Allow UID to call protect().
     */
    void allowProtect(int uid);

    /**
     * Deny UID from calling protect().
     */
    void denyProtect(int uid);

    void addInterfaceToLocalNetwork(String iface, in List<RouteInfo> routes);
    void removeInterfaceFromLocalNetwork(String iface);
}
