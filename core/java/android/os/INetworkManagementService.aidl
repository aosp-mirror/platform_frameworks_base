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
import android.net.ITetheringStatsProvider;
import android.net.Network;
import android.net.NetworkStats;
import android.net.RouteInfo;
import android.net.UidRange;
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
     * Register an observer to receive events.
     */
    @UnsupportedAppUsage
    void registerObserver(INetworkManagementEventObserver obs);

    /**
     * Unregister an observer from receiving events.
     */
    @UnsupportedAppUsage
    void unregisterObserver(INetworkManagementEventObserver obs);

    /**
     * Returns a list of currently known network interfaces
     */
    String[] listInterfaces();

    /**
     * Retrieves the specified interface config
     *
     */
    @UnsupportedAppUsage
    InterfaceConfiguration getInterfaceConfig(String iface);

    /**
     * Sets the configuration of the specified interface
     */
    @UnsupportedAppUsage
    void setInterfaceConfig(String iface, in InterfaceConfiguration cfg);

    /**
     * Clear all IP addresses on the specified interface
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable);

    /**
     * Disable IPv6 on an interface
     */
    @UnsupportedAppUsage
    void disableIpv6(String iface);

    /**
     * Enable IPv6 on an interface
     */
    @UnsupportedAppUsage
    void enableIpv6(String iface);

    /**
     * Set IPv6 autoconf address generation mode.
     * This is a no-op if an unsupported mode is requested.
     */
    @UnsupportedAppUsage
    void setIPv6AddrGenMode(String iface, int mode);

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
    @UnsupportedAppUsage
    boolean getIpForwardingEnabled();

    /**
     * Enables/Disables IP Forwarding
     */
    @UnsupportedAppUsage
    void setIpForwardingEnabled(boolean enabled);

    /**
     * Start tethering services with the specified dhcp server range
     * arg is a set of start end pairs defining the ranges.
     */
    @UnsupportedAppUsage
    void startTethering(in String[] dhcpRanges);

    /**
     * Stop currently running tethering services
     */
    @UnsupportedAppUsage
    void stopTethering();

    /**
     * Returns true if tethering services are started
     */
    @UnsupportedAppUsage
    boolean isTetheringStarted();

    /**
     * Tethers the specified interface
     */
    @UnsupportedAppUsage
    void tetherInterface(String iface);

    /**
     * Untethers the specified interface
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    void enableNat(String internalInterface, String externalInterface);

    /**
     *  Disables Network Address Translation between two interfaces.
     */
    @UnsupportedAppUsage
    void disableNat(String internalInterface, String externalInterface);

    /**
     * Registers a {@code ITetheringStatsProvider} to provide tethering statistics.
     * All registered providers will be called in order, and their results will be added together.
     * Netd is always registered as a tethering stats provider.
     */
    void registerTetheringStatsProvider(ITetheringStatsProvider provider, String name);

    /**
     * Unregisters a previously-registered {@code ITetheringStatsProvider}.
     */
    void unregisterTetheringStatsProvider(ITetheringStatsProvider provider);

    /**
     * Reports that a tethering provider has reached a data limit.
     *
     * Currently triggers a global alert, which causes NetworkStatsService to poll counters and
     * re-evaluate data usage.
     *
     * This does not take an interface name because:
     * 1. The tethering offload stats provider cannot reliably determine the interface on which the
     *    limit was reached, because the HAL does not provide it.
     * 2. Firing an interface-specific alert instead of a global alert isn't really useful since in
     *    all cases of interest, the system responds to both in the same way - it polls stats, and
     *    then notifies NetworkPolicyManagerService of the fact.
     */
    void tetherLimitReached(ITetheringStatsProvider provider);

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
     * Return detailed network statistics for the requested UID and interfaces,
     * including interface and tag details.
     * @param uid UID to obtain statistics for, or {@link NetworkStats#UID_ALL}.
     * @param ifaces Interfaces to obtain statistics for, or {@link NetworkStats#INTERFACES_ALL}.
     */
    NetworkStats getNetworkStatsUidDetail(int uid, in String[] ifaces);

    /**
     * Return summary of network statistics all tethering interfaces.
     */
    NetworkStats getNetworkStatsTethering(int how);

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
    void setUidMeteredNetworkBlacklist(int uid, boolean enable);
    void setUidMeteredNetworkWhitelist(int uid, boolean enable);
    boolean setDataSaverModeEnabled(boolean enable);

    void setUidCleartextNetworkPolicy(int uid, int policy);

    /**
     * Return status of bandwidth control module.
     */
    @UnsupportedAppUsage
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
     * Configure name servers, search paths, and resolver parameters for the given network.
     */
    void setDnsConfigurationForNetwork(int netId, in String[] servers, in String[] domains,
            in int[] params, String tlsHostname, in String[] tlsServers);

    void setFirewallEnabled(boolean enabled);
    boolean isFirewallEnabled();
    void setFirewallInterfaceRule(String iface, boolean allow);
    void setFirewallUidRule(int chain, int uid, int rule);
    void setFirewallUidRules(int chain, in int[] uids, in int[] rules);
    void setFirewallChainEnabled(int chain, boolean enable);

    /**
     * Set all packets from users in ranges to go through VPN specified by netId.
     */
    void addVpnUidRanges(int netId, in UidRange[] ranges);

    /**
     * Clears the special VPN rules for users in ranges and VPN specified by netId.
     */
    void removeVpnUidRanges(int netId, in UidRange[] ranges);

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
     * @param permission PERMISSION_NONE if no permissions required to access this network.
     *                   PERMISSION_NETWORK or PERMISSION_SYSTEM to set respective permission.
     */
    void createPhysicalNetwork(int netId, int permission);

    /**
     * Setup a new VPN.
     */
    void createVirtualNetwork(int netId, boolean secure);

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

    /**
     * Set permission for a network.
     * @param permission PERMISSION_NONE to clear permissions.
     *                   PERMISSION_NETWORK or PERMISSION_SYSTEM to set permission.
     */
    void setNetworkPermission(int netId, int permission);

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
    int removeRoutesFromLocalNetwork(in List<RouteInfo> routes);

    void setAllowOnlyVpnForUids(boolean enable, in UidRange[] uidRanges);

    boolean isNetworkRestricted(int uid);
}
