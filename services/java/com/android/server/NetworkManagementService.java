/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.InterfaceConfiguration;
import android.net.INetworkManagementEventObserver;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import android.provider.Settings;
import android.content.ContentResolver;
import android.database.ContentObserver;

import java.io.File;
import java.io.FileReader;
import java.lang.IllegalStateException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @hide
 */
class NetworkManagementService extends INetworkManagementService.Stub {

    private static final String TAG = "NetworkManagmentService";

    class NetdResponseCode {
        public static final int InterfaceListResult       = 110;
        public static final int TetherInterfaceListResult = 111;
        public static final int TetherDnsFwdTgtListResult = 112;
        public static final int TtyListResult             = 113;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
        public static final int InterfaceGetCfgResult     = 213;
        public static final int SoftapStatusResult        = 214;
        public static final int UsbRNDISStatusResult      = 215;
        public static final int InterfaceRxCounterResult  = 216;
        public static final int InterfaceTxCounterResult  = 217;
        public static final int InterfaceRxThrottleResult = 218;
        public static final int InterfaceTxThrottleResult = 219;

        public static final int InterfaceChange           = 600;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    private ArrayList<INetworkManagementEventObserver> mObservers;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    public NetworkManagementService(Context context) {
        mContext = context;

        mObservers = new ArrayList<INetworkManagementEventObserver>();

        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            return;
        }

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), "netd", 10, "NetdConnector");
        Thread thread = new Thread(mConnector, NativeDaemonConnector.class.getName());
        thread.start();
    }

    public void registerObserver(INetworkManagementEventObserver obs) {
        Slog.d(TAG, "Registering observer");
        mObservers.add(obs);
    }

    public void unregisterObserver(INetworkManagementEventObserver obs) {
        Slog.d(TAG, "Unregistering observer");
        mObservers.remove(mObservers.indexOf(obs));
    }

    /**
     * Notify our observers of an interface link status change
     */
    private void notifyInterfaceLinkStatusChanged(String iface, boolean link) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceLinkStatusChanged(iface, link);
            } catch (Exception ex) {
                Slog.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Notify our observers of an interface addition.
     */
    private void notifyInterfaceAdded(String iface) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceAdded(iface);
            } catch (Exception ex) {
                Slog.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Notify our observers of an interface removal.
     */
    private void notifyInterfaceRemoved(String iface) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceRemoved(iface);
            } catch (Exception ex) {
                Slog.w(TAG, "Observer notifier failed", ex);
            }
        }
    }


    //
    // Netd Callback handling
    //

    class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            new Thread() {
                public void run() {
                }
            }.start();
        }
        public boolean onEvent(int code, String raw, String[] cooked) {
            if (code == NetdResponseCode.InterfaceChange) {
                /*
                 * a network interface change occured
                 * Format: "NNN Iface added <name>"
                 *         "NNN Iface removed <name>"
                 *         "NNN Iface changed <name> <up/down>"
                 */
                if (cooked.length < 4 || !cooked[1].equals("Iface")) {
                    throw new IllegalStateException(
                            String.format("Invalid event from daemon (%s)", raw));
                }
                if (cooked[2].equals("added")) {
                    notifyInterfaceAdded(cooked[3]);
                    return true;
                } else if (cooked[2].equals("removed")) {
                    notifyInterfaceRemoved(cooked[3]);
                    return true;
                } else if (cooked[2].equals("changed") && cooked.length == 5) {
                    notifyInterfaceLinkStatusChanged(cooked[3], cooked[4].equals("up"));
                    return true;
                }
                throw new IllegalStateException(
                        String.format("Invalid event from daemon (%s)", raw));
            }
            return false;
        }
    }

    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }

            int a = Integer.parseInt(parts[0])      ;
            int b = Integer.parseInt(parts[1]) <<  8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;

            return a | b | c | d;
        } catch (NumberFormatException ex) {
            throw new UnknownHostException(addrString);
        }
    }

    public static String intToIpString(int i) {
        return ((i >> 24 ) & 0xFF) + "." + ((i >> 16 ) & 0xFF) + "." + ((i >>  8 ) & 0xFF) + "." +
               (i & 0xFF);
    }

    //
    // INetworkManagementService members
    //

    public String[] listInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        try {
            return mConnector.doListCommand("interface list", NetdResponseCode.InterfaceListResult);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Cannot communicate with native daemon to list interfaces");
        }
    }

    public InterfaceConfiguration getInterfaceConfig(String iface) throws IllegalStateException {
        String rsp;
        try {
            rsp = mConnector.doCommand("interface getcfg " + iface).get(0);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Cannot communicate with native daemon to get interface config");
        }
        Slog.d(TAG, String.format("rsp <%s>", rsp));

        // Rsp: 213 xx:xx:xx:xx:xx:xx yyy.yyy.yyy.yyy zzz.zzz.zzz.zzz [flag1 flag2 flag3]
        StringTokenizer st = new StringTokenizer(rsp);

        InterfaceConfiguration cfg;
        try {
            try {
                int code = Integer.parseInt(st.nextToken(" "));
                if (code != NetdResponseCode.InterfaceGetCfgResult) {
                    throw new IllegalStateException(
                        String.format("Expected code %d, but got %d",
                                NetdResponseCode.InterfaceGetCfgResult, code));
                }
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException(
                        String.format("Invalid response from daemon (%s)", rsp));
            }

            cfg = new InterfaceConfiguration();
            cfg.hwAddr = st.nextToken(" ");
            try {
                cfg.ipAddr = stringToIpAddr(st.nextToken(" "));
            } catch (UnknownHostException uhe) {
                Slog.e(TAG, "Failed to parse ipaddr", uhe);
                cfg.ipAddr = 0;
            }

            try {
                cfg.netmask = stringToIpAddr(st.nextToken(" "));
            } catch (UnknownHostException uhe) {
                Slog.e(TAG, "Failed to parse netmask", uhe);
                cfg.netmask = 0;
            }
            cfg.interfaceFlags = st.nextToken("]").trim() +"]";
        } catch (NoSuchElementException nsee) {
            throw new IllegalStateException(
                    String.format("Invalid response from daemon (%s)", rsp));
        }
        Slog.d(TAG, String.format("flags <%s>", cfg.interfaceFlags));
        return cfg;
    }

    public void setInterfaceConfig(
            String iface, InterfaceConfiguration cfg) throws IllegalStateException {
        String cmd = String.format("interface setcfg %s %s %s %s", iface,
                intToIpString(cfg.ipAddr), intToIpString(cfg.netmask), cfg.interfaceFlags);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon to interface setcfg");
        }
    }

    public void shutdown() {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires SHUTDOWN permission");
        }

        Slog.d(TAG, "Shutting down");
    }

    public boolean getIpForwardingEnabled() throws IllegalStateException{
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp;
        try {
            rsp = mConnector.doCommand("ipfwd status");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon to ipfwd status");
        }

        for (String line : rsp) {
            String[] tok = line.split(" ");
            if (tok.length < 3) {
                Slog.e(TAG, "Malformed response from native daemon: " + line);
                return false;
            }

            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.IpFwdStatusResult) {
                // 211 Forwarding <enabled/disabled>
                return "enabled".equals(tok[2]);
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void setIpForwardingEnabled(boolean enable) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("ipfwd %sable", (enable ? "en" : "dis")));
    }

    public void startTethering(String[] dhcpRange)
             throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        // cmd is "tether start first_start first_stop second_start second_stop ..."
        // an odd number of addrs will fail
        String cmd = "tether start";
        for (String d : dhcpRange) {
            cmd += " " + d;
        }

        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Unable to communicate to native daemon");
        }
    }

    public void stopTethering() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("tether stop");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Unable to communicate to native daemon to stop tether");
        }
    }

    public boolean isTetheringStarted() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp;
        try {
            rsp = mConnector.doCommand("tether status");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon to get tether status");
        }

        for (String line : rsp) {
            String[] tok = line.split(" ");
            if (tok.length < 3) {
                throw new IllegalStateException("Malformed response for tether status: " + line);
            }
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.TetherStatusResult) {
                // XXX: Tethering services <started/stopped> <TBD>...
                return "started".equals(tok[2]);
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void tetherInterface(String iface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("tether interface add " + iface);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for adding tether interface");
        }
    }

    public void untetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("tether interface remove " + iface);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for removing tether interface");
        }
    }

    public String[] listTetheredInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        try {
            return mConnector.doListCommand(
                    "tether interface list", NetdResponseCode.TetherInterfaceListResult);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for listing tether interfaces");
        }
    }

    public void setDnsForwarders(String[] dns) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "tether dns set";
            for (String s : dns) {
                cmd += " " + InetAddress.getByName(s).getHostAddress();
            }
            try {
                mConnector.doCommand(cmd);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException(
                        "Unable to communicate to native daemon for setting tether dns");
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Error resolving dns name", e);
        }
    }

    public String[] getDnsForwarders() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        try {
            return mConnector.doListCommand(
                    "tether dns list", NetdResponseCode.TetherDnsFwdTgtListResult);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for listing tether dns");
        }
    }

    public void enableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand(
                    String.format("nat enable %s %s", internalInterface, externalInterface));
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for enabling NAT interface");
        }
    }

    public void disableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand(
                    String.format("nat disable %s %s", internalInterface, externalInterface));
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for disabling NAT interface");
        }
    }

    public String[] listTtys() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        try {
            return mConnector.doListCommand("list_ttys", NetdResponseCode.TtyListResult);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate to native daemon for listing TTYs");
        }
    }

    public void attachPppd(String tty, String localAddr, String remoteAddr, String dns1Addr,
            String dns2Addr) throws IllegalStateException {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
            mConnector.doCommand(String.format("pppd attach %s %s %s %s %s", tty,
                    InetAddress.getByName(localAddr).getHostAddress(),
                    InetAddress.getByName(remoteAddr).getHostAddress(),
                    InetAddress.getByName(dns1Addr).getHostAddress(),
                    InetAddress.getByName(dns2Addr).getHostAddress()));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Error resolving addr", e);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon to attach pppd", e);
        }
    }

    public void detachPppd(String tty) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand(String.format("pppd detach %s", tty));
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon to detach pppd", e);
        }
    }

    public void startUsbRNDIS() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("usb startrndis");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating to native daemon for starting RNDIS", e);
        }
    }

    public void stopUsbRNDIS() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("usb stoprndis");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon", e);
        }
    }

    public boolean isUsbRNDISStarted() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        ArrayList<String> rsp;
        try {
            rsp = mConnector.doCommand("usb rndisstatus");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating to native daemon to check RNDIS status", e);
        }

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.UsbRNDISStatusResult) {
                if (tok[3].equals("started"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void startAccessPoint(WifiConfiguration wifiConfig, String wlanIface, String softapIface)
             throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand(String.format("softap stop " + wlanIface));
            mConnector.doCommand(String.format("softap fwreload " + wlanIface + " AP"));
            mConnector.doCommand(String.format("softap start " + wlanIface));
            if (wifiConfig == null) {
                mConnector.doCommand(String.format("softap set " + wlanIface + " " + softapIface));
            } else {
                /**
                 * softap set arg1 arg2 arg3 [arg4 arg5 arg6 arg7 arg8]
                 * argv1 - wlan interface
                 * argv2 - softap interface
                 * argv3 - SSID
                 * argv4 - Security
                 * argv5 - Key
                 * argv6 - Channel
                 * argv7 - Preamble
                 * argv8 - Max SCB
                 */
                String str = String.format("softap set " + wlanIface + " " + softapIface +
                                           " %s %s %s", convertQuotedString(wifiConfig.SSID),
                                           wifiConfig.allowedKeyManagement.get(KeyMgmt.WPA_PSK) ?
                                           "wpa2-psk" : "open",
                                           convertQuotedString(wifiConfig.preSharedKey));
                mConnector.doCommand(str);
            }
            mConnector.doCommand(String.format("softap startap"));
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon to start softap", e);
        }
    }

    private String convertQuotedString(String s) {
        if (s == null) {
            return s;
        }
        /* Replace \ with \\, then " with \" and add quotes at end */
        return '"' + s.replaceAll("\\\\","\\\\\\\\").replaceAll("\"","\\\\\"") + '"';
    }

    public void stopAccessPoint() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("softap stopap");
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon to stop soft AP",
                    e);
        }
    }

    public void setAccessPoint(WifiConfiguration wifiConfig, String wlanIface, String softapIface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
            android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");
        try {
            if (wifiConfig == null) {
                mConnector.doCommand(String.format("softap set " + wlanIface + " " + softapIface));
            } else {
                String str = String.format("softap set " + wlanIface + " " + softapIface
                        + " %s %s %s", convertQuotedString(wifiConfig.SSID),
                        wifiConfig.allowedKeyManagement.get(KeyMgmt.WPA_PSK) ? "wpa2-psk" : "open",
                        convertQuotedString(wifiConfig.preSharedKey));
                mConnector.doCommand(str);
            }
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon to set soft AP",
                    e);
        }
    }

    private long getInterfaceCounter(String iface, boolean rx) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        try {
            String rsp;
            try {
                rsp = mConnector.doCommand(
                        String.format("interface read%scounter %s", (rx ? "rx" : "tx"), iface)).get(0);
            } catch (NativeDaemonConnectorException e1) {
                Slog.e(TAG, "Error communicating with native daemon", e1);
                return -1;
            }

            String[] tok = rsp.split(" ");
            if (tok.length < 2) {
                Slog.e(TAG, String.format("Malformed response for reading %s interface",
                        (rx ? "rx" : "tx")));
                return -1;
            }

            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, String.format("Error parsing code %s", tok[0]));
                return -1;
            }
            if ((rx && code != NetdResponseCode.InterfaceRxCounterResult) || (
                    !rx && code != NetdResponseCode.InterfaceTxCounterResult)) {
                Slog.e(TAG, String.format("Unexpected response code %d", code));
                return -1;
            }
            return Long.parseLong(tok[1]);
        } catch (Exception e) {
            Slog.e(TAG, String.format(
                    "Failed to read interface %s counters", (rx ? "rx" : "tx")), e);
        }
        return -1;
    }

    public long getInterfaceRxCounter(String iface) {
        return getInterfaceCounter(iface, true);
    }

    public long getInterfaceTxCounter(String iface) {
        return getInterfaceCounter(iface, false);
    }

    public void setInterfaceThrottle(String iface, int rxKbps, int txKbps) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand(String.format(
                    "interface setthrottle %s %d %d", iface, rxKbps, txKbps));
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Error communicating with native daemon to set throttle", e);
        }
    }

    private int getInterfaceThrottle(String iface, boolean rx) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        try {
            String rsp;
            try {
                rsp = mConnector.doCommand(
                        String.format("interface getthrottle %s %s", iface,
                                (rx ? "rx" : "tx"))).get(0);
            } catch (NativeDaemonConnectorException e) {
                Slog.e(TAG, "Error communicating with native daemon to getthrottle", e);
                return -1;
            }

            String[] tok = rsp.split(" ");
            if (tok.length < 2) {
                Slog.e(TAG, "Malformed response to getthrottle command");
                return -1;
            }

            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, String.format("Error parsing code %s", tok[0]));
                return -1;
            }
            if ((rx && code != NetdResponseCode.InterfaceRxThrottleResult) || (
                    !rx && code != NetdResponseCode.InterfaceTxThrottleResult)) {
                Slog.e(TAG, String.format("Unexpected response code %d", code));
                return -1;
            }
            return Integer.parseInt(tok[1]);
        } catch (Exception e) {
            Slog.e(TAG, String.format(
                    "Failed to read interface %s throttle value", (rx ? "rx" : "tx")), e);
        }
        return -1;
    }

    public int getInterfaceRxThrottle(String iface) {
        return getInterfaceThrottle(iface, true);
    }

    public int getInterfaceTxThrottle(String iface) {
        return getInterfaceThrottle(iface, false);
    }
}
