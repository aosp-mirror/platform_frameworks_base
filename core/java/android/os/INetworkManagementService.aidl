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
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable);

    /**
     * Disable IPv6 on an interface
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void disableIpv6(String iface);

    /**
     * Enable IPv6 on an interface
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void enableIpv6(String iface);

    /**
     * Set IPv6 autoconf address generation mode.
     * This is a no-op if an unsupported mode is requested.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
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
     * Start tethering services with the specified dhcp server range and
     * DNS proxy config.
     * {@code boolean} is used to control legacy DNS proxy server.
     * {@code String[]} is a set of start end pairs defining the ranges.
     */
    void startTetheringWithConfiguration(boolean usingLegacyDnsProxy, in String[] dhcpRanges);

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
    @UnsupportedAppUsage
    String[] listTetheredInterfaces();

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
    void setUidOnMeteredNetworkDenylist(int uid, boolean enable);
    void setUidOnMeteredNetworkAllowlist(int uid, boolean enable);
    boolean setDataSaverModeEnabled(boolean enable);

    void setUidCleartextNetworkPolicy(int uid, int policy);

    /**
     * Return status of bandwidth control module.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isBandwidthControlEnabled();

    void setFirewallEnabled(boolean enabled);
    boolean isFirewallEnabled();
    void setFirewallInterfaceRule(String iface, boolean allow);
    void setFirewallUidRule(int chain, int uid, int rule);
    void setFirewallUidRules(int chain, in int[] uids, in int[] rules);
    void setFirewallChainEnabled(int chain, boolean enable);

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

    boolean isNetworkRestricted(int uid);
}
