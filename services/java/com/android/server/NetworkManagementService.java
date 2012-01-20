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

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.SHUTDOWN;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_TETHERING;
import static android.provider.Settings.Secure.NETSTATS_ENABLED;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceGetCfgResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceRxThrottleResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceTxThrottleResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.IpFwdStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherDnsFwdTgtListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherInterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetheringStatsResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TtyListResult;
import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;

import android.content.Context;
import android.net.INetworkManagementEventObserver;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkStats;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.INetworkManagementService;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.net.NetworkStatsFactory;
import com.android.server.NativeDaemonConnector.Command;
import com.google.android.collect.Sets;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;

/**
 * @hide
 */
public class NetworkManagementService extends INetworkManagementService.Stub
        implements Watchdog.Monitor {
    private static final String TAG = "NetworkManagementService";
    private static final boolean DBG = false;
    private static final String NETD_TAG = "NetdConnector";

    private static final String ADD = "add";
    private static final String REMOVE = "remove";

    private static final String DEFAULT = "default";
    private static final String SECONDARY = "secondary";

    /**
     * Name representing {@link #setGlobalAlert(long)} limit when delivered to
     * {@link INetworkManagementEventObserver#limitReached(String, String)}.
     */
    public static final String LIMIT_GLOBAL_ALERT = "globalAlert";

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
        public static final int QuotaCounterResult        = 220;
        public static final int TetheringStatsResult      = 221;

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

    private final RemoteCallbackList<INetworkManagementEventObserver> mObservers =
            new RemoteCallbackList<INetworkManagementEventObserver>();

    private final NetworkStatsFactory mStatsFactory = new NetworkStatsFactory();

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
    private NetworkManagementService(Context context) {
        mContext = context;

        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            return;
        }

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), "netd", 10, NETD_TAG, 50);
        mThread = new Thread(mConnector, NETD_TAG);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    public static NetworkManagementService create(Context context) throws InterruptedException {
        NetworkManagementService service = new NetworkManagementService(context);
        if (DBG) Slog.d(TAG, "Creating NetworkManagementService");
        service.mThread.start();
        if (DBG) Slog.d(TAG, "Awaiting socket connection");
        service.mConnectedSignal.await();
        if (DBG) Slog.d(TAG, "Connected");
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
                mConnector.execute("bandwidth", "enable");
                mBandwidthControlEnabled = true;
            } catch (NativeDaemonConnectorException e) {
                Log.wtf(TAG, "problem enabling bandwidth controls", e);
            }
        } else {
            Slog.d(TAG, "not enabling bandwidth control");
        }

        SystemProperties.set(PROP_QTAGUID_ENABLED, mBandwidthControlEnabled ? "1" : "0");
    }

    @Override
    public void registerObserver(INetworkManagementEventObserver observer) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        mObservers.register(observer);
    }

    @Override
    public void unregisterObserver(INetworkManagementEventObserver observer) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        mObservers.unregister(observer);
    }

    /**
     * Notify our observers of an interface status change
     */
    private void notifyInterfaceStatusChanged(String iface, boolean up) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceStatusChanged(iface, up);
            } catch (RemoteException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    private void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceLinkStateChanged(iface, up);
            } catch (RemoteException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of an interface addition.
     */
    private void notifyInterfaceAdded(String iface) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceAdded(iface);
            } catch (RemoteException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of an interface removal.
     */
    private void notifyInterfaceRemoved(String iface) {
        // netd already clears out quota and alerts for removed ifaces; update
        // our sanity-checking state.
        mActiveAlertIfaces.remove(iface);
        mActiveQuotaIfaces.remove(iface);

        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceRemoved(iface);
            } catch (RemoteException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of a limit reached.
     */
    private void notifyLimitReached(String limitName, String iface) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).limitReached(limitName, iface);
            } catch (RemoteException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Let us know the daemon is connected
     */
    protected void onDaemonConnected() {
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

    @Override
    public String[] listInterfaces() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("interface", "list"), InterfaceListResult);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public InterfaceConfiguration getInterfaceConfig(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("interface", "getcfg", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        event.checkCode(InterfaceGetCfgResult);

        // Rsp: 213 xx:xx:xx:xx:xx:xx yyy.yyy.yyy.yyy zzz flag1 flag2 flag3
        final StringTokenizer st = new StringTokenizer(event.getMessage());

        InterfaceConfiguration cfg;
        try {
            cfg = new InterfaceConfiguration();
            cfg.setHardwareAddress(st.nextToken(" "));
            InetAddress addr = null;
            int prefixLength = 0;
            try {
                addr = NetworkUtils.numericToInetAddress(st.nextToken());
            } catch (IllegalArgumentException iae) {
                Slog.e(TAG, "Failed to parse ipaddr", iae);
            }

            try {
                prefixLength = Integer.parseInt(st.nextToken());
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, "Failed to parse prefixLength", nfe);
            }

            cfg.setLinkAddress(new LinkAddress(addr, prefixLength));
            while (st.hasMoreTokens()) {
                cfg.setFlag(st.nextToken());
            }
        } catch (NoSuchElementException nsee) {
            throw new IllegalStateException("Invalid response from daemon: " + event);
        }
        return cfg;
    }

    @Override
    public void setInterfaceConfig(String iface, InterfaceConfiguration cfg) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        LinkAddress linkAddr = cfg.getLinkAddress();
        if (linkAddr == null || linkAddr.getAddress() == null) {
            throw new IllegalStateException("Null LinkAddress given");
        }

        final Command cmd = new Command("interface", "setcfg", iface,
                linkAddr.getAddress().getHostAddress(),
                linkAddr.getNetworkPrefixLength());
        for (String flag : cfg.getFlags()) {
            cmd.appendArg(flag);
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setInterfaceDown(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceDown();
        setInterfaceConfig(iface, ifcg);
    }

    @Override
    public void setInterfaceUp(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceUp();
        setInterfaceConfig(iface, ifcg);
    }

    @Override
    public void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute(
                    "interface", "ipv6privacyextensions", iface, enable ? "enable" : "disable");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /* TODO: This is right now a IPv4 only function. Works for wifi which loses its
       IPv6 addresses on interface down, but we need to do full clean up here */
    @Override
    public void clearInterfaceAddresses(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "clearaddrs", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void enableIpv6(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "ipv6", iface, "enable");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void disableIpv6(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "ipv6", iface, "disable");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addRoute(String interfaceName, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyRoute(interfaceName, ADD, route, DEFAULT);
    }

    @Override
    public void removeRoute(String interfaceName, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyRoute(interfaceName, REMOVE, route, DEFAULT);
    }

    @Override
    public void addSecondaryRoute(String interfaceName, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyRoute(interfaceName, ADD, route, SECONDARY);
    }

    @Override
    public void removeSecondaryRoute(String interfaceName, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyRoute(interfaceName, REMOVE, route, SECONDARY);
    }

    private void modifyRoute(String interfaceName, String action, RouteInfo route, String type) {
        final Command cmd = new Command("interface", "route", action, interfaceName, type);

        // create triplet: dest-ip-addr prefixlength gateway-ip-addr
        final LinkAddress la = route.getDestination();
        cmd.appendArg(la.getAddress().getHostAddress());
        cmd.appendArg(la.getNetworkPrefixLength());

        if (route.getGateway() == null) {
            if (la.getAddress() instanceof Inet4Address) {
                cmd.appendArg("0.0.0.0");
            } else {
                cmd.appendArg("::0");
            }
        } else {
            cmd.appendArg(route.getGateway().getHostAddress());
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
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

    @Override
    public RouteInfo[] getRoutes(String interfaceName) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
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
        return routes.toArray(new RouteInfo[routes.size()]);
    }

    @Override
    public void shutdown() {
        // TODO: remove from aidl if nobody calls externally
        mContext.enforceCallingOrSelfPermission(SHUTDOWN, TAG);

        Slog.d(TAG, "Shutting down");
    }

    @Override
    public boolean getIpForwardingEnabled() throws IllegalStateException{
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("ipfwd", "status");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        // 211 Forwarding enabled
        event.checkCode(IpFwdStatusResult);
        return event.getMessage().endsWith("enabled");
    }

    @Override
    public void setIpForwardingEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("ipfwd", enable ? "enable" : "disable");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void startTethering(String[] dhcpRange) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        // cmd is "tether start first_start first_stop second_start second_stop ..."
        // an odd number of addrs will fail

        final Command cmd = new Command("tether", "start");
        for (String d : dhcpRange) {
            cmd.appendArg(d);
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void stopTethering() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("tether", "stop");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public boolean isTetheringStarted() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("tether", "status");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        // 210 Tethering services started
        event.checkCode(TetherStatusResult);
        return event.getMessage().endsWith("started");
    }

    @Override
    public void tetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("tether", "interface", "add", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void untetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("tether", "interface", "remove", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public String[] listTetheredInterfaces() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("tether", "interface", "list"),
                    TetherInterfaceListResult);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setDnsForwarders(String[] dns) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final Command cmd = new Command("tether", "dns", "set");
        for (String s : dns) {
            cmd.appendArg(NetworkUtils.numericToInetAddress(s).getHostAddress());
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public String[] getDnsForwarders() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("tether", "dns", "list"), TetherDnsFwdTgtListResult);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    private void modifyNat(String action, String internalInterface, String externalInterface)
            throws SocketException {
        final Command cmd = new Command("nat", action, internalInterface, externalInterface);

        final NetworkInterface internalNetworkInterface = NetworkInterface.getByName(
                internalInterface);
        if (internalNetworkInterface == null) {
            cmd.appendArg("0");
        } else {
            Collection<InterfaceAddress> interfaceAddresses = internalNetworkInterface
                    .getInterfaceAddresses();
            cmd.appendArg(interfaceAddresses.size());
            for (InterfaceAddress ia : interfaceAddresses) {
                InetAddress addr = NetworkUtils.getNetworkPart(
                        ia.getAddress(), ia.getNetworkPrefixLength());
                cmd.appendArg(addr.getHostAddress() + "/" + ia.getNetworkPrefixLength());
            }
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void enableNat(String internalInterface, String externalInterface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            modifyNat("enable", internalInterface, externalInterface);
        } catch (SocketException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void disableNat(String internalInterface, String externalInterface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            modifyNat("disable", internalInterface, externalInterface);
        } catch (SocketException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String[] listTtys() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("list_ttys"), TtyListResult);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void attachPppd(
            String tty, String localAddr, String remoteAddr, String dns1Addr, String dns2Addr) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("pppd", "attach", tty,
                    NetworkUtils.numericToInetAddress(localAddr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(remoteAddr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(dns1Addr).getHostAddress(),
                    NetworkUtils.numericToInetAddress(dns2Addr).getHostAddress());
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void detachPppd(String tty) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("pppd", "detach", tty);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void startAccessPoint(
            WifiConfiguration wifiConfig, String wlanIface, String softapIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            wifiFirmwareReload(wlanIface, "AP");
            mConnector.execute("softap", "start", wlanIface);
            if (wifiConfig == null) {
                mConnector.execute("softap", "set", wlanIface, softapIface);
            } else {
                mConnector.execute("softap", "set", wlanIface, softapIface, wifiConfig.SSID,
                        getSecurityType(wifiConfig), wifiConfig.preSharedKey);
            }
            mConnector.execute("softap", "startap");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    private static String getSecurityType(WifiConfiguration wifiConfig) {
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
    @Override
    public void wifiFirmwareReload(String wlanIface, String mode) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("softap", "fwreload", wlanIface, mode);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void stopAccessPoint(String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("softap", "stopap");
            mConnector.execute("softap", "stop", wlanIface);
            wifiFirmwareReload(wlanIface, "STA");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setAccessPoint(WifiConfiguration wifiConfig, String wlanIface, String softapIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            if (wifiConfig == null) {
                mConnector.execute("softap", "set", wlanIface, softapIface);
            } else {
                mConnector.execute("softap", "set", wlanIface, softapIface, wifiConfig.SSID,
                        getSecurityType(wifiConfig), wifiConfig.preSharedKey);
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public NetworkStats getNetworkStatsSummary() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return mStatsFactory.readNetworkStatsSummary();
    }

    @Override
    public NetworkStats getNetworkStatsDetail() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return mStatsFactory.readNetworkStatsDetail(UID_ALL);
    }

    @Override
    public void setInterfaceQuota(String iface, long quotaBytes) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (mActiveQuotaIfaces.contains(iface)) {
                throw new IllegalStateException("iface " + iface + " already has quota");
            }

            try {
                // TODO: support quota shared across interfaces
                mConnector.execute("bandwidth", "setiquota", iface, quotaBytes);
                mActiveQuotaIfaces.add(iface);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public void removeInterfaceQuota(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (!mActiveQuotaIfaces.contains(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            mActiveQuotaIfaces.remove(iface);
            mActiveAlertIfaces.remove(iface);

            try {
                // TODO: support quota shared across interfaces
                mConnector.execute("bandwidth", "removeiquota", iface);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public void setInterfaceAlert(String iface, long alertBytes) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

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

            try {
                // TODO: support alert shared across interfaces
                mConnector.execute("bandwidth", "setinterfacealert", iface, alertBytes);
                mActiveAlertIfaces.add(iface);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public void removeInterfaceAlert(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (!mActiveAlertIfaces.contains(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            try {
                // TODO: support alert shared across interfaces
                mConnector.execute("bandwidth", "removeinterfacealert", iface);
                mActiveAlertIfaces.remove(iface);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public void setGlobalAlert(long alertBytes) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        try {
            mConnector.execute("bandwidth", "setglobalalert", alertBytes);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mUidRejectOnQuota) {
            final boolean oldRejectOnQuota = mUidRejectOnQuota.get(uid, false);
            if (oldRejectOnQuota == rejectOnQuotaInterfaces) {
                // TODO: eventually consider throwing
                return;
            }

            try {
                mConnector.execute("bandwidth",
                        rejectOnQuotaInterfaces ? "addnaughtyapps" : "removenaughtyapps", uid);
                if (rejectOnQuotaInterfaces) {
                    mUidRejectOnQuota.put(uid, true);
                } else {
                    mUidRejectOnQuota.delete(uid);
                }
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public boolean isBandwidthControlEnabled() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return mBandwidthControlEnabled;
    }

    @Override
    public NetworkStats getNetworkStatsUidDetail(int uid) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return mStatsFactory.readNetworkStatsDetail(uid);
    }

    @Override
    public NetworkStats getNetworkStatsTethering(String[] ifacePairs) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        if (ifacePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "unexpected ifacePairs; length=" + ifacePairs.length);
        }

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        for (int i = 0; i < ifacePairs.length; i += 2) {
            final String ifaceIn = ifacePairs[i];
            final String ifaceOut = ifacePairs[i + 1];
            if (ifaceIn != null && ifaceOut != null) {
                stats.combineValues(getNetworkStatsTethering(ifaceIn, ifaceOut));
            }
        }
        return stats;
    }

    private NetworkStats.Entry getNetworkStatsTethering(String ifaceIn, String ifaceOut) {
        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("bandwidth", "gettetherstats", ifaceIn, ifaceOut);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        event.checkCode(TetheringStatsResult);

        // 221 ifaceIn ifaceOut rx_bytes rx_packets tx_bytes tx_packets
        final StringTokenizer tok = new StringTokenizer(event.getMessage());
        tok.nextToken();
        tok.nextToken();

        try {
            final NetworkStats.Entry entry = new NetworkStats.Entry();
            entry.iface = ifaceIn;
            entry.uid = UID_TETHERING;
            entry.set = SET_DEFAULT;
            entry.tag = TAG_NONE;
            entry.rxBytes = Long.parseLong(tok.nextToken());
            entry.rxPackets = Long.parseLong(tok.nextToken());
            entry.txBytes = Long.parseLong(tok.nextToken());
            entry.txPackets = Long.parseLong(tok.nextToken());
            return entry;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "problem parsing tethering stats for " + ifaceIn + " " + ifaceOut + ": " + e);
        }
    }

    @Override
    public void setInterfaceThrottle(String iface, int rxKbps, int txKbps) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "setthrottle", iface, rxKbps, txKbps);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    private int getInterfaceThrottle(String iface, boolean rx) {
        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("interface", "getthrottle", iface, rx ? "rx" : "tx");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        if (rx) {
            event.checkCode(InterfaceRxThrottleResult);
        } else {
            event.checkCode(InterfaceTxThrottleResult);
        }

        try {
            return Integer.parseInt(event.getMessage());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unexpected response:" + event);
        }
    }

    @Override
    public int getInterfaceRxThrottle(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return getInterfaceThrottle(iface, true);
    }

    @Override
    public int getInterfaceTxThrottle(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        return getInterfaceThrottle(iface, false);
    }

    @Override
    public void setDefaultInterfaceForDns(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "setdefaultif", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setDnsServersForInterface(String iface, String[] servers) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final Command cmd = new Command("resolver", "setifdns", iface);
        for (String s : servers) {
            InetAddress a = NetworkUtils.numericToInetAddress(s);
            if (a.isAnyLocalAddress() == false) {
                cmd.appendArg(a.getHostAddress());
            }
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void flushDefaultDnsCache() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "flushdefaultif");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void flushInterfaceDnsCache(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "flushif", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /** {@inheritDoc} */
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("NetworkManagementService NativeDaemonConnector Log:");
        mConnector.dump(fd, pw, args);
        pw.println();

        pw.print("Bandwidth control enabled: "); pw.println(mBandwidthControlEnabled);

        synchronized (mQuotaLock) {
            pw.print("Active quota ifaces: "); pw.println(mActiveQuotaIfaces.toString());
            pw.print("Active alert ifaces: "); pw.println(mActiveAlertIfaces.toString());
        }

        synchronized (mUidRejectOnQuota) {
            pw.print("UID reject on quota ifaces: [");
            final int size = mUidRejectOnQuota.size();
            for (int i = 0; i < size; i++) {
                pw.print(mUidRejectOnQuota.keyAt(i));
                if (i < size - 1) pw.print(",");
            }
            pw.println("]");
        }
    }
}
