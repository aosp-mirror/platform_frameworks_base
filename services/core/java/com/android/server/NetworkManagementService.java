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
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_NONE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NONE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkPolicyManager.FIREWALL_TYPE_BLACKLIST;
import static android.net.NetworkPolicyManager.FIREWALL_TYPE_WHITELIST;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.UID_TETHERING;
import static com.android.server.NetworkManagementService.NetdResponseCode.ClatdStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceGetCfgResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.InterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.IpFwdStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherDnsFwdTgtListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherInterfaceListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetherStatusResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TetheringStatsListResult;
import static com.android.server.NetworkManagementService.NetdResponseCode.TtyListResult;
import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;
import android.annotation.NonNull;
import android.app.ActivityManagerNative;
import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.INetworkManagementEventObserver;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkPolicyManager;
import android.net.NetworkStats;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.INetworkActivityListener;
import android.os.INetworkManagementService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.util.HexDump;
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
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;

/**
 * @hide
 */
public class NetworkManagementService extends INetworkManagementService.Stub
        implements Watchdog.Monitor {
    private static final String TAG = "NetworkManagement";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String NETD_TAG = "NetdConnector";
    private static final String NETD_SERVICE_NAME = "netd";

    private static final int MAX_UID_RANGES_PER_COMMAND = 10;

    /**
     * Name representing {@link #setGlobalAlert(long)} limit when delivered to
     * {@link INetworkManagementEventObserver#limitReached(String, String)}.
     */
    public static final String LIMIT_GLOBAL_ALERT = "globalAlert";

    /**
     * String to pass to netd to indicate that a network is only accessible
     * to apps that have the CHANGE_NETWORK_STATE permission.
     */
    public static final String PERMISSION_NETWORK = "NETWORK";

    /**
     * String to pass to netd to indicate that a network is only
     * accessible to system apps and those with the CONNECTIVITY_INTERNAL
     * permission.
     */
    public static final String PERMISSION_SYSTEM = "SYSTEM";

    class NetdResponseCode {
        /* Keep in sync with system/netd/server/ResponseCode.h */
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

        public static final int InterfaceChange           = 600;
        public static final int BandwidthControl          = 601;
        public static final int InterfaceClassActivity    = 613;
        public static final int InterfaceAddressChange    = 614;
        public static final int InterfaceDnsServerInfo    = 615;
        public static final int RouteChange               = 616;
        public static final int StrictCleartext           = 617;
    }

    /* Defaults for resolver parameters. */
    public static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    public static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    public static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    public static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;

    /**
     * String indicating a softap command.
     */
    static final String SOFT_AP_COMMAND = "softap";

    /**
     * String passed back to netd connector indicating softap command success.
     */
    static final String SOFT_AP_COMMAND_SUCCESS = "Ok";

    static final int DAEMON_MSG_MOBILE_CONN_REAL_TIME_INFO = 1;

    /**
     * Binder context for this service
     */
    private final Context mContext;

    /**
     * connector object for communicating with netd
     */
    private final NativeDaemonConnector mConnector;

    private final Handler mFgHandler;
    private final Handler mDaemonHandler;

    private INetd mNetdService;

    private IBatteryStats mBatteryStats;

    private final Thread mThread;
    private CountDownLatch mConnectedSignal = new CountDownLatch(1);

    private final RemoteCallbackList<INetworkManagementEventObserver> mObservers =
            new RemoteCallbackList<INetworkManagementEventObserver>();

    private final NetworkStatsFactory mStatsFactory = new NetworkStatsFactory();

    private Object mQuotaLock = new Object();

    /** Set of interfaces with active quotas. */
    @GuardedBy("mQuotaLock")
    private HashMap<String, Long> mActiveQuotas = Maps.newHashMap();
    /** Set of interfaces with active alerts. */
    @GuardedBy("mQuotaLock")
    private HashMap<String, Long> mActiveAlerts = Maps.newHashMap();
    /** Set of UIDs blacklisted on metered networks. */
    @GuardedBy("mQuotaLock")
    private SparseBooleanArray mUidRejectOnMetered = new SparseBooleanArray();
    /** Set of UIDs whitelisted on metered networks. */
    @GuardedBy("mQuotaLock")
    private SparseBooleanArray mUidAllowOnMetered = new SparseBooleanArray();
    /** Set of UIDs with cleartext penalties. */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidCleartextPolicy = new SparseIntArray();
    /** Set of UIDs that are to be blocked/allowed by firewall controller. */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidFirewallRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to application idles.
     */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidFirewallStandbyRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to device idles.
     */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidFirewallDozableRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to device on power-save mode.
     */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidFirewallPowerSaveRules = new SparseIntArray();
    /** Set of states for the child firewall chains. True if the chain is active. */
    @GuardedBy("mQuotaLock")
    final SparseBooleanArray mFirewallChainStates = new SparseBooleanArray();

    @GuardedBy("mQuotaLock")
    private boolean mDataSaverMode;

    private Object mIdleTimerLock = new Object();
    /** Set of interfaces with active idle timers. */
    private static class IdleTimerParams {
        public final int timeout;
        public final int type;
        public int networkCount;

        IdleTimerParams(int timeout, int type) {
            this.timeout = timeout;
            this.type = type;
            this.networkCount = 1;
        }
    }
    private HashMap<String, IdleTimerParams> mActiveIdleTimers = Maps.newHashMap();

    private volatile boolean mBandwidthControlEnabled;
    private volatile boolean mFirewallEnabled;
    private volatile boolean mStrictEnabled;

    private boolean mMobileActivityFromRadio = false;
    private int mLastPowerStateFromRadio = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    private int mLastPowerStateFromWifi = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;

    private final RemoteCallbackList<INetworkActivityListener> mNetworkActivityListeners =
            new RemoteCallbackList<INetworkActivityListener>();
    private boolean mNetworkActive;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    private NetworkManagementService(Context context, String socket) {
        mContext = context;

        // make sure this is on the same looper as our NativeDaemonConnector for sync purposes
        mFgHandler = new Handler(FgThread.get().getLooper());

        // Don't need this wake lock, since we now have a time stamp for when
        // the network actually went inactive.  (It might be nice to still do this,
        // but I don't want to do it through the power manager because that pollutes the
        // battery stats history with pointless noise.)
        //PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null; //pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NETD_TAG);

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), socket, 10, NETD_TAG, 160, wl,
                FgThread.get().getLooper());
        mThread = new Thread(mConnector, NETD_TAG);

        mDaemonHandler = new Handler(FgThread.get().getLooper());

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    static NetworkManagementService create(Context context, String socket)
            throws InterruptedException {
        final NetworkManagementService service = new NetworkManagementService(context, socket);
        final CountDownLatch connectedSignal = service.mConnectedSignal;
        if (DBG) Slog.d(TAG, "Creating NetworkManagementService");
        service.mThread.start();
        if (DBG) Slog.d(TAG, "Awaiting socket connection");
        connectedSignal.await();
        if (DBG) Slog.d(TAG, "Connected");
        service.connectNativeNetdService();
        return service;
    }

    public static NetworkManagementService create(Context context) throws InterruptedException {
        return create(context, NETD_SERVICE_NAME);
    }

    public void systemReady() {
        if (DBG) {
            final long start = System.currentTimeMillis();
            prepareNativeDaemon();
            final long delta = System.currentTimeMillis() - start;
            Slog.d(TAG, "Prepared in " + delta + "ms");
            return;
        } else {
            prepareNativeDaemon();
        }
    }

    private IBatteryStats getBatteryStats() {
        synchronized (this) {
            if (mBatteryStats != null) {
                return mBatteryStats;
            }
            mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                    BatteryStats.SERVICE_NAME));
            return mBatteryStats;
        }
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
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).interfaceStatusChanged(iface, up);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    private void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).interfaceLinkStateChanged(iface, up);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of an interface addition.
     */
    private void notifyInterfaceAdded(String iface) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).interfaceAdded(iface);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
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
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).interfaceRemoved(iface);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of a limit reached.
     */
    private void notifyLimitReached(String limitName, String iface) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).limitReached(limitName, iface);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of a change in the data activity state of the interface
     */
    private void notifyInterfaceClassActivity(int type, int powerState, long tsNanos,
            int uid, boolean fromRadio) {
        final boolean isMobile = ConnectivityManager.isNetworkTypeMobile(type);
        if (isMobile) {
            if (!fromRadio) {
                if (mMobileActivityFromRadio) {
                    // If this call is not coming from a report from the radio itself, but we
                    // have previously received reports from the radio, then we will take the
                    // power state to just be whatever the radio last reported.
                    powerState = mLastPowerStateFromRadio;
                }
            } else {
                mMobileActivityFromRadio = true;
            }
            if (mLastPowerStateFromRadio != powerState) {
                mLastPowerStateFromRadio = powerState;
                try {
                    getBatteryStats().noteMobileRadioPowerState(powerState, tsNanos, uid);
                } catch (RemoteException e) {
                }
            }
        }

        if (ConnectivityManager.isNetworkTypeWifi(type)) {
            if (mLastPowerStateFromWifi != powerState) {
                mLastPowerStateFromWifi = powerState;
                try {
                    getBatteryStats().noteWifiRadioPowerState(powerState, tsNanos, uid);
                } catch (RemoteException e) {
                }
            }
        }

        boolean isActive = powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM
                || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;

        if (!isMobile || fromRadio || !mMobileActivityFromRadio) {
            // Report the change in data activity.  We don't do this if this is a change
            // on the mobile network, that is not coming from the radio itself, and we
            // have previously seen change reports from the radio.  In that case only
            // the radio is the authority for the current state.
            final int length = mObservers.beginBroadcast();
            try {
                for (int i = 0; i < length; i++) {
                    try {
                        mObservers.getBroadcastItem(i).interfaceClassDataActivityChanged(
                                Integer.toString(type), isActive, tsNanos);
                    } catch (RemoteException | RuntimeException e) {
                    }
                }
            } finally {
                mObservers.finishBroadcast();
            }
        }

        boolean report = false;
        synchronized (mIdleTimerLock) {
            if (mActiveIdleTimers.isEmpty()) {
                // If there are no idle timers, we are not monitoring activity, so we
                // are always considered active.
                isActive = true;
            }
            if (mNetworkActive != isActive) {
                mNetworkActive = isActive;
                report = isActive;
            }
        }
        if (report) {
            reportNetworkActive();
        }
    }

    // Sync the state of the given chain with the native daemon.
    private void syncFirewallChainLocked(int chain, SparseIntArray uidFirewallRules, String name) {
        int size = uidFirewallRules.size();
        if (size > 0) {
            // Make a copy of the current rules, and then clear them. This is because
            // setFirewallUidRuleInternal only pushes down rules to the native daemon if they are
            // different from the current rules stored in the mUidFirewall*Rules array for the
            // specified chain. If we don't clear the rules, setFirewallUidRuleInternal will do
            // nothing.
            final SparseIntArray rules = uidFirewallRules.clone();
            uidFirewallRules.clear();

            // Now push the rules. setFirewallUidRuleInternal will push each of these down to the
            // native daemon, and also add them to the mUidFirewall*Rules array for the specified
            // chain.
            if (DBG) Slog.d(TAG, "Pushing " + size + " active firewall " + name + "UID rules");
            for (int i = 0; i < rules.size(); i++) {
                setFirewallUidRuleLocked(chain, rules.keyAt(i), rules.valueAt(i));
            }
        }
    }

    private void connectNativeNetdService() {
        boolean nativeServiceAvailable = false;
        try {
            mNetdService = INetd.Stub.asInterface(ServiceManager.getService(NETD_SERVICE_NAME));
            nativeServiceAvailable = mNetdService.isAlive();
        } catch (RemoteException e) {}
        if (!nativeServiceAvailable) {
            Slog.wtf(TAG, "Can't connect to NativeNetdService " + NETD_SERVICE_NAME);
        }
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
            Slog.i(TAG, "not enabling bandwidth control");
        }

        SystemProperties.set(PROP_QTAGUID_ENABLED, mBandwidthControlEnabled ? "1" : "0");

        if (mBandwidthControlEnabled) {
            try {
                getBatteryStats().noteNetworkStatsEnabled();
            } catch (RemoteException e) {
            }
        }

        try {
            mConnector.execute("strict", "enable");
            mStrictEnabled = true;
        } catch (NativeDaemonConnectorException e) {
            Log.wtf(TAG, "Failed strict enable", e);
        }

        // push any existing quota or UID rules
        synchronized (mQuotaLock) {

            setDataSaverModeEnabled(mDataSaverMode);

            int size = mActiveQuotas.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active quota rules");
                final HashMap<String, Long> activeQuotas = mActiveQuotas;
                mActiveQuotas = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeQuotas.entrySet()) {
                    setInterfaceQuota(entry.getKey(), entry.getValue());
                }
            }

            size = mActiveAlerts.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active alert rules");
                final HashMap<String, Long> activeAlerts = mActiveAlerts;
                mActiveAlerts = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeAlerts.entrySet()) {
                    setInterfaceAlert(entry.getKey(), entry.getValue());
                }
            }

            size = mUidRejectOnMetered.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " UIDs to metered whitelist rules");
                final SparseBooleanArray uidRejectOnQuota = mUidRejectOnMetered;
                mUidRejectOnMetered = new SparseBooleanArray();
                for (int i = 0; i < uidRejectOnQuota.size(); i++) {
                    setUidMeteredNetworkBlacklist(uidRejectOnQuota.keyAt(i),
                            uidRejectOnQuota.valueAt(i));
                }
            }

            size = mUidAllowOnMetered.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " UIDs to metered blacklist rules");
                final SparseBooleanArray uidAcceptOnQuota = mUidAllowOnMetered;
                mUidAllowOnMetered = new SparseBooleanArray();
                for (int i = 0; i < uidAcceptOnQuota.size(); i++) {
                    setUidMeteredNetworkWhitelist(uidAcceptOnQuota.keyAt(i),
                            uidAcceptOnQuota.valueAt(i));
                }
            }

            size = mUidCleartextPolicy.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active UID cleartext policies");
                final SparseIntArray local = mUidCleartextPolicy;
                mUidCleartextPolicy = new SparseIntArray();
                for (int i = 0; i < local.size(); i++) {
                    setUidCleartextNetworkPolicy(local.keyAt(i), local.valueAt(i));
                }
            }

            setFirewallEnabled(mFirewallEnabled || LockdownVpnTracker.isEnabled());

            syncFirewallChainLocked(FIREWALL_CHAIN_NONE, mUidFirewallRules, "");
            syncFirewallChainLocked(FIREWALL_CHAIN_STANDBY, mUidFirewallStandbyRules, "standby ");
            syncFirewallChainLocked(FIREWALL_CHAIN_DOZABLE, mUidFirewallDozableRules, "dozable ");
            syncFirewallChainLocked(FIREWALL_CHAIN_POWERSAVE, mUidFirewallPowerSaveRules,
                    "powersave ");

            if (mFirewallChainStates.get(FIREWALL_CHAIN_STANDBY)) {
                setFirewallChainEnabled(FIREWALL_CHAIN_STANDBY, true);
            }
            if (mFirewallChainStates.get(FIREWALL_CHAIN_DOZABLE)) {
                setFirewallChainEnabled(FIREWALL_CHAIN_DOZABLE, true);
            }
            if (mFirewallChainStates.get(FIREWALL_CHAIN_POWERSAVE)) {
                setFirewallChainEnabled(FIREWALL_CHAIN_POWERSAVE, true);
            }
        }
    }

    /**
     * Notify our observers of a new or updated interface address.
     */
    private void notifyAddressUpdated(String iface, LinkAddress address) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).addressUpdated(iface, address);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of a deleted interface address.
     */
    private void notifyAddressRemoved(String iface, LinkAddress address) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).addressRemoved(iface, address);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of DNS server information received.
     */
    private void notifyInterfaceDnsServerInfo(String iface, long lifetime, String[] addresses) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mObservers.getBroadcastItem(i).interfaceDnsServerInfo(iface, lifetime,
                        addresses);
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of a route change.
     */
    private void notifyRouteChange(String action, RouteInfo route) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    if (action.equals("updated")) {
                        mObservers.getBroadcastItem(i).routeUpdated(route);
                    } else {
                        mObservers.getBroadcastItem(i).routeRemoved(route);
                    }
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    //
    // Netd Callback handling
    //

    private class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        @Override
        public void onDaemonConnected() {
            Slog.i(TAG, "onDaemonConnected()");
            // event is dispatched from internal NDC thread, so we prepare the
            // daemon back on main thread.
            if (mConnectedSignal != null) {
                // The system is booting and we're connecting to netd for the first time.
                mConnectedSignal.countDown();
                mConnectedSignal = null;
            } else {
                // We're reconnecting to netd after the socket connection
                // was interrupted (e.g., if it crashed).
                mFgHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        connectNativeNetdService();
                        prepareNativeDaemon();
                    }
                });
            }
        }

        @Override
        public boolean onCheckHoldWakeLock(int code) {
            return code == NetdResponseCode.InterfaceClassActivity;
        }

        @Override
        public boolean onEvent(int code, String raw, String[] cooked) {
            String errorMessage = String.format("Invalid event from daemon (%s)", raw);
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
                        throw new IllegalStateException(errorMessage);
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
                    throw new IllegalStateException(errorMessage);
                    // break;
            case NetdResponseCode.BandwidthControl:
                    /*
                     * Bandwidth control needs some attention
                     * Format: "NNN limit alert <alertName> <ifaceName>"
                     */
                    if (cooked.length < 5 || !cooked[1].equals("limit")) {
                        throw new IllegalStateException(errorMessage);
                    }
                    if (cooked[2].equals("alert")) {
                        notifyLimitReached(cooked[3], cooked[4]);
                        return true;
                    }
                    throw new IllegalStateException(errorMessage);
                    // break;
            case NetdResponseCode.InterfaceClassActivity:
                    /*
                     * An network interface class state changed (active/idle)
                     * Format: "NNN IfaceClass <active/idle> <label>"
                     */
                    if (cooked.length < 4 || !cooked[1].equals("IfaceClass")) {
                        throw new IllegalStateException(errorMessage);
                    }
                    long timestampNanos = 0;
                    int processUid = -1;
                    if (cooked.length >= 5) {
                        try {
                            timestampNanos = Long.parseLong(cooked[4]);
                            if (cooked.length == 6) {
                                processUid = Integer.parseInt(cooked[5]);
                            }
                        } catch(NumberFormatException ne) {}
                    } else {
                        timestampNanos = SystemClock.elapsedRealtimeNanos();
                    }
                    boolean isActive = cooked[2].equals("active");
                    notifyInterfaceClassActivity(Integer.parseInt(cooked[3]),
                            isActive ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                            : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW,
                            timestampNanos, processUid, false);
                    return true;
                    // break;
            case NetdResponseCode.InterfaceAddressChange:
                    /*
                     * A network address change occurred
                     * Format: "NNN Address updated <addr> <iface> <flags> <scope>"
                     *         "NNN Address removed <addr> <iface> <flags> <scope>"
                     */
                    if (cooked.length < 7 || !cooked[1].equals("Address")) {
                        throw new IllegalStateException(errorMessage);
                    }

                    String iface = cooked[4];
                    LinkAddress address;
                    try {
                        int flags = Integer.parseInt(cooked[5]);
                        int scope = Integer.parseInt(cooked[6]);
                        address = new LinkAddress(cooked[3], flags, scope);
                    } catch(NumberFormatException e) {     // Non-numeric lifetime or scope.
                        throw new IllegalStateException(errorMessage, e);
                    } catch(IllegalArgumentException e) {  // Malformed/invalid IP address.
                        throw new IllegalStateException(errorMessage, e);
                    }

                    if (cooked[2].equals("updated")) {
                        notifyAddressUpdated(iface, address);
                    } else {
                        notifyAddressRemoved(iface, address);
                    }
                    return true;
                    // break;
            case NetdResponseCode.InterfaceDnsServerInfo:
                    /*
                     * Information about available DNS servers has been received.
                     * Format: "NNN DnsInfo servers <interface> <lifetime> <servers>"
                     */
                    long lifetime;  // Actually a 32-bit unsigned integer.

                    if (cooked.length == 6 &&
                        cooked[1].equals("DnsInfo") &&
                        cooked[2].equals("servers")) {
                        try {
                            lifetime = Long.parseLong(cooked[4]);
                        } catch (NumberFormatException e) {
                            throw new IllegalStateException(errorMessage);
                        }
                        String[] servers = cooked[5].split(",");
                        notifyInterfaceDnsServerInfo(cooked[3], lifetime, servers);
                    }
                    return true;
                    // break;
            case NetdResponseCode.RouteChange:
                    /*
                     * A route has been updated or removed.
                     * Format: "NNN Route <updated|removed> <dst> [via <gateway] [dev <iface>]"
                     */
                    if (!cooked[1].equals("Route") || cooked.length < 6) {
                        throw new IllegalStateException(errorMessage);
                    }

                    String via = null;
                    String dev = null;
                    boolean valid = true;
                    for (int i = 4; (i + 1) < cooked.length && valid; i += 2) {
                        if (cooked[i].equals("dev")) {
                            if (dev == null) {
                                dev = cooked[i+1];
                            } else {
                                valid = false;  // Duplicate interface.
                            }
                        } else if (cooked[i].equals("via")) {
                            if (via == null) {
                                via = cooked[i+1];
                            } else {
                                valid = false;  // Duplicate gateway.
                            }
                        } else {
                            valid = false;      // Unknown syntax.
                        }
                    }
                    if (valid) {
                        try {
                            // InetAddress.parseNumericAddress(null) inexplicably returns ::1.
                            InetAddress gateway = null;
                            if (via != null) gateway = InetAddress.parseNumericAddress(via);
                            RouteInfo route = new RouteInfo(new IpPrefix(cooked[3]), gateway, dev);
                            notifyRouteChange(cooked[2], route);
                            return true;
                        } catch (IllegalArgumentException e) {}
                    }
                    throw new IllegalStateException(errorMessage);
                    // break;
            case NetdResponseCode.StrictCleartext:
                final int uid = Integer.parseInt(cooked[1]);
                final byte[] firstPacket = HexDump.hexStringToByteArray(cooked[2]);
                try {
                    ActivityManagerNative.getDefault().notifyCleartextNetwork(uid, firstPacket);
                } catch (RemoteException ignored) {
                }
                break;
            default: break;
            }
            return false;
        }
    }


    //
    // INetworkManagementService members
    //
    @Override
    public INetd getNetdService() throws RemoteException {
        final CountDownLatch connectedSignal = mConnectedSignal;
        if (connectedSignal != null) {
            try {
                connectedSignal.await();
            } catch (InterruptedException ignored) {}
        }

        return mNetdService;
    }

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
                linkAddr.getPrefixLength());
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
    public void setInterfaceIpv6NdOffload(String iface, boolean enable) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute(
                    "interface", "ipv6ndoffload", iface, (enable ? "enable" : "disable"));
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addRoute(int netId, RouteInfo route) {
        modifyRoute("add", "" + netId, route);
    }

    @Override
    public void removeRoute(int netId, RouteInfo route) {
        modifyRoute("remove", "" + netId, route);
    }

    private void modifyRoute(String action, String netId, RouteInfo route) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final Command cmd = new Command("network", "route", action, netId);

        // create triplet: interface dest-ip-addr/prefixlength gateway-ip-addr
        cmd.appendArg(route.getInterface());
        cmd.appendArg(route.getDestination().toString());

        switch (route.getType()) {
            case RouteInfo.RTN_UNICAST:
                if (route.hasGateway()) {
                    cmd.appendArg(route.getGateway().getHostAddress());
                }
                break;
            case RouteInfo.RTN_UNREACHABLE:
                cmd.appendArg("unreachable");
                break;
            case RouteInfo.RTN_THROW:
                cmd.appendArg("throw");
                break;
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

        Slog.i(TAG, "Shutting down");
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
            mConnector.execute("ipfwd", enable ? "enable" : "disable", "tethering");
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
        List<RouteInfo> routes = new ArrayList<RouteInfo>();
        // The RouteInfo constructor truncates the LinkAddress to a network prefix, thus making it
        // suitable to use as a route destination.
        routes.add(new RouteInfo(getInterfaceConfig(iface).getLinkAddress(), null, iface));
        addInterfaceToLocalNetwork(iface, routes);
    }

    @Override
    public void untetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("tether", "interface", "remove", iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } finally {
            removeInterfaceFromLocalNetwork(iface);
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
    public void setDnsForwarders(Network network, String[] dns) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        int netId = (network != null) ? network.netId : ConnectivityManager.NETID_UNSET;
        final Command cmd = new Command("tether", "dns", "set", netId);

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

    private List<InterfaceAddress> excludeLinkLocal(List<InterfaceAddress> addresses) {
        ArrayList<InterfaceAddress> filtered = new ArrayList<InterfaceAddress>(addresses.size());
        for (InterfaceAddress ia : addresses) {
            if (!ia.getAddress().isLinkLocalAddress())
                filtered.add(ia);
        }
        return filtered;
    }

    private void modifyInterfaceForward(boolean add, String fromIface, String toIface) {
        final Command cmd = new Command("ipfwd", add ? "add" : "remove", fromIface, toIface);
        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void startInterfaceForwarding(String fromIface, String toIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyInterfaceForward(true, fromIface, toIface);
    }

    @Override
    public void stopInterfaceForwarding(String fromIface, String toIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        modifyInterfaceForward(false, fromIface, toIface);
    }

    private void modifyNat(String action, String internalInterface, String externalInterface)
            throws SocketException {
        final Command cmd = new Command("nat", action, internalInterface, externalInterface);

        final NetworkInterface internalNetworkInterface = NetworkInterface.getByName(
                internalInterface);
        if (internalNetworkInterface == null) {
            cmd.appendArg("0");
        } else {
            // Don't touch link-local routes, as link-local addresses aren't routable,
            // kernel creates link-local routes on all interfaces automatically
            List<InterfaceAddress> interfaceAddresses = excludeLinkLocal(
                    internalNetworkInterface.getInterfaceAddresses());
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

    /**
     * Private method used to call execute for a command given the provided arguments.
     *
     * This function checks the returned NativeDaemonEvent for the provided expected response code
     * and message.  If either of these is not correct, an error is logged.
     *
     * @param String command The command to execute.
     * @param Object[] args If needed, arguments for the command to execute.
     * @param int expectedResponseCode The code expected to be returned in the corresponding event.
     * @param String expectedResponseMessage The message expected in the returned event.
     * @param String logMsg The message to log as an error (TAG will be applied).
     */
    private void executeOrLogWithMessage(String command, Object[] args,
            int expectedResponseCode, String expectedResponseMessage, String logMsg)
            throws NativeDaemonConnectorException {
        NativeDaemonEvent event = mConnector.execute(command, args);
        if (event.getCode() != expectedResponseCode
                || !event.getMessage().equals(expectedResponseMessage)) {
            Log.e(TAG, logMsg + ": event = " + event);
        }
    }

    @Override
    public void startAccessPoint(WifiConfiguration wifiConfig, String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        Object[] args;
        String logMsg = "startAccessPoint Error setting up softap";
        try {
            if (wifiConfig == null) {
                args = new Object[] {"set", wlanIface};
            } else {
                args = new Object[] {"set", wlanIface, wifiConfig.SSID,
                        "broadcast", Integer.toString(wifiConfig.apChannel),
                        getSecurityType(wifiConfig), new SensitiveArg(wifiConfig.preSharedKey)};
            }
            executeOrLogWithMessage(SOFT_AP_COMMAND, args, NetdResponseCode.SoftapStatusResult,
                    SOFT_AP_COMMAND_SUCCESS, logMsg);

            logMsg = "startAccessPoint Error starting softap";
            args = new Object[] {"startap"};
            executeOrLogWithMessage(SOFT_AP_COMMAND, args, NetdResponseCode.SoftapStatusResult,
                    SOFT_AP_COMMAND_SUCCESS, logMsg);
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
        Object[] args = {"fwreload", wlanIface, mode};
        String logMsg = "wifiFirmwareReload Error reloading "
                + wlanIface + " fw in " + mode + " mode";
        try {
            executeOrLogWithMessage(SOFT_AP_COMMAND, args, NetdResponseCode.SoftapStatusResult,
                    SOFT_AP_COMMAND_SUCCESS, logMsg);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        // Ensure that before we return from this command, any asynchronous
        // notifications generated before the command completed have been
        // processed by all NetworkManagementEventObservers.
        mConnector.waitForCallbacks();
    }

    @Override
    public void stopAccessPoint(String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        Object[] args = {"stopap"};
        String logMsg = "stopAccessPoint Error stopping softap";

        try {
            executeOrLogWithMessage(SOFT_AP_COMMAND, args, NetdResponseCode.SoftapStatusResult,
                    SOFT_AP_COMMAND_SUCCESS, logMsg);
            wifiFirmwareReload(wlanIface, "STA");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setAccessPoint(WifiConfiguration wifiConfig, String wlanIface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        Object[] args;
        String logMsg = "startAccessPoint Error setting up softap";
        try {
            if (wifiConfig == null) {
                args = new Object[] {"set", wlanIface};
            } else {
                // TODO: understand why this is set to "6" instead of
                // Integer.toString(wifiConfig.apChannel) as in startAccessPoint
                // TODO: should startAccessPoint call this instead of repeating code?
                args = new Object[] {"set", wlanIface, wifiConfig.SSID,
                        "broadcast", "6",
                        getSecurityType(wifiConfig), new SensitiveArg(wifiConfig.preSharedKey)};
            }
            executeOrLogWithMessage(SOFT_AP_COMMAND, args, NetdResponseCode.SoftapStatusResult,
                    SOFT_AP_COMMAND_SUCCESS, logMsg);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addIdleTimer(String iface, int timeout, final int type) {
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
                mConnector.execute("idletimer", "add", iface, Integer.toString(timeout),
                        Integer.toString(type));
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
            mActiveIdleTimers.put(iface, new IdleTimerParams(timeout, type));

            // Networks start up.
            if (ConnectivityManager.isNetworkTypeMobile(type)) {
                mNetworkActive = false;
            }
            mDaemonHandler.post(new Runnable() {
                @Override public void run() {
                    notifyInterfaceClassActivity(type,
                            DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH,
                            SystemClock.elapsedRealtimeNanos(), -1, false);
                }
            });
        }
    }

    @Override
    public void removeIdleTimer(String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        if (DBG) Slog.d(TAG, "Removing idletimer");

        synchronized (mIdleTimerLock) {
            final IdleTimerParams params = mActiveIdleTimers.get(iface);
            if (params == null || --(params.networkCount) > 0) {
                return;
            }

            try {
                mConnector.execute("idletimer", "remove", iface,
                        Integer.toString(params.timeout), Integer.toString(params.type));
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
            mActiveIdleTimers.remove(iface);
            mDaemonHandler.post(new Runnable() {
                @Override public void run() {
                    notifyInterfaceClassActivity(params.type,
                            DataConnectionRealTimeInfo.DC_POWER_STATE_LOW,
                            SystemClock.elapsedRealtimeNanos(), -1, false);
                }
            });
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
            return mStatsFactory.readNetworkStatsDetail(UID_ALL, null, TAG_ALL, null);
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

    private void setUidOnMeteredNetworkList(SparseBooleanArray quotaList, int uid,
            boolean blacklist, boolean enable) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        // silently discard when control disabled
        // TODO: eventually migrate to be always enabled
        if (!mBandwidthControlEnabled) return;

        final String chain = blacklist ? "naughtyapps" : "niceapps";
        final String suffix = enable ? "add" : "remove";

        synchronized (mQuotaLock) {
            final boolean oldEnable = quotaList.get(uid, false);
            if (oldEnable == enable) {
                // TODO: eventually consider throwing
                return;
            }

            try {
                mConnector.execute("bandwidth", suffix + chain, uid);
                if (enable) {
                    quotaList.put(uid, true);
                } else {
                    quotaList.delete(uid);
                }
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    @Override
    public void setUidMeteredNetworkBlacklist(int uid, boolean enable) {
        setUidOnMeteredNetworkList(mUidRejectOnMetered, uid, true, enable);
    }

    @Override
    public void setUidMeteredNetworkWhitelist(int uid, boolean enable) {
        setUidOnMeteredNetworkList(mUidAllowOnMetered, uid, false, enable);
    }

    @Override
    public boolean setDataSaverModeEnabled(boolean enable) {
        if (DBG) Log.d(TAG, "setDataSaverMode: " + enable);
        synchronized (mQuotaLock) {
            if (mDataSaverMode == enable) {
                Log.w(TAG, "setDataSaverMode(): already " + mDataSaverMode);
                return true;
            }
            try {
                final boolean changed = mNetdService.bandwidthEnableDataSaver(enable);
                if (changed) {
                    mDataSaverMode = enable;
                } else {
                    Log.w(TAG, "setDataSaverMode(" + enable + "): netd command silently failed");
                }
                return changed;
            } catch (RemoteException e) {
                Log.w(TAG, "setDataSaverMode(" + enable + "): netd command failed", e);
                return false;
            }
        }
    }

    @Override
    public void setAllowOnlyVpnForUids(boolean add, UidRange[] uidRanges)
            throws ServiceSpecificException {
        try {
            mNetdService.networkRejectNonSecureVpn(add, uidRanges);
        } catch (ServiceSpecificException e) {
            Log.w(TAG, "setAllowOnlyVpnForUids(" + add + ", " + Arrays.toString(uidRanges) + ")"
                    + ": netd command failed", e);
            throw e;
        } catch (RemoteException e) {
            Log.w(TAG, "setAllowOnlyVpnForUids(" + add + ", " + Arrays.toString(uidRanges) + ")"
                    + ": netd command failed", e);
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void setUidCleartextNetworkPolicy(int uid, int policy) {
        if (Binder.getCallingUid() != uid) {
            mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        }

        synchronized (mQuotaLock) {
            final int oldPolicy = mUidCleartextPolicy.get(uid, StrictMode.NETWORK_POLICY_ACCEPT);
            if (oldPolicy == policy) {
                return;
            }

            if (!mStrictEnabled) {
                // Module isn't enabled yet; stash the requested policy away to
                // apply later once the daemon is connected.
                mUidCleartextPolicy.put(uid, policy);
                return;
            }

            final String policyString;
            switch (policy) {
                case StrictMode.NETWORK_POLICY_ACCEPT:
                    policyString = "accept";
                    break;
                case StrictMode.NETWORK_POLICY_LOG:
                    policyString = "log";
                    break;
                case StrictMode.NETWORK_POLICY_REJECT:
                    policyString = "reject";
                    break;
                default:
                    throw new IllegalArgumentException("Unknown policy " + policy);
            }

            try {
                mConnector.execute("strict", "set_uid_cleartext_policy", uid, policyString);
                mUidCleartextPolicy.put(uid, policy);
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
            return mStatsFactory.readNetworkStatsDetail(uid, null, TAG_ALL, null);
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
    public void setDnsConfigurationForNetwork(int netId, String[] servers, String domains) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        ContentResolver resolver = mContext.getContentResolver();

        int sampleValidity = Settings.Global.getInt(resolver,
                Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
        if (sampleValidity < 0 || sampleValidity > 65535) {
            Slog.w(TAG, "Invalid sampleValidity=" + sampleValidity + ", using default=" +
                    DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
            sampleValidity = DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS;
        }

        int successThreshold = Settings.Global.getInt(resolver,
                Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
        if (successThreshold < 0 || successThreshold > 100) {
            Slog.w(TAG, "Invalid successThreshold=" + successThreshold + ", using default=" +
                    DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
            successThreshold = DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT;
        }

        int minSamples = Settings.Global.getInt(resolver,
                Settings.Global.DNS_RESOLVER_MIN_SAMPLES, DNS_RESOLVER_DEFAULT_MIN_SAMPLES);
        int maxSamples = Settings.Global.getInt(resolver,
                Settings.Global.DNS_RESOLVER_MAX_SAMPLES, DNS_RESOLVER_DEFAULT_MAX_SAMPLES);
        if (minSamples < 0 || minSamples > maxSamples || maxSamples > 64) {
            Slog.w(TAG, "Invalid sample count (min, max)=(" + minSamples + ", " + maxSamples +
                    "), using default=(" + DNS_RESOLVER_DEFAULT_MIN_SAMPLES + ", " +
                    DNS_RESOLVER_DEFAULT_MAX_SAMPLES + ")");
            minSamples = DNS_RESOLVER_DEFAULT_MIN_SAMPLES;
            maxSamples = DNS_RESOLVER_DEFAULT_MAX_SAMPLES;
        }

        final String[] domainStrs = domains == null ? new String[0] : domains.split(" ");
        final int[] params = { sampleValidity, successThreshold, minSamples, maxSamples };
        try {
            mNetdService.setResolverConfiguration(netId, servers, domainStrs, params);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setDnsServersForNetwork(int netId, String[] servers, String domains) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        Command cmd;
        if (servers.length > 0) {
            cmd = new Command("resolver", "setnetdns", netId,
                    (domains == null ? "" : domains));
            for (String s : servers) {
                InetAddress a = NetworkUtils.numericToInetAddress(s);
                if (a.isAnyLocalAddress() == false) {
                    cmd.appendArg(a.getHostAddress());
                }
            }
        } else {
            cmd = new Command("resolver", "clearnetdns", netId);
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addVpnUidRanges(int netId, UidRange[] ranges) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        Object[] argv = new Object[3 + MAX_UID_RANGES_PER_COMMAND];
        argv[0] = "users";
        argv[1] = "add";
        argv[2] = netId;
        int argc = 3;
        // Avoid overly long commands by limiting number of UID ranges per command.
        for (int i = 0; i < ranges.length; i++) {
            argv[argc++] = ranges[i].toString();
            if (i == (ranges.length - 1) || argc == argv.length) {
                try {
                    mConnector.execute("network", Arrays.copyOf(argv, argc));
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
                argc = 3;
            }
        }
    }

    @Override
    public void removeVpnUidRanges(int netId, UidRange[] ranges) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        Object[] argv = new Object[3 + MAX_UID_RANGES_PER_COMMAND];
        argv[0] = "users";
        argv[1] = "remove";
        argv[2] = netId;
        int argc = 3;
        // Avoid overly long commands by limiting number of UID ranges per command.
        for (int i = 0; i < ranges.length; i++) {
            argv[argc++] = ranges[i].toString();
            if (i == (ranges.length - 1) || argc == argv.length) {
                try {
                    mConnector.execute("network", Arrays.copyOf(argv, argc));
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
                argc = 3;
            }
        }
    }

    @Override
    public void setFirewallEnabled(boolean enabled) {
        enforceSystemUid();
        try {
            mConnector.execute("firewall", "enable", enabled ? "whitelist" : "blacklist");
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
        final String rule = allow ? "allow" : "deny";
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
        final String rule = allow ? "allow" : "deny";
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
        final String rule = allow ? "allow" : "deny";
        try {
            mConnector.execute("firewall", "set_egress_dest_rule", addr, port, rule);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    private void closeSocketsForFirewallChainLocked(int chain, String chainName) {
        // UID ranges to close sockets on.
        UidRange[] ranges;
        // UID ranges whose sockets we won't touch.
        int[] exemptUids;

        final SparseIntArray rules = getUidFirewallRules(chain);
        int numUids = 0;

        if (getFirewallType(chain) == FIREWALL_TYPE_WHITELIST) {
            // Close all sockets on all non-system UIDs...
            ranges = new UidRange[] {
                // TODO: is there a better way of finding all existing users? If so, we could
                // specify their ranges here.
                new UidRange(Process.FIRST_APPLICATION_UID, Integer.MAX_VALUE),
            };
            // ... except for the UIDs that have allow rules.
            exemptUids = new int[rules.size()];
            for (int i = 0; i < exemptUids.length; i++) {
                if (rules.valueAt(i) == NetworkPolicyManager.FIREWALL_RULE_ALLOW) {
                    exemptUids[numUids] = rules.keyAt(i);
                    numUids++;
                }
            }
            // Normally, whitelist chains only contain deny rules, so numUids == exemptUids.length.
            // But the code does not guarantee this in any way, and at least in one case - if we add
            // a UID rule to the firewall, and then disable the firewall - the chains can contain
            // the wrong type of rule. In this case, don't close connections that we shouldn't.
            //
            // TODO: tighten up this code by ensuring we never set the wrong type of rule, and
            // fix setFirewallEnabled to grab mQuotaLock and clear rules.
            if (numUids != exemptUids.length) {
                exemptUids = Arrays.copyOf(exemptUids, numUids);
            }
        } else {
            // Close sockets for every UID that has a deny rule...
            ranges = new UidRange[rules.size()];
            for (int i = 0; i < ranges.length; i++) {
                if (rules.valueAt(i) == NetworkPolicyManager.FIREWALL_RULE_DENY) {
                    int uid = rules.keyAt(i);
                    ranges[numUids] = new UidRange(uid, uid);
                    numUids++;
                }
            }
            // As above; usually numUids == ranges.length, but not always.
            if (numUids != ranges.length) {
                ranges = Arrays.copyOf(ranges, numUids);
            }
            // ... with no exceptions.
            exemptUids = new int[0];
        }

        try {
            mNetdService.socketDestroy(ranges, exemptUids);
        } catch(RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error closing sockets after enabling chain " + chainName + ": " + e);
        }
    }

    @Override
    public void setFirewallChainEnabled(int chain, boolean enable) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            if (mFirewallChainStates.get(chain) == enable) {
                // All is the same, nothing to do.  This relies on the fact that netd has child
                // chains default detached.
                return;
            }
            mFirewallChainStates.put(chain, enable);

            final String operation = enable ? "enable_chain" : "disable_chain";
            final String chainName;
            switch(chain) {
                case FIREWALL_CHAIN_STANDBY:
                    chainName = FIREWALL_CHAIN_NAME_STANDBY;
                    break;
                case FIREWALL_CHAIN_DOZABLE:
                    chainName = FIREWALL_CHAIN_NAME_DOZABLE;
                    break;
                case FIREWALL_CHAIN_POWERSAVE:
                    chainName = FIREWALL_CHAIN_NAME_POWERSAVE;
                    break;
                default:
                    throw new IllegalArgumentException("Bad child chain: " + chain);
            }

            try {
                mConnector.execute("firewall", operation, chainName);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }

            // Close any sockets that were opened by the affected UIDs. This has to be done after
            // disabling network connectivity, in case they react to the socket close by reopening
            // the connection and race with the iptables commands that enable the firewall. All
            // whitelist and blacklist chains allow RSTs through.
            if (enable) {
                if (DBG) Slog.d(TAG, "Closing sockets after enabling chain " + chainName);
                closeSocketsForFirewallChainLocked(chain, chainName);
            }
        }
    }

    private int getFirewallType(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_TYPE_BLACKLIST;
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_TYPE_WHITELIST;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_TYPE_WHITELIST;
            default:
                return isFirewallEnabled() ? FIREWALL_TYPE_WHITELIST : FIREWALL_TYPE_BLACKLIST;
        }
    }

    @Override
    public void setFirewallUidRules(int chain, int[] uids, int[] rules) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            SparseIntArray uidFirewallRules = getUidFirewallRules(chain);
            SparseIntArray newRules = new SparseIntArray();
            // apply new set of rules
            for (int index = uids.length - 1; index >= 0; --index) {
                int uid = uids[index];
                int rule = rules[index];
                updateFirewallUidRuleLocked(chain, uid, rule);
                newRules.put(uid, rule);
            }
            // collect the rules to remove.
            SparseIntArray rulesToRemove = new SparseIntArray();
            for (int index = uidFirewallRules.size() - 1; index >= 0; --index) {
                int uid = uidFirewallRules.keyAt(index);
                if (newRules.indexOfKey(uid) < 0) {
                    rulesToRemove.put(uid, FIREWALL_RULE_DEFAULT);
                }
            }
            // remove dead rules
            for (int index = rulesToRemove.size() - 1; index >= 0; --index) {
                int uid = rulesToRemove.keyAt(index);
                updateFirewallUidRuleLocked(chain, uid, FIREWALL_RULE_DEFAULT);
            }
            try {
                switch (chain) {
                    case FIREWALL_CHAIN_DOZABLE:
                        mNetdService.firewallReplaceUidChain("fw_dozable", true, uids);
                        break;
                    case FIREWALL_CHAIN_STANDBY:
                        mNetdService.firewallReplaceUidChain("fw_standby", false, uids);
                        break;
                    case FIREWALL_CHAIN_POWERSAVE:
                        mNetdService.firewallReplaceUidChain("fw_powersave", true, uids);
                        break;
                    case FIREWALL_CHAIN_NONE:
                    default:
                        Slog.d(TAG, "setFirewallUidRules() called on invalid chain: " + chain);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Error flushing firewall chain " + chain, e);
            }
        }
    }

    @Override
    public void setFirewallUidRule(int chain, int uid, int rule) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            setFirewallUidRuleLocked(chain, uid, rule);
        }
    }

    private void setFirewallUidRuleLocked(int chain, int uid, int rule) {
        if (updateFirewallUidRuleLocked(chain, uid, rule)) {
            try {
                mConnector.execute("firewall", "set_uid_rule", getFirewallChainName(chain), uid,
                        getFirewallRuleName(chain, rule));
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }
    }

    // TODO: now that netd supports batching, NMS should not keep these data structures anymore...
    private boolean updateFirewallUidRuleLocked(int chain, int uid, int rule) {
        SparseIntArray uidFirewallRules = getUidFirewallRules(chain);

        final int oldUidFirewallRule = uidFirewallRules.get(uid, FIREWALL_RULE_DEFAULT);
        if (DBG) {
            Slog.d(TAG, "oldRule = " + oldUidFirewallRule
                    + ", newRule=" + rule + " for uid=" + uid + " on chain " + chain);
        }
        if (oldUidFirewallRule == rule) {
            if (DBG) Slog.d(TAG, "!!!!! Skipping change");
            // TODO: eventually consider throwing
            return false;
        }

        String ruleName = getFirewallRuleName(chain, rule);
        String oldRuleName = getFirewallRuleName(chain, oldUidFirewallRule);

        if (rule == NetworkPolicyManager.FIREWALL_RULE_DEFAULT) {
            uidFirewallRules.delete(uid);
        } else {
            uidFirewallRules.put(uid, rule);
        }
        return !ruleName.equals(oldRuleName);
    }

    private @NonNull String getFirewallRuleName(int chain, int rule) {
        String ruleName;
        if (getFirewallType(chain) == FIREWALL_TYPE_WHITELIST) {
            if (rule == NetworkPolicyManager.FIREWALL_RULE_ALLOW) {
                ruleName = "allow";
            } else {
                ruleName = "deny";
            }
        } else { // Blacklist mode
            if (rule == NetworkPolicyManager.FIREWALL_RULE_DENY) {
                ruleName = "deny";
            } else {
                ruleName = "allow";
            }
        }
        return ruleName;
    }

    private @NonNull SparseIntArray getUidFirewallRules(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return mUidFirewallStandbyRules;
            case FIREWALL_CHAIN_DOZABLE:
                return mUidFirewallDozableRules;
            case FIREWALL_CHAIN_POWERSAVE:
                return mUidFirewallPowerSaveRules;
            case FIREWALL_CHAIN_NONE:
                return mUidFirewallRules;
            default:
                throw new IllegalArgumentException("Unknown chain:" + chain);
        }
    }

    public @NonNull String getFirewallChainName(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_CHAIN_NAME_STANDBY;
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_CHAIN_NAME_DOZABLE;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_CHAIN_NAME_POWERSAVE;
            case FIREWALL_CHAIN_NONE:
                return FIREWALL_CHAIN_NAME_NONE;
            default:
                throw new IllegalArgumentException("Unknown chain:" + chain);
        }
    }

    private static void enforceSystemUid() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException("Only available to AID_SYSTEM");
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
    public void stopClatd(String interfaceName) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("clatd", "stop", interfaceName);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public boolean isClatdStarted(String interfaceName) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("clatd", "status", interfaceName);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }

        event.checkCode(ClatdStatusResult);
        return event.getMessage().endsWith("started");
    }

    @Override
    public void registerNetworkActivityListener(INetworkActivityListener listener) {
        mNetworkActivityListeners.register(listener);
    }

    @Override
    public void unregisterNetworkActivityListener(INetworkActivityListener listener) {
        mNetworkActivityListeners.unregister(listener);
    }

    @Override
    public boolean isNetworkActive() {
        synchronized (mNetworkActivityListeners) {
            return mNetworkActive || mActiveIdleTimers.isEmpty();
        }
    }

    private void reportNetworkActive() {
        final int length = mNetworkActivityListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mNetworkActivityListeners.getBroadcastItem(i).onNetworkActive();
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mNetworkActivityListeners.finishBroadcast();
        }
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
        pw.print("mMobileActivityFromRadio="); pw.print(mMobileActivityFromRadio);
                pw.print(" mLastPowerStateFromRadio="); pw.println(mLastPowerStateFromRadio);
        pw.print("mNetworkActive="); pw.println(mNetworkActive);

        synchronized (mQuotaLock) {
            pw.print("Active quota ifaces: "); pw.println(mActiveQuotas.toString());
            pw.print("Active alert ifaces: "); pw.println(mActiveAlerts.toString());
            pw.print("Data saver mode: "); pw.println(mDataSaverMode);
            dumpUidRuleOnQuotaLocked(pw, "blacklist", mUidRejectOnMetered);
            dumpUidRuleOnQuotaLocked(pw, "whitelist", mUidAllowOnMetered);
        }

        synchronized (mUidFirewallRules) {
            dumpUidFirewallRule(pw, "", mUidFirewallRules);
        }

        pw.print("UID firewall standby chain enabled: "); pw.println(
                mFirewallChainStates.get(FIREWALL_CHAIN_STANDBY));
        synchronized (mUidFirewallStandbyRules) {
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_STANDBY, mUidFirewallStandbyRules);
        }

        pw.print("UID firewall dozable chain enabled: "); pw.println(
                mFirewallChainStates.get(FIREWALL_CHAIN_DOZABLE));
        synchronized (mUidFirewallDozableRules) {
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_DOZABLE, mUidFirewallDozableRules);
        }

        pw.println("UID firewall powersave chain enabled: " +
                mFirewallChainStates.get(FIREWALL_CHAIN_POWERSAVE));
        synchronized (mUidFirewallPowerSaveRules) {
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_POWERSAVE, mUidFirewallPowerSaveRules);
        }

        synchronized (mIdleTimerLock) {
            pw.println("Idle timers:");
            for (HashMap.Entry<String, IdleTimerParams> ent : mActiveIdleTimers.entrySet()) {
                pw.print("  "); pw.print(ent.getKey()); pw.println(":");
                IdleTimerParams params = ent.getValue();
                pw.print("    timeout="); pw.print(params.timeout);
                pw.print(" type="); pw.print(params.type);
                pw.print(" networkCount="); pw.println(params.networkCount);
            }
        }

        pw.print("Firewall enabled: "); pw.println(mFirewallEnabled);
        pw.print("Netd service status: " );
        if (mNetdService == null) {
            pw.println("disconnected");
        } else {
            try {
                final boolean alive = mNetdService.isAlive();
                pw.println(alive ? "alive": "dead");
            } catch (RemoteException e) {
                pw.println("unreachable");
            }
        }
    }

    private void dumpUidRuleOnQuotaLocked(PrintWriter pw, String name, SparseBooleanArray list) {
        pw.print("UID bandwith control ");
        pw.print(name);
        pw.print(" rule: [");
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            pw.print(list.keyAt(i));
            if (i < size - 1) pw.print(",");
        }
        pw.println("]");
    }

    private void dumpUidFirewallRule(PrintWriter pw, String name, SparseIntArray rules) {
        pw.print("UID firewall ");
        pw.print(name);
        pw.print(" rule: [");
        final int size = rules.size();
        for (int i = 0; i < size; i++) {
            pw.print(rules.keyAt(i));
            pw.print(":");
            pw.print(rules.valueAt(i));
            if (i < size - 1) pw.print(",");
        }
        pw.println("]");
    }

    @Override
    public void createPhysicalNetwork(int netId, String permission) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            if (permission != null) {
                mConnector.execute("network", "create", netId, permission);
            } else {
                mConnector.execute("network", "create", netId);
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void createVirtualNetwork(int netId, boolean hasDNS, boolean secure) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "create", netId, "vpn", hasDNS ? "1" : "0",
                    secure ? "1" : "0");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void removeNetwork(int netId) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "destroy", netId);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addInterfaceToNetwork(String iface, int netId) {
        modifyInterfaceInNetwork("add", "" + netId, iface);
    }

    @Override
    public void removeInterfaceFromNetwork(String iface, int netId) {
        modifyInterfaceInNetwork("remove", "" + netId, iface);
    }

    private void modifyInterfaceInNetwork(String action, String netId, String iface) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        try {
            mConnector.execute("network", "interface", action, netId, iface);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addLegacyRouteForNetId(int netId, RouteInfo routeInfo, int uid) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        final Command cmd = new Command("network", "route", "legacy", uid, "add", netId);

        // create triplet: interface dest-ip-addr/prefixlength gateway-ip-addr
        final LinkAddress la = routeInfo.getDestinationLinkAddress();
        cmd.appendArg(routeInfo.getInterface());
        cmd.appendArg(la.getAddress().getHostAddress() + "/" + la.getPrefixLength());
        if (routeInfo.hasGateway()) {
            cmd.appendArg(routeInfo.getGateway().getHostAddress());
        }

        try {
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setDefaultNetId(int netId) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "default", "set", netId);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearDefaultNetId() {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "default", "clear");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setNetworkPermission(int netId, String permission) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            if (permission != null) {
                mConnector.execute("network", "permission", "network", "set", permission, netId);
            } else {
                mConnector.execute("network", "permission", "network", "clear", netId);
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }


    @Override
    public void setPermission(String permission, int[] uids) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        Object[] argv = new Object[4 + MAX_UID_RANGES_PER_COMMAND];
        argv[0] = "permission";
        argv[1] = "user";
        argv[2] = "set";
        argv[3] = permission;
        int argc = 4;
        // Avoid overly long commands by limiting number of UIDs per command.
        for (int i = 0; i < uids.length; ++i) {
            argv[argc++] = uids[i];
            if (i == uids.length - 1 || argc == argv.length) {
                try {
                    mConnector.execute("network", Arrays.copyOf(argv, argc));
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
                argc = 4;
            }
        }
    }

    @Override
    public void clearPermission(int[] uids) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        Object[] argv = new Object[3 + MAX_UID_RANGES_PER_COMMAND];
        argv[0] = "permission";
        argv[1] = "user";
        argv[2] = "clear";
        int argc = 3;
        // Avoid overly long commands by limiting number of UIDs per command.
        for (int i = 0; i < uids.length; ++i) {
            argv[argc++] = uids[i];
            if (i == uids.length - 1 || argc == argv.length) {
                try {
                    mConnector.execute("network", Arrays.copyOf(argv, argc));
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
                argc = 3;
            }
        }
    }

    @Override
    public void allowProtect(int uid) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "protect", "allow", uid);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void denyProtect(int uid) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        try {
            mConnector.execute("network", "protect", "deny", uid);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void addInterfaceToLocalNetwork(String iface, List<RouteInfo> routes) {
        modifyInterfaceInNetwork("add", "local", iface);

        for (RouteInfo route : routes) {
            if (!route.isDefaultRoute()) {
                modifyRoute("add", "local", route);
            }
        }
    }

    @Override
    public void removeInterfaceFromLocalNetwork(String iface) {
        modifyInterfaceInNetwork("remove", "local", iface);
    }

    @Override
    public int removeRoutesFromLocalNetwork(List<RouteInfo> routes) {
        int failures = 0;

        for (RouteInfo route : routes) {
            try {
                modifyRoute("remove", "local", route);
            } catch (IllegalStateException e) {
                failures++;
            }
        }

        return failures;
    }
}
