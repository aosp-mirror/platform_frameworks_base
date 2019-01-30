/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.ip;

import android.net.INetd;
import android.net.InterfaceConfigurationParcel;
import android.net.LinkAddress;
import android.net.util.SharedLog;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.InetAddress;


/**
 * Encapsulates the multiple IP configuration operations performed on an interface.
 *
 * TODO: refactor/eliminate the redundant ways to set and clear addresses.
 *
 * @hide
 */
public class InterfaceController {
    private final static boolean DBG = false;

    private final String mIfName;
    private final INetd mNetd;
    private final SharedLog mLog;

    public InterfaceController(String ifname, INetd netd, SharedLog log) {
        mIfName = ifname;
        mNetd = netd;
        mLog = log;
    }

    private boolean setInterfaceAddress(LinkAddress addr) {
        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = mIfName;
        ifConfig.ipv4Addr = addr.getAddress().getHostAddress();
        ifConfig.prefixLength = addr.getPrefixLength();
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[0];
        try {
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            logError("Setting IPv4 address to %s/%d failed: %s",
                    ifConfig.ipv4Addr, ifConfig.prefixLength, e);
            return false;
        }
        return true;
    }

    /**
     * Set the IPv4 address of the interface.
     */
    public boolean setIPv4Address(LinkAddress address) {
        if (!(address.getAddress() instanceof Inet4Address)) {
            return false;
        }
        return setInterfaceAddress(address);
    }

    /**
     * Clear the IPv4Address of the interface.
     */
    public boolean clearIPv4Address() {
        return setInterfaceAddress(new LinkAddress("0.0.0.0/0"));
    }

    private boolean setEnableIPv6(boolean enabled) {
        try {
            mNetd.interfaceSetEnableIPv6(mIfName, enabled);
        } catch (RemoteException | ServiceSpecificException e) {
            logError("%s IPv6 failed: %s", (enabled ? "enabling" : "disabling"), e);
            return false;
        }
        return true;
    }

    /**
     * Enable IPv6 on the interface.
     */
    public boolean enableIPv6() {
        return setEnableIPv6(true);
    }

    /**
     * Disable IPv6 on the interface.
     */
    public boolean disableIPv6() {
        return setEnableIPv6(false);
    }

    /**
     * Enable or disable IPv6 privacy extensions on the interface.
     * @param enabled Whether the extensions should be enabled.
     */
    public boolean setIPv6PrivacyExtensions(boolean enabled) {
        try {
            mNetd.interfaceSetIPv6PrivacyExtensions(mIfName, enabled);
        } catch (RemoteException | ServiceSpecificException e) {
            logError("error %s IPv6 privacy extensions: %s",
                    (enabled ? "enabling" : "disabling"), e);
            return false;
        }
        return true;
    }

    /**
     * Set IPv6 address generation mode on the interface.
     *
     * <p>IPv6 should be disabled before changing the mode.
     */
    public boolean setIPv6AddrGenModeIfSupported(int mode) {
        try {
            mNetd.setIPv6AddrGenMode(mIfName, mode);
        } catch (RemoteException e) {
            logError("Unable to set IPv6 addrgen mode: %s", e);
            return false;
        } catch (ServiceSpecificException e) {
            if (e.errorCode != OsConstants.EOPNOTSUPP) {
                logError("Unable to set IPv6 addrgen mode: %s", e);
                return false;
            }
        }
        return true;
    }

    /**
     * Add an address to the interface.
     */
    public boolean addAddress(LinkAddress addr) {
        return addAddress(addr.getAddress(), addr.getPrefixLength());
    }

    /**
     * Add an address to the interface.
     */
    public boolean addAddress(InetAddress ip, int prefixLen) {
        try {
            mNetd.interfaceAddAddress(mIfName, ip.getHostAddress(), prefixLen);
        } catch (ServiceSpecificException | RemoteException e) {
            logError("failed to add %s/%d: %s", ip, prefixLen, e);
            return false;
        }
        return true;
    }

    /**
     * Remove an address from the interface.
     */
    public boolean removeAddress(InetAddress ip, int prefixLen) {
        try {
            mNetd.interfaceDelAddress(mIfName, ip.getHostAddress(), prefixLen);
        } catch (ServiceSpecificException | RemoteException e) {
            logError("failed to remove %s/%d: %s", ip, prefixLen, e);
            return false;
        }
        return true;
    }

    /**
     * Remove all addresses from the interface.
     */
    public boolean clearAllAddresses() {
        try {
            mNetd.interfaceClearAddrs(mIfName);
        } catch (Exception e) {
            logError("Failed to clear addresses: %s", e);
            return false;
        }
        return true;
    }

    private void logError(String fmt, Object... args) {
        mLog.e(String.format(fmt, args));
    }
}
