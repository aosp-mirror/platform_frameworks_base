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

import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.provider.Settings.Secure.NETSTATS_ENABLED;
import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.INetworkManagementEventObserver;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkStats;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Binder;
import android.os.INetworkManagementService;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;

import libcore.io.IoUtils;

/**
 * @hide
 */
class NetworkManagementService extends INetworkManagementService.Stub implements Watchdog.Monitor {
    private static final String TAG = "NetworkManagementService";
    private static final boolean DBG = false;
    private static final String NETD_TAG = "NetdConnector";

    private static final int ADD = 1;
    private static final int REMOVE = 2;

    /** Path to {@code /proc/uid_stat}. */
    @Deprecated
    private final File mStatsUid;
    /** Path to {@code /proc/net/dev}. */
    private final File mStatsIface;
    /** Path to {@code /proc/net/xt_qtaguid/stats}. */
    private final File mStatsXtUid;
    /** Path to {@code /proc/net/xt_qtaguid/iface_stat}. */
    private final File mStatsXtIface;

    /** {@link #mStatsXtUid} headers. */
    private static final String KEY_IFACE = "iface";
    private static final String KEY_TAG_HEX = "acct_tag_hex";
    private static final String KEY_UID = "uid_tag_int";
    private static final String KEY_RX_BYTES = "rx_bytes";
    private static final String KEY_RX_PACKETS = "rx_packets";
    private static final String KEY_TX_BYTES = "tx_bytes";
    private static final String KEY_TX_PACKETS = "tx_packets";

    class NetdResponseCode {
        /* Keep in sync with system/netd/ResponseCode.h */
        public static final int InterfaceListResult       = 110;
        public static final int TetherInterfaceListResult = 111;
        public static final int TetherDnsFwdTgtListResult = 112;
        public static final int TtyListResult             = 113;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
        public static final int InterfaceGetCfgResult     = 213;
        public static final int SoftapStatusResult        = 214;
        public static final int InterfaceRxCounterResult  = 216;
        public static final int InterfaceTxCounterResult  = 217;
        public static final int InterfaceRxThrottleResult = 218;
        public static final int InterfaceTxThrottleResult = 219;

        public static final int InterfaceChange           = 600;
        public static final int BandwidthControl          = 601;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    private Thread mThread;
    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);

    // TODO: replace with RemoteCallbackList
    private ArrayList<INetworkManagementEventObserver> mObservers;

    private Object mQuotaLock = new Object();
    /** Set of interfaces with active quotas. */
    private HashSet<String> mActiveQuotaIfaces = Sets.newHashSet();
    /** Set of interfaces with active alerts. */
    private HashSet<String> mActiveAlertIfaces = Sets.newHashSet();
    /** Set of UIDs with active reject rules. */
    private SparseBooleanArray mUidRejectOnQuota = new SparseBooleanArray();

    private volatile boolean mBandwidthControlEnabled;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    private NetworkManagementService(Context context, File procRoot) {
        mContext = context;
        mObservers = new ArrayList<INetworkManagementEventObserver>();

        mStatsUid = new File(procRoot, "uid_stat");
        mStatsIface = new File(procRoot, "net/dev");
        mStatsXtUid = new File(procRoot, "net/xt_qtaguid/stats");
        mStatsXtIface = new File(procRoot, "net/xt_qtaguid/iface_stat");

        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            return;
        }

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), "netd", 10, NETD_TAG);
        mThread = new Thread(mConnector, NETD_TAG);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    public static NetworkManagementService create(Context context) throws InterruptedException {
        NetworkManagementService service = new NetworkManagementService(
                context, new File("/proc/"));
        if (DBG) Slog.d(TAG, "Creating NetworkManagementService");
        service.mThread.start();
        if (DBG) Slog.d(TAG, "Awaiting socket connection");
        service.mConnectedSignal.await();
        if (DBG) Slog.d(TAG, "Connected");
        return service;
    }

    // @VisibleForTesting
    public static NetworkManagementService createForTest(
            Context context, File procRoot, boolean bandwidthControlEnabled) {
        // TODO: eventually connect with mock netd
        final NetworkManagementService service = new NetworkManagementService(context, procRoot);
        service.mBandwidthControlEnabled = bandwidthControlEnabled;
        return service;
    }

    public void systemReady() {
        // only enable bandwidth control when support exists, and requested by
        // system setting.
        final boolean hasKernelSupport = new File("/proc/net/xt_qtaguid/ctrl").exists();
        final boolean shouldEnable =
                Settings.Secure.getInt(mContext.getContentResolver(), NETSTATS_ENABLED, 1) != 0;

        if (hasKernelSupport && shouldEnable) {
            Slog.d(TAG, "enabling bandwidth control");
            try {
                mConnector.doCommand("bandwidth enable");
                mBandwidthControlEnabled = true;
            } catch (NativeDaemonConnectorException e) {
                Slog.e(TAG, "problem enabling bandwidth controls", e);
            }
        } else {
            Slog.d(TAG, "not enabling bandwidth control");
        }

        SystemProperties.set(PROP_QTAGUID_ENABLED, mBandwidthControlEnabled ? "1" : "0");
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
     * Notify our observers of an interface status change
     */
    private void notifyInterfaceStatusChanged(String iface, boolean up) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceStatusChanged(iface, up);
            } catch (Exception ex) {
                Slog.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    private void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceLinkStateChanged(iface, up);
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

    /**
     * Notify our observers of a limit reached.
     */
    private void notifyLimitReached(String limitName, String iface) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.limitReached(limitName, iface);
                Slog.d(TAG, "Observer notified limit reached for " + limitName + " " + iface);
            } catch (Exception ex) {
                Slog.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Let us know the daemon is connected
     */
    protected void onDaemonConnected() {
        if (DBG) Slog.d(TAG, "onConnected");
        mConnectedSignal.countDown();
    }


    //
    // Netd Callback handling
    //

    class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        /** {@inheritDoc} */
        public void onDaemonConnected() {
            NetworkManagementService.this.onDaemonConnected();
        }

        /** {@inheritDoc} */
        public boolean onEvent(int code, String raw, String[] cooked) {
            switch (code) {
            case NetdResponseCode.InterfaceChange:
                    /*
                     * a network interface change occured
                     * Format: "NNN Iface added <name>"
                     *         "NNN Iface removed <name>"
                     *         "NNN Iface changed <name> <up/down>"
                     *         "NNN Iface linkstatus <name> <up/down>"
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
                        notifyInterfaceStatusChanged(cooked[3], cooked[4].equals("up"));
                        return true;
                    } else if (cooked[2].equals("linkstate") && cooked.length == 5) {
                        notifyInterfaceLinkStateChanged(cooked[3], cooked[4].equals("up"));
                        return true;
                    }
                    throw new IllegalStateException(
                            String.format("Invalid event from daemon (%s)", raw));
                    // break;
            case NetdResponseCode.BandwidthControl:
                    /*
                     * Bandwidth control needs some attention
                     * Format: "NNN limit alert <alertName> <ifaceName>"
                     */
                    if (cooked.length < 5 || !cooked[1].equals("limit")) {
                        throw new IllegalStateException(
                                String.format("Invalid event from daemon (%s)", raw));
                    }
                    if (cooked[2].equals("alert")) {
                        notifyLimitReached(cooked[3], cooked[4]);
                        return true;
                    }
                    throw new IllegalStateException(
                            String.format("Invalid event from daemon (%s)", raw));
                    // break;
            default: break;
            }
            return false;
        }
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

        // Rsp: 213 xx:xx:xx:xx:xx:xx yyy.yyy.yyy.yyy zzz [flag1 flag2 flag3]
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
            InetAddress addr = null;
            int prefixLength = 0;
            try {
                addr = NetworkUtils.numericToInetAddress(st.nextToken(" "));
            } catch (IllegalArgumentException iae) {
                Slog.e(TAG, "Failed to parse ipaddr", iae);
            }

            try {
                prefixLength = Integer.parseInt(st.nextToken(" "));
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, "Failed to parse prefixLength", nfe);
            }

            cfg.addr = new LinkAddress(addr, prefixLength);
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
        LinkAddress linkAddr = cfg.addr;
        if (linkAddr == null || linkAddr.getAddress() == null) {
            throw new IllegalStateException("Null LinkAddress given");
        }
        String cmd = String.format("interface setcfg %s %s %d %s", iface,
                linkAddr.getAddress().getHostAddress(),
                linkAddr.getNetworkPrefixLength(),
                cfg.interfaceFlags);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon to interface setcfg - " + e);
        }
    }

    public void setInterfaceDown(String iface) throws IllegalStateException {
        try {
            InterfaceConfiguration ifcg = getInterfaceConfig(iface);
            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("up", "down");
            setInterfaceConfig(iface, ifcg);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon for interface down - " + e);
        }
    }

    public void setInterfaceUp(String iface) throws IllegalStateException {
        try {
            InterfaceConfiguration ifcg = getInterfaceConfig(iface);
            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("down", "up");
            setInterfaceConfig(iface, ifcg);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon for interface up - " + e);
        }
    }

    /* TODO: This is right now a IPv4 only function. Works for wifi which loses its
       IPv6 addresses on interface down, but we need to do full clean up here */
    public void clearInterfaceAddresses(String iface) throws IllegalStateException {
         String cmd = String.format("interface clearaddrs %s", iface);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native daemon to interface clearallips - " + e);
        }
    }

    public void addRoute(String interfaceName, RouteInfo route) {
        modifyRoute(interfaceName, ADD, route);
    }

    public void removeRoute(String interfaceName, RouteInfo route) {
        modifyRoute(interfaceName, REMOVE, route);
    }

    private void modifyRoute(String interfaceName, int action, RouteInfo route) {
        ArrayList<String> rsp;

        StringBuilder cmd;

        switch (action) {
            case ADD:
            {
                cmd = new StringBuilder("interface route add " + interfaceName);
                break;
            }
            case REMOVE:
            {
                cmd = new StringBuilder("interface route remove " + interfaceName);
                break;
            }
            default:
                throw new IllegalStateException("Unknown action type " + action);
        }

        // create triplet: dest-ip-addr prefixlength gateway-ip-addr
        LinkAddress la = route.getDestination();
        cmd.append(' ');
        cmd.append(la.getAddress().getHostAddress());
        cmd.append(' ');
        cmd.append(la.getNetworkPrefixLength());
        cmd.append(' ');
        if (route.getGateway() == null) {
            if (la.getAddress() instanceof Inet4Address) {
                cmd.append("0.0.0.0");
            } else {
                cmd.append ("::0");
            }
        } else {
            cmd.append(route.getGateway().getHostAddress());
        }
        try {
            rsp = mConnector.doCommand(cmd.toString());
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Unable to communicate with native dameon to add routes - "
                    + e);
        }

        for (String line : rsp) {
            Log.v(TAG, "add route response is " + line);
        }
    }

    private ArrayList<String> readRouteList(String filename) {
        FileInputStream fstream = null;
        ArrayList<String> list = new ArrayList<String>();

        try {
            fstream = new FileInputStream(filename);
            DataInputStream in = new DataInputStream(fstream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String s;

            // throw away the title line

            while (((s = br.readLine()) != null) && (s.length() != 0)) {
                list.add(s);
            }
        } catch (IOException ex) {
            // return current list, possibly empty
        } finally {
            if (fstream != null) {
                try {
                    fstream.close();
                } catch (IOException ex) {}
            }
        }

        return list;
    }

    public RouteInfo[] getRoutes(String interfaceName) {
        ArrayList<RouteInfo> routes = new ArrayList<RouteInfo>();

        // v4 routes listed as:
        // iface dest-addr gateway-addr flags refcnt use metric netmask mtu window IRTT
        for (String s : readRouteList("/proc/net/route")) {
            String[] fields = s.split("\t");

            if (fields.length > 7) {
                String iface = fields[0];

                if (interfaceName.equals(iface)) {
                    String dest = fields[1];
                    String gate = fields[2];
                    String flags = fields[3]; // future use?
                    String mask = fields[7];
                    try {
                        // address stored as a hex string, ex: 0014A8C0
                        InetAddress destAddr =
                                NetworkUtils.intToInetAddress((int)Long.parseLong(dest, 16));
                        int prefixLength =
                                NetworkUtils.netmaskIntToPrefixLength(
                                (int)Long.parseLong(mask, 16));
                        LinkAddress linkAddress = new LinkAddress(destAddr, prefixLength);

                        // address stored as a hex string, ex 0014A8C0
                        InetAddress gatewayAddr =
                                NetworkUtils.intToInetAddress((int)Long.parseLong(gate, 16));

                        RouteInfo route = new RouteInfo(linkAddress, gatewayAddr);
                        routes.add(route);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing route " + s + " : " + e);
                        continue;
                    }
                }
            }
        }

        // v6 routes listed as:
        // dest-addr prefixlength ?? ?? gateway-addr ?? ?? ?? ?? iface
        for (String s : readRouteList("/proc/net/ipv6_route")) {
            String[]fields = s.split("\\s+");
            if (fields.length > 9) {
                String iface = fields[9].trim();
                if (interfaceName.equals(iface)) {
                    String dest = fields[0];
                    String prefix = fields[1];
                    String gate = fields[4];

                    try {
                        // prefix length stored as a hex string, ex 40
                        int prefixLength = Integer.parseInt(prefix, 16);

                        // address stored as a 32 char hex string
                        // ex fe800000000000000000000000000000
                        InetAddress destAddr = NetworkUtils.hexToInet6Address(dest);
                        LinkAddress linkAddress = new LinkAddress(destAddr, prefixLength);

                        InetAddress gateAddr = NetworkUtils.hexToInet6Address(gate);

                        RouteInfo route = new RouteInfo(linkAddress, gateAddr);
                        routes.add(route);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing route " + s + " : " + e);
                        continue;
                    }
                }
            }
        }
        return (RouteInfo[]) routes.toArray(new RouteInfo[0]);
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
                cmd += " " + NetworkUtils.numericToInetAddress(s).getHostAddress();
            }
            try {
                mConnector.doCommand(cmd);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException(
                        "Unable to communicate to native daemon for setting tether dns");
            }
        } catch (IllegalArgumentException e) {
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
                    NetworkUtils.numericToInetAddress(localAddr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(remoteAddr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(dns1Addr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(dns2Addr).getHostAddress()));
        } catch (IllegalArgumentException e) {
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

    public void startAccessPoint(WifiConfiguration wifiConfig, String wlanIface, String softapIface)
             throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");
        try {
            wifiFirmwareReload(wlanIface, "AP");
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
                                       getSecurityType(wifiConfig),
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

    private String getSecurityType(WifiConfiguration wifiConfig) {
        switch (wifiConfig.getAuthType()) {
            case KeyMgmt.WPA_PSK:
                return "wpa-psk";
            case KeyMgmt.WPA2_PSK:
                return "wpa2-psk";
            default:
                return "open";
        }
    }

    /* @param mode can be "AP", "STA" or "P2P" */
    public void wifiFirmwareReload(String wlanIface, String mode) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");

        try {
            mConnector.doCommand(String.format("softap fwreload " + wlanIface + " " + mode));
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon ", e);
        }
    }

    public void stopAccessPoint(String wlanIface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "NetworkManagementService");
        try {
            mConnector.doCommand("softap stopap");
            mConnector.doCommand("softap stop " + wlanIface);
            wifiFirmwareReload(wlanIface, "STA");
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
                        getSecurityType(wifiConfig),
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

    @Override
    public NetworkStats getNetworkStatsSummary() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        final HashSet<String> activeIfaces = Sets.newHashSet();
        final ArrayList<String> values = Lists.newArrayList();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mStatsIface));

            // skip first two header lines
            reader.readLine();
            reader.readLine();

            // parse remaining lines
            String line;
            while ((line = reader.readLine()) != null) {
                splitLine(line, values);

                try {
                    entry.iface = values.get(0);
                    entry.uid = UID_ALL;
                    entry.tag = TAG_NONE;
                    entry.rxBytes = Long.parseLong(values.get(1));
                    entry.rxPackets = Long.parseLong(values.get(2));
                    entry.txBytes = Long.parseLong(values.get(9));
                    entry.txPackets = Long.parseLong(values.get(10));

                    activeIfaces.add(entry.iface);
                    stats.addValues(entry);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "problem parsing stats row '" + line + "': " + e);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "problem parsing stats: " + e);
        } finally {
            IoUtils.closeQuietly(reader);
        }

        if (DBG) Slog.d(TAG, "recorded active stats from " + activeIfaces);

        // splice in stats from any disabled ifaces
        if (mBandwidthControlEnabled) {
            final HashSet<String> xtIfaces = Sets.newHashSet(fileListWithoutNull(mStatsXtIface));
            xtIfaces.removeAll(activeIfaces);

            for (String iface : xtIfaces) {
                final File ifacePath = new File(mStatsXtIface, iface);

                entry.iface = iface;
                entry.uid = UID_ALL;
                entry.tag = TAG_NONE;
                entry.rxBytes = readSingleLongFromFile(new File(ifacePath, "rx_bytes"));
                entry.rxPackets = readSingleLongFromFile(new File(ifacePath, "rx_packets"));
                entry.txBytes = readSingleLongFromFile(new File(ifacePath, "tx_bytes"));
                entry.txPackets = readSingleLongFromFile(new File(ifacePath, "tx_packets"));

                stats.addValues(entry);
            }

            if (DBG) Slog.d(TAG, "recorded stale stats from " + xtIfaces);
        }

        return stats;
    }

    @Override
    public NetworkStats getNetworkStatsDetail() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        if (mBandwidthControlEnabled) {
            return getNetworkStatsDetailNetfilter(UID_ALL);
        } else {
            return getNetworkStatsDetailUidstat(UID_ALL);
        }
    }

    @Override
    public void setInterfaceQuota(String iface, long quotaBytes) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (mActiveQuotaIfaces.contains(iface)) {
                throw new IllegalStateException("iface " + iface + " already has quota");
            }

            final StringBuilder command = new StringBuilder();
            command.append("bandwidth setiquota ").append(iface).append(" ").append(quotaBytes);

            try {
                // TODO: support quota shared across interfaces
                mConnector.doCommand(command.toString());
                mActiveQuotaIfaces.add(iface);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException("Error communicating to native daemon", e);
            }
        }
    }

    @Override
    public void removeInterfaceQuota(String iface) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (!mActiveQuotaIfaces.contains(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            final StringBuilder command = new StringBuilder();
            command.append("bandwidth removeiquota ").append(iface);

            try {
                // TODO: support quota shared across interfaces
                mConnector.doCommand(command.toString());
                mActiveQuotaIfaces.remove(iface);
                mActiveAlertIfaces.remove(iface);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException("Error communicating to native daemon", e);
            }
        }
    }

    @Override
    public void setInterfaceAlert(String iface, long alertBytes) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        // quick sanity check
        if (!mActiveQuotaIfaces.contains(iface)) {
            throw new IllegalStateException("setting alert requires existing quota on iface");
        }

        synchronized (mQuotaLock) {
            if (mActiveAlertIfaces.contains(iface)) {
                throw new IllegalStateException("iface " + iface + " already has alert");
            }

            final StringBuilder command = new StringBuilder();
            command.append("bandwidth setinterfacealert ").append(iface).append(" ").append(
                    alertBytes);

            try {
                // TODO: support alert shared across interfaces
                mConnector.doCommand(command.toString());
                mActiveAlertIfaces.add(iface);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException("Error communicating to native daemon", e);
            }
        }
    }

    @Override
    public void removeInterfaceAlert(String iface) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (!mActiveAlertIfaces.contains(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            final StringBuilder command = new StringBuilder();
            command.append("bandwidth removeinterfacealert ").append(iface);

            try {
                // TODO: support alert shared across interfaces
                mConnector.doCommand(command.toString());
                mActiveAlertIfaces.remove(iface);
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException("Error communicating to native daemon", e);
            }
        }
    }

    @Override
    public void setGlobalAlert(long alertBytes) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        final StringBuilder command = new StringBuilder();
        command.append("bandwidth setglobalalert ").append(alertBytes);

        try {
            mConnector.doCommand(command.toString());
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon", e);
        }
    }

    @Override
    public void setUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mUidRejectOnQuota) {
            final boolean oldRejectOnQuota = mUidRejectOnQuota.get(uid, false);
            if (oldRejectOnQuota == rejectOnQuotaInterfaces) {
                // TODO: eventually consider throwing
                return;
            }

            final StringBuilder command = new StringBuilder();
            command.append("bandwidth");
            if (rejectOnQuotaInterfaces) {
                command.append(" addnaughtyapps");
            } else {
                command.append(" removenaughtyapps");
            }
            command.append(" ").append(uid);

            try {
                mConnector.doCommand(command.toString());
                if (rejectOnQuotaInterfaces) {
                    mUidRejectOnQuota.put(uid, true);
                } else {
                    mUidRejectOnQuota.delete(uid);
                }
            } catch (NativeDaemonConnectorException e) {
                throw new IllegalStateException("Error communicating to native daemon", e);
            }
        }
    }

    @Override
    public boolean isBandwidthControlEnabled() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        return mBandwidthControlEnabled;
    }

    @Override
    public NetworkStats getNetworkStatsUidDetail(int uid) {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        }

        if (mBandwidthControlEnabled) {
            return getNetworkStatsDetailNetfilter(uid);
        } else {
            return getNetworkStatsDetailUidstat(uid);
        }
    }

    /**
     * Build {@link NetworkStats} with detailed UID statistics.
     */
    private NetworkStats getNetworkStatsDetailNetfilter(int limitUid) {
        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 24);
        final NetworkStats.Entry entry = new NetworkStats.Entry();

        // TODO: remove knownLines check once 5087722 verified
        final HashSet<String> knownLines = Sets.newHashSet();

        final ArrayList<String> keys = Lists.newArrayList();
        final ArrayList<String> values = Lists.newArrayList();
        final HashMap<String, String> parsed = Maps.newHashMap();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mStatsXtUid));

            // parse first line as header
            String line = reader.readLine();
            splitLine(line, keys);

            // parse remaining lines
            while ((line = reader.readLine()) != null) {
                splitLine(line, values);
                parseLine(keys, values, parsed);

                if (!knownLines.add(line)) {
                    throw new IllegalStateException("encountered duplicate proc entry");
                }

                try {
                    entry.iface = parsed.get(KEY_IFACE);
                    entry.tag = kernelToTag(parsed.get(KEY_TAG_HEX));
                    entry.uid = getParsedInt(parsed, KEY_UID);
                    entry.rxBytes = getParsedLong(parsed, KEY_RX_BYTES);
                    entry.rxPackets = getParsedLong(parsed, KEY_RX_PACKETS);
                    entry.txBytes = getParsedLong(parsed, KEY_TX_BYTES);
                    entry.txPackets = getParsedLong(parsed, KEY_TX_PACKETS);

                    if (limitUid == UID_ALL || limitUid == entry.uid) {
                        stats.addValues(entry);
                    }
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "problem parsing stats row '" + line + "': " + e);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "problem parsing stats: " + e);
        } finally {
            IoUtils.closeQuietly(reader);
        }

        return stats;
    }

    private static int getParsedInt(HashMap<String, String> parsed, String key) {
        final String value = parsed.get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }

    private static long getParsedLong(HashMap<String, String> parsed, String key) {
        final String value = parsed.get(key);
        return value != null ? Long.parseLong(value) : 0;
    }

    /**
     * Build {@link NetworkStats} with detailed UID statistics.
     *
     * @deprecated since this uses older "uid_stat" data, and doesn't provide
     *             tag-level granularity or additional variables.
     */
    @Deprecated
    private NetworkStats getNetworkStatsDetailUidstat(int limitUid) {
        final String[] knownUids;
        if (limitUid == UID_ALL) {
            knownUids = fileListWithoutNull(mStatsUid);
        } else {
            knownUids = new String[] { String.valueOf(limitUid) };
        }

        final NetworkStats stats = new NetworkStats(
                SystemClock.elapsedRealtime(), knownUids.length);
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        for (String uid : knownUids) {
            final int uidInt = Integer.parseInt(uid);
            final File uidPath = new File(mStatsUid, uid);

            entry.iface = IFACE_ALL;
            entry.uid = uidInt;
            entry.tag = TAG_NONE;
            entry.rxBytes = readSingleLongFromFile(new File(uidPath, "tcp_rcv"));
            entry.rxPackets = readSingleLongFromFile(new File(uidPath, "tcp_rcv_pkt"));
            entry.txBytes = readSingleLongFromFile(new File(uidPath, "tcp_snd"));
            entry.txPackets = readSingleLongFromFile(new File(uidPath, "tcp_snd_pkt"));

            stats.addValues(entry);
        }

        return stats;
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

    /**
     * Split given line into {@link ArrayList}.
     */
    private static void splitLine(String line, ArrayList<String> outSplit) {
        outSplit.clear();

        final StringTokenizer t = new StringTokenizer(line, " \t\n\r\f:");
        while (t.hasMoreTokens()) {
            outSplit.add(t.nextToken());
        }
    }

    /**
     * Zip the two given {@link ArrayList} as key and value pairs into
     * {@link HashMap}.
     */
    private static void parseLine(
            ArrayList<String> keys, ArrayList<String> values, HashMap<String, String> outParsed) {
        outParsed.clear();

        final int size = Math.min(keys.size(), values.size());
        for (int i = 0; i < size; i++) {
            outParsed.put(keys.get(i), values.get(i));
        }
    }

    /**
     * Utility method to read a single plain-text {@link Long} from the given
     * {@link File}, usually from a {@code /proc/} filesystem.
     */
    private static long readSingleLongFromFile(File file) {
        try {
            final byte[] buffer = IoUtils.readFileAsByteArray(file.toString());
            return Long.parseLong(new String(buffer).trim());
        } catch (NumberFormatException e) {
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Wrapper for {@link File#list()} that returns empty array instead of
     * {@code null}.
     */
    private static String[] fileListWithoutNull(File file) {
        final String[] list = file.list();
        return list != null ? list : new String[0];
    }

    public void setDefaultInterfaceForDns(String iface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "resolver setdefaultif " + iface;

            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native daemon to set default interface", e);
        }
    }

    public void setDnsServersForInterface(String iface, String[] servers)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_NETWORK_STATE,
                "NetworkManagementService");
        try {
            String cmd = "resolver setifdns " + iface;
            for (String s : servers) {
                InetAddress a = NetworkUtils.numericToInetAddress(s);
                if (a.isAnyLocalAddress() == false) {
                    cmd += " " + a.getHostAddress();
                }
            }
            mConnector.doCommand(cmd);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Error setting dnsn for interface", e);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native daemon to set dns for interface", e);
        }
    }

    public void flushDefaultDnsCache() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "resolver flushdefaultif";

            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native deamon to flush default interface", e);
        }
    }

    public void flushInterfaceDnsCache(String iface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "resolver flushif " + iface;

            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native daemon to flush interface " + iface, e);
        }
    }

    /** {@inheritDoc} */
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }
}
