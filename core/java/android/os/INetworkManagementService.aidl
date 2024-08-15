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
     * Shuts down the service
     */
    @EnforcePermission("SHUTDOWN")
    void shutdown();

    /**
     ** DATA USAGE RELATED
     **/

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
     * Control network activity of a UID over interfaces with a quota limit.
     */
    void setUidOnMeteredNetworkDenylist(int uid, boolean enable);
    void setUidOnMeteredNetworkAllowlist(int uid, boolean enable);
    @EnforcePermission("NETWORK_SETTINGS")
    boolean setDataSaverModeEnabled(boolean enable);

    void setUidCleartextNetworkPolicy(int uid, int policy);

    /**
     * Return status of bandwidth control module.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isBandwidthControlEnabled();

    void setFirewallEnabled(boolean enabled);
    boolean isFirewallEnabled();
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

    @EnforcePermission("OBSERVE_NETWORK_POLICY")
    boolean isNetworkRestricted(int uid);
}
