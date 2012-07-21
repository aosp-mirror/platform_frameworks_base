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
import static com.android.server.NetworkManagementService.NetdResponseCode.ClatdStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.GetMarkResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceGetCfgResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.IpFwdStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherDnsFwdTgtListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherInterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetheringStatsListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TtyListResult;
import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;

import android.content.Context;
import android.content.res.Resources;
import android.net.INetworkManagementEventObserver;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkStats;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.util.Preconditions;
import com.android.server.NativeDaemonConnector.Command;
import com.android.server.NativeDaemonConnector.SensitiveArg;
import com.android.server.net.LockdownVpnTracker;
import com.google.android.collect.Maps;

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
import java.util.HashMap;
import java.util.Map;
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
    private static final String NETD_SOCKET_NAME = "netd";

    private static final String ADD = "add";
    private static final String REMOVE = "remove";

    private static final String ALLOW = "allow";
    private static final String DENY = "deny";

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
        public static final int TetheringStatsListResult  = 114;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
        public static final int InterfaceGetCfgResult     = 213;
        public static final int SoftapStatusResult        = 214;
        public static final int InterfaceRxCounterResult  = 216;
        public static final int InterfaceTxCounterResult  = 217;
        public static final int QuotaCounterResult        = 220;
        public static final int TetheringStatsResult      = 221;
        public static final int DnsProxyQueryResult       = 222;
        public static final int ClatdStatusResult         = 223;
        public static final int GetMarkResult             = 225;

        public static final int InterfaceChange           = 600;
        public static final int BandwidthControl          = 601;
        public static final int InterfaceClassActivity    = 613;
        public static final int InterfaceAddressChange    = 614;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    private final Handler mMainHandler = new Handler();

    private Thread mThread;
    private CountDownLatch mConnectedSignal = new CountDownLatch(1);

    private final RemoteCallbackList<INetworkManagementEventObserver> mObservers =
            new RemoteCallbackList<INetworkManagementEventObserver>();

    private final NetworkStatsFactory mStatsFactory = new NetworkStatsFactory();

    private Object mQuotaLock = new Object();
    /** Set of interfaces with active quotas. */
    private HashMap<String, Long> mActiveQuotas = Maps.newHashMap();
    /** Set of interfaces with active alerts. */
    private HashMap<String, Long> mActiveAlerts = Maps.newHashMap();
    /** Set of UIDs with active reject rules. */
    private SparseBooleanArray mUidRejectOnQuota = new SparseBooleanArray();

    private Object mIdleTimerLock = new Object();
    /** Set of interfaces with active idle timers. */
    private static class IdleTimerParams {
        public final int timeout;
        public final String label;
        public int networkCount;

        IdleTimerParams(int timeout, String label) {
            this.timeout = timeout;
            this.label = label;
            this.networkCount = 1;
        }
    }
    private HashMap<String, IdleTimerParams> mActiveIdleTimers = Maps.newHashMap();

    private volatile boolean mBandwidthControlEnabled;
    private volatile boolean mFirewallEnabled;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    private NetworkManagementService(Context context, String socket) {
        mContext = context;

        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            return;
        }

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), socket, 10, NETD_TAG, 160);
        mThread = new Thread(mConnector, NETD_TAG);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    static NetworkManagementService create(Context context,
            String socket) throws InterruptedException {
        final NetworkManagementService service = new NetworkManagementService(context, socket);
        final CountDownLatch connectedSignal = service.mConnectedSignal;
        if (DBG) Slog.d(TAG, "Creating NetworkManagementService");
        service.mThread.start();
        if (DBG) Slog.d(TAG, "Awaiting socket connection");
        connectedSignal.await();
        if (DBG) Slog.d(TAG, "Connected");
        return service;
    }

    public static NetworkManagementService create(Context context) throws InterruptedException {
        return create(context, NETD_SOCKET_NAME);
    }

    public void systemReady() {
        prepareNativeDaemon();
        if (DBG) Slog.d(TAG, "Prepared");
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
            } catch (RuntimeException e) {
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
            } catch (RuntimeException e) {
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
            } catch (RuntimeException e) {
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
        mActiveAlerts.remove(iface);
        mActiveQuotas.remove(iface);

        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceRemoved(iface);
            } catch (RemoteException e) {
            } catch (RuntimeException e) {
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
            } catch (RuntimeException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of a change in the data activity state of the interface
     */
    private void notifyInterfaceClassActivity(String label, boolean active) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).interfaceClassDataActivityChanged(label, active);
            } catch (RemoteException e) {
            } catch (RuntimeException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Prepare native daemon once connected, enabling modules and pushing any
     * existing in-memory rules.
     */
    private void prepareNativeDaemon() {
        mBandwidthControlEnabled = false;

        // only enable bandwidth control when support exists
        final boolean hasKernelSupport = new File("/proc/net/xt_qtaguid/ctrl").exists();
        if (hasKernelSupport) {
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

        if (mBandwidthControlEnabled) {
            try {
                IBatteryStats.Stub.asInterface(ServiceManager.getService(BatteryStats.SERVICE_NAME))
                        .noteNetworkStatsEnabled();
            } catch (RemoteException e) {
            }
        }

        // push any existing quota or UID rules
        synchronized (mQuotaLock) {
            int size = mActiveQuotas.size();
            if (size > 0) {
                Slog.d(TAG, "pushing " + size + " active quota rules");
                final HashMap<String, Long> activeQuotas = mActiveQuotas;
                mActiveQuotas = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeQuotas.entrySet()) {
                    setInterfaceQuota(entry.getKey(), entry.getValue());
                }
            }

            size = mActiveAlerts.size();
            if (size > 0) {
                Slog.d(TAG, "pushing " + size + " active alert rules");
                final HashMap<String, Long> activeAlerts = mActiveAlerts;
                mActiveAlerts = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeAlerts.entrySet()) {
                    setInterfaceAlert(entry.getKey(), entry.getValue());
                }
            }

            size = mUidRejectOnQuota.size();
            if (size > 0) {
                Slog.d(TAG, "pushing " + size + " active uid rules");
                final SparseBooleanArray uidRejectOnQuota = mUidRejectOnQuota;
                mUidRejectOnQuota = new SparseBooleanArray();
                for (int i = 0; i < uidRejectOnQuota.size(); i++) {
                    setUidNetworkRules(uidRejectOnQuota.keyAt(i), uidRejectOnQuota.valueAt(i));
                }
            }
        }

        // TODO: Push any existing firewall state
        setFirewallEnabled(mFirewallEnabled || LockdownVpnTracker.isEnabled());
    }

    /**
     * Notify our observers of a new or updated interface address.
     */
    private void notifyAddressUpdated(String address, String iface, int flags, int scope) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).addressUpdated(address, iface, flags, scope);
            } catch (RemoteException e) {
            } catch (RuntimeException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    /**
     * Notify our observers of a deleted interface address.
     */
    private void notifyAddressRemoved(String address, String iface, int flags, int scope) {
        final int length = mObservers.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                mObservers.getBroadcastItem(i).addressRemoved(address, iface, flags, scope);
            } catch (RemoteException e) {
            } catch (RuntimeException e) {
            }
        }
        mObservers.finishBroadcast();
    }

    //
    // Netd Callback handling
    //

    private class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        @Override
        public void onDaemonConnected() {
            // event is dispatched from internal NDC thread, so we prepare the
            // daemon back on main thread.
            if (mConnectedSignal != null) {
                mConnectedSignal.countDown();
                mConnectedSignal = null;
            } else {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        prepareNativeDaemon();
                    }
                });
            }
        }

        @Override
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
            case NetdResponseCode.InterfaceClassActivity:
                    /*
                     * An network interface class state changed (active/idle)
                     * Format: "NNN IfaceClass <active/idle> <label>"
                     */
                    if (cooked.length < 4 || !cooked[1].equals("IfaceClass")) {
                        throw new IllegalStateException(
                                String.format("Invalid event from daemon (%s)", raw));
                    }
                    boolean isActive = cooked[2].equals("active");
                    notifyInterfaceClassActivity(cooked[3], isActive);
                    return true;
                    // break;
            case NetdResponseCode.InterfaceAddressChange:
                    /*
                     * A network address change occurred
                     * Format: "NNN Address updated <addr> <iface> <flags> <scope>"
                     *         "NNN Address removed <addr> <iface> <flags> <scope>"
                     */
                    String msg = String.format("Invalid event from daemon (%s)", raw);
                    if (cooked.length < 6 || !cooked[1].equals("Address")) {
                        throw new IllegalStateException(msg);
                    }

                    int flags;
                    int scope;
                    try {
                        flags = Integer.parseInt(cooked[5]);
                        scope = Integer.parseInt(cooked[6]);
                    } catch(NumberFormatException e) {
                        throw new IllegalStateException(msg);
                    }

                    if (cooked[2].equals("updated")) {
                        notifyAddressUpdated(cooked[3], cooked[4], flags, scope);
                    } else {
                        notifyAddressRemoved(cooked[3], cooked[4], flags, scope);
                    }
                    return true;
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
    public void setMtu(String iface, int mtu) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("interface", "setmtu", iface, mtu);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
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
            WifiConfiguration wifiConfig, String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            if (mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_wifiApFirmwareReload)) {
                wifiFirmwareReload(wlanIface, "AP");
            }
            if (mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_wifiApStartInterface)) {
                mConnector.execute("softap", "start", wlanIface);
            }
            if (wifiConfig == null) {
                mConnector.execute("softap", "set", wlanIface);
            } else {
                mConnector.execute("softap", "set", wlanIface, wifiConfig.SSID,
                                   "broadcast", "6", getSecurityType(wifiConfig),
                                   new SensitiveArg(wifiConfig.preSharedKey));
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
            wifiFirmwareReload(wlanIface, "STA");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setAccessPoint(WifiConfiguration wifiConfig, String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            if (wifiConfig == null) {
                mConnector.execute("softap", "set", wlanIface);
            } else {
                mConnector.execute("softap", "set", wlanIface, wifiConfig.SSID,
                                   "broadcast", "6", getSecurityType(wifiConfig),
                                   new SensitiveArg(wifiConfig.preSharedKey));
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addIdleTimer(String iface, int timeout, String label) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        if (DBG) Slog.d(TAG, "Adding idletimer");

        synchronized (mIdleTimerLock) {
            IdleTimerParams params = mActiveIdleTimers.get(iface);
            if (params != null) {
                // the interface already has idletimer, update network count
                params.networkCount++;
                return;
            }

            try {
                mConnector.execute("idletimer", "add", iface, Integer.toString(timeout), label);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
            mActiveIdleTimers.put(iface, new IdleTimerParams(timeout, label));
        }
    }

    @Override
    public void removeIdleTimer(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        if (DBG) Slog.d(TAG, "Removing idletimer");

        synchronized (mIdleTimerLock) {
            IdleTimerParams params = mActiveIdleTimers.get(iface);
            if (params == null || --(params.networkCount) > 0) {
                return;
            }

            try {
                mConnector.execute("idletimer", "remove", iface,
                        Integer.toString(params.timeout), params.label);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
            mActiveIdleTimers.remove(iface);
        }
    }

    @Override
    public NetworkStats getNetworkStatsSummaryDev() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return mStatsFactory.readNetworkStatsSummaryDev();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public NetworkStats getNetworkStatsSummaryXt() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return mStatsFactory.readNetworkStatsSummaryXt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public NetworkStats getNetworkStatsDetail() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            return mStatsFactory.readNetworkStatsDetail(UID_ALL);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setInterfaceQuota(String iface, long quotaBytes) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        synchronized (mQuotaLock) {
            if (mActiveQuotas.containsKey(iface)) {
                throw new IllegalStateException("iface " + iface + " already has quota");
            }

            try {
                // TODO: support quota shared across interfaces
                mConnector.execute("bandwidth", "setiquota", iface, quotaBytes);
                mActiveQuotas.put(iface, quotaBytes);
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
            if (!mActiveQuotas.containsKey(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            mActiveQuotas.remove(iface);
            mActiveAlerts.remove(iface);

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
        if (!mActiveQuotas.containsKey(iface)) {
            throw new IllegalStateException("setting alert requires existing quota on iface");
        }

        synchronized (mQuotaLock) {
            if (mActiveAlerts.containsKey(iface)) {
                throw new IllegalStateException("iface " + iface + " already has alert");
            }

            try {
                // TODO: support alert shared across interfaces
                mConnector.execute("bandwidth", "setinterfacealert", iface, alertBytes);
                mActiveAlerts.put(iface, alertBytes);
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
            if (!mActiveAlerts.containsKey(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            try {
                // TODO: support alert shared across interfaces
                mConnector.execute("bandwidth", "removeinterfacealert", iface);
                mActiveAlerts.remove(iface);
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

        synchronized (mQuotaLock) {
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
        try {
            return mStatsFactory.readNetworkStatsDetail(uid);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public NetworkStats getNetworkStatsTethering() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        try {
            final NativeDaemonEvent[] events = mConnector.executeForList(
                    "bandwidth", "gettetherstats");
            for (NativeDaemonEvent event : events) {
                if (event.getCode() != TetheringStatsListResult) continue;

                // 114 ifaceIn ifaceOut rx_bytes rx_packets tx_bytes tx_packets
                final StringTokenizer tok = new StringTokenizer(event.getMessage());
                try {
                    final String ifaceIn = tok.nextToken();
                    final String ifaceOut = tok.nextToken();

                    final NetworkStats.Entry entry = new NetworkStats.Entry();
                    entry.iface = ifaceOut;
                    entry.uid = UID_TETHERING;
                    entry.set = SET_DEFAULT;
                    entry.tag = TAG_NONE;
                    entry.rxBytes = Long.parseLong(tok.nextToken());
                    entry.rxPackets = Long.parseLong(tok.nextToken());
                    entry.txBytes = Long.parseLong(tok.nextToken());
                    entry.txPackets = Long.parseLong(tok.nextToken());
                    stats.combineValues(entry);
                } catch (NoSuchElementException e) {
                    throw new IllegalStateException("problem parsing tethering stats: " + event);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("problem parsing tethering stats: " + event);
                }
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
        return stats;
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
    public void setDnsServersForInterface(String iface, String[] servers, String domains) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final Command cmd = new Command("resolver", "setifdns", iface,
                (domains == null ? "" : domains));

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
    public void setUidRangeRoute(String iface, int uid_start, int uid_end) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark",
                    "uid", "add", iface, uid_start, uid_end);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearUidRangeRoute(String iface, int uid_start, int uid_end) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark",
                    "uid", "remove", iface, uid_start, uid_end);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setMarkedForwarding(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark", "rule", "add", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearMarkedForwarding(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark", "rule", "remove", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public int getMarkForUid(int uid) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("interface", "fwmark", "get", "mark", uid);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
        event.checkCode(GetMarkResult);
        return Integer.parseInt(event.getMessage());
    }

    @Override
    public int getMarkForProtect() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("interface", "fwmark", "get", "protect");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
        event.checkCode(GetMarkResult);
        return Integer.parseInt(event.getMessage());
    }

    @Override
    public void setMarkedForwardingRoute(String iface, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            LinkAddress dest = route.getDestination();
            mConnector.execute("interface", "fwmark", "route", "add", iface,
                    dest.getAddress().getHostAddress(), dest.getNetworkPrefixLength());
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearMarkedForwardingRoute(String iface, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            LinkAddress dest = route.getDestination();
            mConnector.execute("interface", "fwmark", "route", "remove", iface,
                    dest.getAddress().getHostAddress(), dest.getNetworkPrefixLength());
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setHostExemption(LinkAddress host) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark", "exempt", "add", host);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearHostExemption(LinkAddress host) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("interface", "fwmark", "exempt", "remove", host);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setDnsInterfaceForUidRange(String iface, int uid_start, int uid_end) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "setifaceforuidrange", iface, uid_start, uid_end);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearDnsInterfaceForUidRange(int uid_start, int uid_end) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "clearifaceforuidrange", uid_start, uid_end);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearDnsInterfaceMaps() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "clearifacemapping");
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

    @Override
    public void setFirewallEnabled(boolean enabled) {
        enforceSystemUid();
        try {
            mConnector.execute("firewall", enabled ? "enable" : "disable");
            mFirewallEnabled = enabled;
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public boolean isFirewallEnabled() {
        enforceSystemUid();
        return mFirewallEnabled;
    }

    @Override
    public void setFirewallInterfaceRule(String iface, boolean allow) {
        enforceSystemUid();
        Preconditions.checkState(mFirewallEnabled);
        final String rule = allow ? ALLOW : DENY;
        try {
            mConnector.execute("firewall", "set_interface_rule", iface, rule);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setFirewallEgressSourceRule(String addr, boolean allow) {
        enforceSystemUid();
        Preconditions.checkState(mFirewallEnabled);
        final String rule = allow ? ALLOW : DENY;
        try {
            mConnector.execute("firewall", "set_egress_source_rule", addr, rule);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setFirewallEgressDestRule(String addr, int port, boolean allow) {
        enforceSystemUid();
        Preconditions.checkState(mFirewallEnabled);
        final String rule = allow ? ALLOW : DENY;
        try {
            mConnector.execute("firewall", "set_egress_dest_rule", addr, port, rule);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setFirewallUidRule(int uid, boolean allow) {
        enforceSystemUid();
        Preconditions.checkState(mFirewallEnabled);
        final String rule = allow ? ALLOW : DENY;
        try {
            mConnector.execute("firewall", "set_uid_rule", uid, rule);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    private static void enforceSystemUid() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException("Only available to AID_SYSTEM");
        }
    }

    @Override
    public void setDnsInterfaceForPid(String iface, int pid) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "setifaceforpid", iface, pid);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native deamon to set interface for pid" + iface, e);
        }
    }

    @Override
    public void clearDnsInterfaceForPid(int pid) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("resolver", "clearifaceforpid", pid);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException(
                    "Error communicating with native deamon to clear interface for pid " + pid, e);
        }
    }

    @Override
    public void startClatd(String interfaceName) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("clatd", "start", interfaceName);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void stopClatd() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("clatd", "stop");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public boolean isClatdStarted() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("clatd", "status");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        event.checkCode(ClatdStatusResult);
        return event.getMessage().endsWith("started");
    }

    /** {@inheritDoc} */
    @Override
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
            pw.print("Active quota ifaces: "); pw.println(mActiveQuotas.toString());
            pw.print("Active alert ifaces: "); pw.println(mActiveAlerts.toString());
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

        pw.print("Firewall enabled: "); pw.println(mFirewallEnabled);
    }
}
