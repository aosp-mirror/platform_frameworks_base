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
     * Turn on USB RNDIS support - this will turn off thinks like adb/mass-storage
     */
    void startUsbRNDIS();

    /**
     * Turn off USB RNDIS support
     */
    void stopUsbRNDIS();

    /**
     * Check the status of USB RNDIS support
     */
    boolean isUsbRNDISStarted();

    /**
     * Start Wifi Access Point
     */
    void startAccessPoint(in WifiConfiguration wifiConfig, String wlanIface, String softapIface);

    /**
     * Stop Wifi Access Point
     */
    void stopAccessPoint();

    /**
     * Set Access Point config
     */
    void setAccessPoint(in WifiConfiguration wifiConfig, String wlanIface, String softapIface);

    /**
     * Read number of bytes sent over an interface
     */
    long getInterfaceTxCounter(String iface);

    /**
     * Read number of bytes received over an interface
     */
    long getInterfaceRxCounter(String iface);

    /**
     * Configures bandwidth throttling on an interface
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

}
