/*
 * Copyright (C) 2008 The Android Open Source Project
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
import static android.Manifest.permission.RECEIVE_DATA_ACTIVITY_CHANGE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.MobileDataStateTracker;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.Proxy;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.SamplingDataTracker;
import android.net.UidRange;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.telephony.DctConstants;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.connectivity.PacManager;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub
        implements PendingIntent.OnFinished {
    private static final String TAG = "ConnectivityService";

    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // network sampling debugging
    private static final boolean SAMPLE_DBG = false;

    private static final boolean LOGD_RULES = false;

    // TODO: create better separation between radio types and network types

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // Default value if FAIL_FAST_TIME_MS is not set
    private static final int DEFAULT_FAIL_FAST_TIME_MS = 1 * 60 * 1000;
    // system property that can override DEFAULT_FAIL_FAST_TIME_MS
    private static final String FAIL_FAST_TIME_MS =
            "persist.radio.fail_fast_time_ms";

    private static final String ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED =
            "android.net.ConnectivityService.action.PKT_CNT_SAMPLE_INTERVAL_ELAPSED";

    private static final int SAMPLE_INTERVAL_ELAPSED_REQUEST_CODE = 0;

    // How long to delay to removal of a pending intent based request.
    // See Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
    private final int mReleasePendingIntentDelayMs;

    private PendingIntent mSampleIntervalElapsedIntent;

    // Set network sampling interval at 12 minutes, this way, even if the timers get
    // aggregated, it will fire at around 15 minutes, which should allow us to
    // aggregate this timer with other timers (specially the socket keep alive timers)
    private static final int DEFAULT_SAMPLING_INTERVAL_IN_SECONDS = (SAMPLE_DBG ? 30 : 12 * 60);

    // start network sampling a minute after booting ...
    private static final int DEFAULT_START_SAMPLING_INTERVAL_IN_SECONDS = (SAMPLE_DBG ? 30 : 60);

    AlarmManager mAlarmManager;

    private Tethering mTethering;

    private final PermissionMonitor mPermissionMonitor;

    private KeyStore mKeyStore;

    @GuardedBy("mVpns")
    private final SparseArray<Vpn> mVpns = new SparseArray<Vpn>();

    private boolean mLockdownEnabled;
    private LockdownVpnTracker mLockdownTracker;

    /** Lock around {@link #mUidRules} and {@link #mMeteredIfaces}. */
    private Object mRulesLock = new Object();
    /** Currently active network rules by UID. */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Set of ifaces that are costly. */
    private HashSet<String> mMeteredIfaces = Sets.newHashSet();

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];

    private Context mContext;
    private int mNetworkPreference;
    // 0 is full bad, 100 is full good
    private int mDefaultInetConditionPublished = 0;

    private Object mDnsLock = new Object();
    private int mNumDnsEntries;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private INetworkManagementService mNetd;
    private INetworkStatsService mStatsService;
    private INetworkPolicyManager mPolicyManager;

    private String mCurrentTcpBufferSizes;

    private static final int ENABLED  = 1;
    private static final int DISABLED = 0;

    // Arguments to rematchNetworkAndRequests()
    private enum NascentState {
        // Indicates a network was just validated for the first time.  If the network is found to
        // be unwanted (i.e. not satisfy any NetworkRequests) it is torn down.
        JUST_VALIDATED,
        // Indicates a network was not validated for the first time immediately prior to this call.
        NOT_JUST_VALIDATED
    };
    private enum ReapUnvalidatedNetworks {
        // Tear down unvalidated networks that have no chance (i.e. even if validated) of becoming
        // the highest scoring network satisfying a NetworkRequest.  This should be passed when it's
        // known that there may be unvalidated networks that could potentially be reaped, and when
        // all networks have been rematched against all NetworkRequests.
        REAP,
        // Don't reap unvalidated networks.  This should be passed when it's known that there are
        // no unvalidated networks that could potentially be reaped, and when some networks have
        // not yet been rematched against all NetworkRequests.
        DONT_REAP
    };

    /**
     * used internally to change our mobile data enabled flag
     */
    private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED = 2;

    /**
     * used internally to clear a wakelock when transitioning
     * from one net to another.  Clear happens when we get a new
     * network - EVENT_EXPIRE_NET_TRANSITION_WAKELOCK happens
     * after a timeout if no network is found (typically 1 min).
     */
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK = 8;

    /**
     * used internally to reload global proxy settings
     */
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY = 9;

    /**
     * used internally to set external dependency met/unmet
     * arg1 = ENABLED (met) or DISABLED (unmet)
     * arg2 = NetworkType
     */
    private static final int EVENT_SET_DEPENDENCY_MET = 10;

    /**
     * used internally to send a sticky broadcast delayed.
     */
    private static final int EVENT_SEND_STICKY_BROADCAST_INTENT = 11;

    /**
     * Used internally to disable fail fast of mobile data
     */
    private static final int EVENT_ENABLE_FAIL_FAST_MOBILE_DATA = 14;

    /**
     * used internally to indicate that data sampling interval is up
     */
    private static final int EVENT_SAMPLE_INTERVAL_ELAPSED = 15;

    /**
     * PAC manager has received new port.
     */
    private static final int EVENT_PROXY_HAS_CHANGED = 16;

    /**
     * used internally when registering NetworkFactories
     * obj = NetworkFactoryInfo
     */
    private static final int EVENT_REGISTER_NETWORK_FACTORY = 17;

    /**
     * used internally when registering NetworkAgents
     * obj = Messenger
     */
    private static final int EVENT_REGISTER_NETWORK_AGENT = 18;

    /**
     * used to add a network request
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_REQUEST = 19;

    /**
     * indicates a timeout period is over - check if we had a network yet or not
     * and if not, call the timeout calback (but leave the request live until they
     * cancel it.
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_TIMEOUT_NETWORK_REQUEST = 20;

    /**
     * used to add a network listener - no request
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_LISTENER = 21;

    /**
     * used to remove a network request, either a listener or a real request
     * arg1 = UID of caller
     * obj  = NetworkRequest
     */
    private static final int EVENT_RELEASE_NETWORK_REQUEST = 22;

    /**
     * used internally when registering NetworkFactories
     * obj = Messenger
     */
    private static final int EVENT_UNREGISTER_NETWORK_FACTORY = 23;

    /**
     * used internally to expire a wakelock when transitioning
     * from one net to another.  Expire happens when we fail to find
     * a new network (typically after 1 minute) -
     * EVENT_CLEAR_NET_TRANSITION_WAKELOCK happens if we had found
     * a replacement network.
     */
    private static final int EVENT_EXPIRE_NET_TRANSITION_WAKELOCK = 24;

    /**
     * Used internally to indicate the system is ready.
     */
    private static final int EVENT_SYSTEM_READY = 25;

    /**
     * used to add a network request with a pending intent
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT = 26;

    /**
     * used to remove a pending intent and its associated network request.
     * arg1 = UID of caller
     * obj  = PendingIntent
     */
    private static final int EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT = 27;


    /** Handler used for internal events. */
    final private InternalHandler mHandler;
    /** Handler used for incoming {@link NetworkStateTracker} events. */
    final private NetworkStateTrackerHandler mTrackerHandler;

    private boolean mSystemReady;
    private Intent mInitialBroadcast;

    private PowerManager.WakeLock mNetTransitionWakeLock;
    private String mNetTransitionWakeLockCausedBy = "";
    private int mNetTransitionWakeLockSerialNumber;
    private int mNetTransitionWakeLockTimeout;
    private final PowerManager.WakeLock mPendingIntentWakeLock;

    private InetAddress mDefaultDns;

    // used in DBG mode to track inet condition reports
    private static final int INET_CONDITION_LOG_MAX_SIZE = 15;
    private ArrayList mInetLog;

    // track the current default http proxy - tell the world if we get a new one (real change)
    private volatile ProxyInfo mDefaultProxy = null;
    private Object mProxyLock = new Object();
    private boolean mDefaultProxyDisabled = false;

    // track the global proxy.
    private ProxyInfo mGlobalProxy = null;

    private PacManager mPacManager = null;

    private SettingsObserver mSettingsObserver;

    private UserManager mUserManager;

    NetworkConfig[] mNetConfigs;
    int mNetworksDefined;

    // the set of network types that can only be enabled by system/sig apps
    List mProtectedNetworks;

    private DataConnectionStats mDataConnectionStats;

    private AtomicInteger mEnableFailFastMobileDataTag = new AtomicInteger(0);

    TelephonyManager mTelephonyManager;

    // sequence number for Networks; keep in sync with system/netd/NetworkController.cpp
    private final static int MIN_NET_ID = 100; // some reserved marks
    private final static int MAX_NET_ID = 65535;
    private int mNextNetId = MIN_NET_ID;

    // sequence number of NetworkRequests
    private int mNextNetworkRequestId = 1;

    /**
     * Implements support for the legacy "one network per network type" model.
     *
     * We used to have a static array of NetworkStateTrackers, one for each
     * network type, but that doesn't work any more now that we can have,
     * for example, more that one wifi network. This class stores all the
     * NetworkAgentInfo objects that support a given type, but the legacy
     * API will only see the first one.
     *
     * It serves two main purposes:
     *
     * 1. Provide information about "the network for a given type" (since this
     *    API only supports one).
     * 2. Send legacy connectivity change broadcasts. Broadcasts are sent if
     *    the first network for a given type changes, or if the default network
     *    changes.
     */
    private class LegacyTypeTracker {

        private static final boolean DBG = true;
        private static final boolean VDBG = false;
        private static final String TAG = "CSLegacyTypeTracker";

        /**
         * Array of lists, one per legacy network type (e.g., TYPE_MOBILE_MMS).
         * Each list holds references to all NetworkAgentInfos that are used to
         * satisfy requests for that network type.
         *
         * This array is built out at startup such that an unsupported network
         * doesn't get an ArrayList instance, making this a tristate:
         * unsupported, supported but not active and active.
         *
         * The actual lists are populated when we scan the network types that
         * are supported on this device.
         */
        private ArrayList<NetworkAgentInfo> mTypeLists[];

        public LegacyTypeTracker() {
            mTypeLists = (ArrayList<NetworkAgentInfo>[])
                    new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE + 1];
        }

        public void addSupportedType(int type) {
            if (mTypeLists[type] != null) {
                throw new IllegalStateException(
                        "legacy list for type " + type + "already initialized");
            }
            mTypeLists[type] = new ArrayList<NetworkAgentInfo>();
        }

        public boolean isTypeSupported(int type) {
            return isNetworkTypeValid(type) && mTypeLists[type] != null;
        }

        public NetworkAgentInfo getNetworkForType(int type) {
            if (isTypeSupported(type) && !mTypeLists[type].isEmpty()) {
                return mTypeLists[type].get(0);
            } else {
                return null;
            }
        }

        private void maybeLogBroadcast(NetworkAgentInfo nai, boolean connected, int type) {
            if (DBG) {
                log("Sending " + (connected ? "connected" : "disconnected") +
                        " broadcast for type " + type + " " + nai.name() +
                        " isDefaultNetwork=" + isDefaultNetwork(nai));
            }
        }

        /** Adds the given network to the specified legacy type list. */
        public void add(int type, NetworkAgentInfo nai) {
            if (!isTypeSupported(type)) {
                return;  // Invalid network type.
            }
            if (VDBG) log("Adding agent " + nai + " for legacy network type " + type);

            ArrayList<NetworkAgentInfo> list = mTypeLists[type];
            if (list.contains(nai)) {
                loge("Attempting to register duplicate agent for type " + type + ": " + nai);
                return;
            }

            list.add(nai);

            // Send a broadcast if this is the first network of its type or if it's the default.
            if (list.size() == 1 || isDefaultNetwork(nai)) {
                maybeLogBroadcast(nai, true, type);
                sendLegacyNetworkBroadcast(nai, true, type);
            }
        }

        /** Removes the given network from the specified legacy type list. */
        public void remove(int type, NetworkAgentInfo nai) {
            ArrayList<NetworkAgentInfo> list = mTypeLists[type];
            if (list == null || list.isEmpty()) {
                return;
            }

            boolean wasFirstNetwork = list.get(0).equals(nai);

            if (!list.remove(nai)) {
                return;
            }

            if (wasFirstNetwork || isDefaultNetwork(nai)) {
                maybeLogBroadcast(nai, false, type);
                sendLegacyNetworkBroadcast(nai, false, type);
            }

            if (!list.isEmpty() && wasFirstNetwork) {
                if (DBG) log("Other network available for type " + type +
                              ", sending connected broadcast");
                maybeLogBroadcast(list.get(0), false, type);
                sendLegacyNetworkBroadcast(list.get(0), false, type);
            }
        }

        /** Removes the given network from all legacy type lists. */
        public void remove(NetworkAgentInfo nai) {
            if (VDBG) log("Removing agent " + nai);
            for (int type = 0; type < mTypeLists.length; type++) {
                remove(type, nai);
            }
        }

        private String naiToString(NetworkAgentInfo nai) {
            String name = (nai != null) ? nai.name() : "null";
            String state = (nai.networkInfo != null) ?
                    nai.networkInfo.getState() + "/" + nai.networkInfo.getDetailedState() :
                    "???/???";
            return name + " " + state;
        }

        public void dump(IndentingPrintWriter pw) {
            for (int type = 0; type < mTypeLists.length; type++) {
                if (mTypeLists[type] == null) continue;
                pw.print(type + " ");
                pw.increaseIndent();
                if (mTypeLists[type].size() == 0) pw.println("none");
                for (NetworkAgentInfo nai : mTypeLists[type]) {
                    pw.println(naiToString(nai));
                }
                pw.decreaseIndent();
            }
        }

        // This class needs its own log method because it has a different TAG.
        private void log(String s) {
            Slog.d(TAG, s);
        }

    }
    private LegacyTypeTracker mLegacyTypeTracker = new LegacyTypeTracker();

    public ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        if (DBG) log("ConnectivityService starting up");

        NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mDefaultRequest = new NetworkRequest(netCap, TYPE_NONE, nextNetworkRequestId());
        NetworkRequestInfo nri = new NetworkRequestInfo(null, mDefaultRequest, new Binder(),
                NetworkRequestInfo.REQUEST);
        mNetworkRequests.put(mDefaultRequest, nri);

        HandlerThread handlerThread = new HandlerThread("ConnectivityServiceThread");
        handlerThread.start();
        mHandler = new InternalHandler(handlerThread.getLooper());
        mTrackerHandler = new NetworkStateTrackerHandler(handlerThread.getLooper());

        // setup our unique device name
        if (TextUtils.isEmpty(SystemProperties.get("net.hostname"))) {
            String id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                String name = new String("android-").concat(id);
                SystemProperties.set("net.hostname", name);
            }
        }

        // read our default dns server ip
        String dns = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = context.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            mDefaultDns = NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            loge("Error setting defaultDns using " + dns);
        }

        mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, 5_000);

        mContext = checkNotNull(context, "missing Context");
        mNetd = checkNotNull(netManager, "missing INetworkManagementService");
        mStatsService = checkNotNull(statsService, "missing INetworkStatsService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");
        mKeyStore = KeyStore.getInstance();
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        try {
            mPolicyManager.registerListener(mPolicyListener);
        } catch (RemoteException e) {
            // ouch, no rules updates means some processes may never get network
            loge("unable to register INetworkPolicyListener" + e.toString());
        }

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mNetTransitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNetTransitionWakeLockTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkTransitionTimeout);
        mPendingIntentWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mNetTrackers = new NetworkStateTracker[
                ConnectivityManager.MAX_NETWORK_TYPE+1];

        mNetConfigs = new NetworkConfig[ConnectivityManager.MAX_NETWORK_TYPE+1];

        // TODO: What is the "correct" way to do determine if this is a wifi only device?
        boolean wifiOnly = SystemProperties.getBoolean("ro.radio.noril", false);
        log("wifiOnly=" + wifiOnly);
        String[] naStrings = context.getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String naString : naStrings) {
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (VDBG) log("naString=" + naString + " config=" + n);
                if (n.type > ConnectivityManager.MAX_NETWORK_TYPE) {
                    loge("Error in networkAttributes - ignoring attempt to define type " +
                            n.type);
                    continue;
                }
                if (wifiOnly && ConnectivityManager.isNetworkTypeMobile(n.type)) {
                    log("networkAttributes - ignoring mobile as this dev is wifiOnly " +
                            n.type);
                    continue;
                }
                if (mNetConfigs[n.type] != null) {
                    loge("Error in networkAttributes - ignoring attempt to redefine type " +
                            n.type);
                    continue;
                }
                mLegacyTypeTracker.addSupportedType(n.type);

                mNetConfigs[n.type] = n;
                mNetworksDefined++;
            } catch(Exception e) {
                // ignore it - leave the entry null
            }
        }

        // Forcibly add TYPE_VPN as a supported type, if it has not already been added via config.
        if (mNetConfigs[TYPE_VPN] == null) {
            // mNetConfigs is used only for "restore time", which isn't applicable to VPNs, so we
            // don't need to add TYPE_VPN to mNetConfigs.
            mLegacyTypeTracker.addSupportedType(TYPE_VPN);
            mNetworksDefined++;  // used only in the log() statement below.
        }

        if (VDBG) log("mNetworksDefined=" + mNetworksDefined);

        mProtectedNetworks = new ArrayList<Integer>();
        int[] protectedNetworks = context.getResources().getIntArray(
                com.android.internal.R.array.config_protectedNetworks);
        for (int p : protectedNetworks) {
            if ((mNetConfigs[p] != null) && (mProtectedNetworks.contains(p) == false)) {
                mProtectedNetworks.add(p);
            } else {
                if (DBG) loge("Ignoring protectedNetwork " + p);
            }
        }

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");

        mTethering = new Tethering(mContext, mNetd, statsService, mHandler.getLooper());

        mPermissionMonitor = new PermissionMonitor(mContext, mNetd);

        //set up the listener for user state for creating user VPNs
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_STARTING);
        intentFilter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        try {
            mNetd.registerObserver(mTethering);
            mNetd.registerObserver(mDataActivityObserver);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        if (DBG) {
            mInetLog = new ArrayList();
        }

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_APPLY_GLOBAL_HTTP_PROXY);
        mSettingsObserver.observe(mContext);

        mDataConnectionStats = new DataConnectionStats(mContext);
        mDataConnectionStats.startMonitoring();

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED)) {
                            mHandler.sendMessage(mHandler.obtainMessage
                                    (EVENT_SAMPLE_INTERVAL_ELAPSED));
                        }
                    }
                },
                new IntentFilter(filter));

        mPacManager = new PacManager(mContext, mHandler, EVENT_PROXY_HAS_CHANGED);

        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    private synchronized int nextNetworkRequestId() {
        return mNextNetworkRequestId++;
    }

    private void assignNextNetId(NetworkAgentInfo nai) {
        synchronized (mNetworkForNetId) {
            for (int i = MIN_NET_ID; i <= MAX_NET_ID; i++) {
                int netId = mNextNetId;
                if (++mNextNetId > MAX_NET_ID) mNextNetId = MIN_NET_ID;
                // Make sure NetID unused.  http://b/16815182
                if (mNetworkForNetId.get(netId) == null) {
                    nai.network = new Network(netId);
                    mNetworkForNetId.put(netId, nai);
                    return;
                }
            }
        }
        throw new IllegalStateException("No free netIds");
    }

    private boolean teardown(NetworkStateTracker netTracker) {
        if (netTracker.teardown()) {
            netTracker.setTeardownRequested(true);
            return true;
        } else {
            return false;
        }
    }

    private NetworkState getFilteredNetworkState(int networkType, int uid) {
        NetworkInfo info = null;
        LinkProperties lp = null;
        NetworkCapabilities nc = null;
        Network network = null;
        String subscriberId = null;

        if (mLegacyTypeTracker.isTypeSupported(networkType)) {
            NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
            if (nai != null) {
                synchronized (nai) {
                    info = new NetworkInfo(nai.networkInfo);
                    lp = new LinkProperties(nai.linkProperties);
                    nc = new NetworkCapabilities(nai.networkCapabilities);
                    network = new Network(nai.network);
                    subscriberId = (nai.networkMisc != null) ? nai.networkMisc.subscriberId : null;
                }
                info.setType(networkType);
            } else {
                info = new NetworkInfo(networkType, 0, getNetworkTypeName(networkType), "");
                info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
                info.setIsAvailable(true);
                lp = new LinkProperties();
                nc = new NetworkCapabilities();
                network = null;
            }
            info = getFilteredNetworkInfo(info, lp, uid);
        }

        return new NetworkState(info, lp, nc, network, subscriberId, null);
    }

    private NetworkAgentInfo getNetworkAgentInfoForNetwork(Network network) {
        if (network == null) {
            return null;
        }
        synchronized (mNetworkForNetId) {
            return mNetworkForNetId.get(network.netId);
        }
    };

    private Network[] getVpnUnderlyingNetworks(int uid) {
        if (!mLockdownEnabled) {
            int user = UserHandle.getUserId(uid);
            synchronized (mVpns) {
                Vpn vpn = mVpns.get(user);
                if (vpn != null && vpn.appliesToUid(uid)) {
                    return vpn.getUnderlyingNetworks();
                }
            }
        }
        return null;
    }

    private NetworkState getUnfilteredActiveNetworkState(int uid) {
        NetworkInfo info = null;
        LinkProperties lp = null;
        NetworkCapabilities nc = null;
        Network network = null;
        String subscriberId = null;

        NetworkAgentInfo nai = mNetworkForRequestId.get(mDefaultRequest.requestId);

        final Network[] networks = getVpnUnderlyingNetworks(uid);
        if (networks != null) {
            // getUnderlyingNetworks() returns:
            // null => there was no VPN, or the VPN didn't specify anything, so we use the default.
            // empty array => the VPN explicitly said "no default network".
            // non-empty array => the VPN specified one or more default networks; we use the
            //                    first one.
            if (networks.length > 0) {
                nai = getNetworkAgentInfoForNetwork(networks[0]);
            } else {
                nai = null;
            }
        }

        if (nai != null) {
            synchronized (nai) {
                info = new NetworkInfo(nai.networkInfo);
                lp = new LinkProperties(nai.linkProperties);
                nc = new NetworkCapabilities(nai.networkCapabilities);
                network = new Network(nai.network);
                subscriberId = (nai.networkMisc != null) ? nai.networkMisc.subscriberId : null;
            }
        }

        return new NetworkState(info, lp, nc, network, subscriberId, null);
    }

    /**
     * Check if UID should be blocked from using the network with the given LinkProperties.
     */
    private boolean isNetworkWithLinkPropertiesBlocked(LinkProperties lp, int uid) {
        final boolean networkCostly;
        final int uidRules;

        final String iface = (lp == null ? "" : lp.getInterfaceName());
        synchronized (mRulesLock) {
            networkCostly = mMeteredIfaces.contains(iface);
            uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
        }

        if (networkCostly && (uidRules & RULE_REJECT_METERED) != 0) {
            return true;
        }

        // no restrictive rules; network is visible
        return false;
    }

    /**
     * Return a filtered {@link NetworkInfo}, potentially marked
     * {@link DetailedState#BLOCKED} based on
     * {@link #isNetworkWithLinkPropertiesBlocked}.
     */
    private NetworkInfo getFilteredNetworkInfo(NetworkInfo info, LinkProperties lp, int uid) {
        if (info != null && isNetworkWithLinkPropertiesBlocked(lp, uid)) {
            // network is blocked; clone and override state
            info = new NetworkInfo(info);
            info.setDetailedState(DetailedState.BLOCKED, null, null);
            if (DBG) {
                log("returning Blocked NetworkInfo for ifname=" +
                        lp.getInterfaceName() + ", uid=" + uid);
            }
        }
        if (info != null && mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
            if (DBG) log("returning Locked NetworkInfo");
        }
        return info;
    }

    /**
     * Return NetworkInfo for the active (i.e., connected) network interface.
     * It is assumed that at most one network is active at a time. If more
     * than one is active, it is indeterminate which will be returned.
     * @return the info for the active network, or {@code null} if none is
     * active
     */
    @Override
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return getFilteredNetworkInfo(state.networkInfo, state.linkProperties, uid);
    }

    /**
     * Find the first Provisioning network.
     *
     * @return NetworkInfo or null if none.
     */
    private NetworkInfo getProvisioningNetworkInfo() {
        enforceAccessPermission();

        // Find the first Provisioning Network
        NetworkInfo provNi = null;
        for (NetworkInfo ni : getAllNetworkInfo()) {
            if (ni.isConnectedToProvisioningNetwork()) {
                provNi = ni;
                break;
            }
        }
        if (DBG) log("getProvisioningNetworkInfo: X provNi=" + provNi);
        return provNi;
    }

    /**
     * Find the first Provisioning network or the ActiveDefaultNetwork
     * if there is no Provisioning network
     *
     * @return NetworkInfo or null if none.
     */
    @Override
    public NetworkInfo getProvisioningOrActiveNetworkInfo() {
        enforceAccessPermission();

        NetworkInfo provNi = getProvisioningNetworkInfo();
        if (provNi == null) {
            provNi = getActiveNetworkInfo();
        }
        if (DBG) log("getProvisioningOrActiveNetworkInfo: X provNi=" + provNi);
        return provNi;
    }

    public NetworkInfo getActiveNetworkInfoUnfiltered() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.networkInfo;
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        enforceConnectivityInternalPermission();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return getFilteredNetworkInfo(state.networkInfo, state.linkProperties, uid);
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        if (getVpnUnderlyingNetworks(uid) != null) {
            // A VPN is active, so we may need to return one of its underlying networks. This
            // information is not available in LegacyTypeTracker, so we have to get it from
            // getUnfilteredActiveNetworkState.
            NetworkState state = getUnfilteredActiveNetworkState(uid);
            if (state.networkInfo != null && state.networkInfo.getType() == networkType) {
                return getFilteredNetworkInfo(state.networkInfo, state.linkProperties, uid);
            }
        }
        NetworkState state = getFilteredNetworkState(networkType, uid);
        return state.networkInfo;
    }

    @Override
    public NetworkInfo getNetworkInfoForNetwork(Network network) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkInfo info = null;
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            synchronized (nai) {
                info = new NetworkInfo(nai.networkInfo);
                info = getFilteredNetworkInfo(info, nai.linkProperties, uid);
            }
        }
        return info;
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final ArrayList<NetworkInfo> result = Lists.newArrayList();
        for (int networkType = 0; networkType <= ConnectivityManager.MAX_NETWORK_TYPE;
                networkType++) {
            NetworkInfo info = getNetworkInfo(networkType);
            if (info != null) {
                result.add(info);
            }
        }
        return result.toArray(new NetworkInfo[result.size()]);
    }

    @Override
    public Network getNetworkForType(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getFilteredNetworkState(networkType, uid);
        if (!isNetworkWithLinkPropertiesBlocked(state.linkProperties, uid)) {
            return state.network;
        }
        return null;
    }

    @Override
    public Network[] getAllNetworks() {
        enforceAccessPermission();
        final ArrayList<Network> result = new ArrayList();
        synchronized (mNetworkForNetId) {
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                result.add(new Network(mNetworkForNetId.valueAt(i).network));
            }
        }
        return result.toArray(new Network[result.size()]);
    }

    private NetworkCapabilities getNetworkCapabilitiesAndValidation(NetworkAgentInfo nai) {
        if (nai != null) {
            synchronized (nai) {
                if (nai.created) {
                    NetworkCapabilities nc = new NetworkCapabilities(nai.networkCapabilities);
                    if (nai.lastValidated) {
                        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    } else {
                        nc.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    }
                    return nc;
                }
            }
        }
        return null;
    }

    @Override
    public NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(int userId) {
        // The basic principle is: if an app's traffic could possibly go over a
        // network, without the app doing anything multinetwork-specific,
        // (hence, by "default"), then include that network's capabilities in
        // the array.
        //
        // In the normal case, app traffic only goes over the system's default
        // network connection, so that's the only network returned.
        //
        // With a VPN in force, some app traffic may go into the VPN, and thus
        // over whatever underlying networks the VPN specifies, while other app
        // traffic may go over the system default network (e.g.: a split-tunnel
        // VPN, or an app disallowed by the VPN), so the set of networks
        // returned includes the VPN's underlying networks and the system
        // default.
        enforceAccessPermission();

        HashMap<Network, NetworkCapabilities> result = new HashMap<Network, NetworkCapabilities>();

        NetworkAgentInfo nai = getDefaultNetwork();
        NetworkCapabilities nc = getNetworkCapabilitiesAndValidation(getDefaultNetwork());
        if (nc != null) {
            result.put(nai.network, nc);
        }

        if (!mLockdownEnabled) {
            synchronized (mVpns) {
                Vpn vpn = mVpns.get(userId);
                if (vpn != null) {
                    Network[] networks = vpn.getUnderlyingNetworks();
                    if (networks != null) {
                        for (Network network : networks) {
                            nai = getNetworkAgentInfoForNetwork(network);
                            nc = getNetworkCapabilitiesAndValidation(nai);
                            if (nc != null) {
                                result.put(nai.network, nc);
                            }
                        }
                    }
                }
            }
        }

        NetworkCapabilities[] out = new NetworkCapabilities[result.size()];
        out = result.values().toArray(out);
        return out;
    }

    @Override
    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return mLegacyTypeTracker.isTypeSupported(networkType);
    }

    /**
     * Return LinkProperties for the active (i.e., connected) default
     * network interface.  It is assumed that at most one default network
     * is active at a time. If more than one is active, it is indeterminate
     * which will be returned.
     * @return the ip properties for the active network, or {@code null} if
     * none is active
     */
    @Override
    public LinkProperties getActiveLinkProperties() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.linkProperties;
    }

    @Override
    public LinkProperties getLinkPropertiesForType(int networkType) {
        enforceAccessPermission();
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            synchronized (nai) {
                return new LinkProperties(nai.linkProperties);
            }
        }
        return null;
    }

    // TODO - this should be ALL networks
    @Override
    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            synchronized (nai) {
                return new LinkProperties(nai.linkProperties);
            }
        }
        return null;
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            synchronized (nai) {
                return new NetworkCapabilities(nai.networkCapabilities);
            }
        }
        return null;
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        // Require internal since we're handing out IMSI details
        enforceConnectivityInternalPermission();

        final ArrayList<NetworkState> result = Lists.newArrayList();
        for (Network network : getAllNetworks()) {
            final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai != null) {
                synchronized (nai) {
                    final String subscriberId = (nai.networkMisc != null)
                            ? nai.networkMisc.subscriberId : null;
                    result.add(new NetworkState(nai.networkInfo, nai.linkProperties,
                            nai.networkCapabilities, network, subscriberId, null));
                }
            }
        }
        return result.toArray(new NetworkState[result.size()]);
    }

    @Override
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            final NetworkState state = getUnfilteredActiveNetworkState(uid);
            if (state.networkInfo != null) {
                try {
                    return mPolicyManager.getNetworkQuotaInfo(state);
                } catch (RemoteException e) {
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isActiveNetworkMetered() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            return isActiveNetworkMeteredUnchecked(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isActiveNetworkMeteredUnchecked(int uid) {
        final NetworkState state = getUnfilteredActiveNetworkState(uid);
        if (state.networkInfo != null) {
            try {
                return mPolicyManager.isNetworkMetered(state);
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    private INetworkManagementEventObserver mDataActivityObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceClassDataActivityChanged(String label, boolean active, long tsNanos) {
            int deviceType = Integer.parseInt(label);
            sendDataActivityBroadcast(deviceType, active, tsNanos);
        }
    };

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        enforceChangePermission();
        if (mProtectedNetworks.contains(networkType)) {
            enforceConnectivityInternalPermission();
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(hostAddress);
        } catch (UnknownHostException e) {
            if (DBG) log("requestRouteToHostAddress got " + e.toString());
            return false;
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) log("requestRouteToHostAddress on invalid network: " + networkType);
            return false;
        }

        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) {
            if (mLegacyTypeTracker.isTypeSupported(networkType) == false) {
                if (DBG) log("requestRouteToHostAddress on unsupported network: " + networkType);
            } else {
                if (DBG) log("requestRouteToHostAddress on down network: " + networkType);
            }
            return false;
        }

        DetailedState netState;
        synchronized (nai) {
            netState = nai.networkInfo.getDetailedState();
        }

        if (netState != DetailedState.CONNECTED && netState != DetailedState.CAPTIVE_PORTAL_CHECK) {
            if (VDBG) {
                log("requestRouteToHostAddress on down network "
                        + "(" + networkType + ") - dropped"
                        + " netState=" + netState);
            }
            return false;
        }

        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            LinkProperties lp;
            int netId;
            synchronized (nai) {
                lp = nai.linkProperties;
                netId = nai.network.netId;
            }
            boolean ok = addLegacyRouteToHost(lp, addr, netId, uid);
            if (DBG) log("requestRouteToHostAddress ok=" + ok);
            return ok;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean addLegacyRouteToHost(LinkProperties lp, InetAddress addr, int netId, int uid) {
        RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getAllRoutes(), addr);
        if (bestRoute == null) {
            bestRoute = RouteInfo.makeHostRoute(addr, lp.getInterfaceName());
        } else {
            String iface = bestRoute.getInterface();
            if (bestRoute.getGateway().equals(addr)) {
                // if there is no better route, add the implied hostroute for our gateway
                bestRoute = RouteInfo.makeHostRoute(addr, iface);
            } else {
                // if we will connect to this through another route, add a direct route
                // to it's gateway
                bestRoute = RouteInfo.makeHostRoute(addr, bestRoute.getGateway(), iface);
            }
        }
        if (DBG) log("Adding " + bestRoute + " for interface " + bestRoute.getInterface());
        try {
            mNetd.addLegacyRouteForNetId(netId, bestRoute, uid);
        } catch (Exception e) {
            // never crash - catch them all
            if (DBG) loge("Exception trying to add a route: " + e);
            return false;
        }
        return true;
    }

    public void setDataDependency(int networkType, boolean met) {
        enforceConnectivityInternalPermission();

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_DEPENDENCY_MET,
                (met ? ENABLED : DISABLED), networkType));
    }

    private void handleSetDependencyMet(int networkType, boolean met) {
        if (mNetTrackers[networkType] != null) {
            if (DBG) {
                log("handleSetDependencyMet(" + networkType + ", " + met + ")");
            }
            mNetTrackers[networkType].setDependencyMet(met);
        }
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onUidRulesChanged(uid=" + uid + ", uidRules=" + uidRules + ")");
            }

            synchronized (mRulesLock) {
                // skip update when we've already applied rules
                final int oldRules = mUidRules.get(uid, RULE_ALLOW_ALL);
                if (oldRules == uidRules) return;

                mUidRules.put(uid, uidRules);
            }

            // TODO: notify UID when it has requested targeted updates
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onMeteredIfacesChanged(ifaces=" + Arrays.toString(meteredIfaces) + ")");
            }

            synchronized (mRulesLock) {
                mMeteredIfaces.clear();
                for (String iface : meteredIfaces) {
                    mMeteredIfaces.add(iface);
                }
            }
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onRestrictBackgroundChanged(restrictBackground=" + restrictBackground + ")");
            }

            // kick off connectivity change broadcast for active network, since
            // global background policy change is radical.
            // TODO: Dead code; remove.
            //
            // final int networkType = mActiveDefaultNetwork;
            // if (isNetworkTypeValid(networkType)) {
            //     final NetworkStateTracker tracker = mNetTrackers[networkType];
            //     if (tracker != null) {
            //         final NetworkInfo info = tracker.getNetworkInfo();
            //         if (info != null && info.isConnected()) {
            //             sendConnectedBroadcast(info);
            //         }
            //     }
            // }
        }
    };

    private void enforceInternetPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERNET,
                "ConnectivityService");
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceTetherAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    public void sendConnectedBroadcast(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        if (mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
        }

        Intent intent = new Intent(bcastType);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(info));
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);
        return intent;
    }

    private void sendGeneralBroadcast(NetworkInfo info, String bcastType) {
        sendStickyBroadcast(makeGeneralIntent(info, bcastType));
    }

    private void sendDataActivityBroadcast(int deviceType, boolean active, long tsNanos) {
        Intent intent = new Intent(ConnectivityManager.ACTION_DATA_ACTIVITY_CHANGE);
        intent.putExtra(ConnectivityManager.EXTRA_DEVICE_TYPE, deviceType);
        intent.putExtra(ConnectivityManager.EXTRA_IS_ACTIVE, active);
        intent.putExtra(ConnectivityManager.EXTRA_REALTIME_NS, tsNanos);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL,
                    RECEIVE_DATA_ACTIVITY_CHANGE, null, null, 0, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized(this) {
            if (!mSystemReady) {
                mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (DBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }

            final long ident = Binder.clearCallingIdentity();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                final IBatteryStats bs = BatteryStatsService.getService();
                try {
                    NetworkInfo ni = intent.getParcelableExtra(
                            ConnectivityManager.EXTRA_NETWORK_INFO);
                    bs.noteConnectivityChanged(intent.getIntExtra(
                            ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_NONE),
                            ni != null ? ni.getState().toString() : "?");
                } catch (RemoteException e) {
                }
            }
            try {
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void systemReady() {
        // start network sampling ..
        Intent intent = new Intent(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED);
        intent.setPackage(mContext.getPackageName());

        mSampleIntervalElapsedIntent = PendingIntent.getBroadcast(mContext,
                SAMPLE_INTERVAL_ELAPSED_REQUEST_CODE, intent, 0);
        setAlarm(DEFAULT_START_SAMPLING_INTERVAL_IN_SECONDS * 1000, mSampleIntervalElapsedIntent);

        loadGlobalProxy();

        synchronized(this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcastAsUser(mInitialBroadcast, UserHandle.ALL);
                mInitialBroadcast = null;
            }
        }
        // load the global proxy at startup
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_APPLY_GLOBAL_HTTP_PROXY));

        // Try bringing up tracker, but if KeyStore isn't ready yet, wait
        // for user to unlock device.
        if (!updateLockdownVpn()) {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            mContext.registerReceiver(mUserPresentReceiver, filter);
        }

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SYSTEM_READY));

        mPermissionMonitor.startMonitoring();
    }

    private BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Try creating lockdown tracker, since user present usually means
            // unlocked keystore.
            if (updateLockdownVpn()) {
                mContext.unregisterReceiver(this);
            }
        }
    };

    /** @hide */
    @Override
    public void captivePortalCheckCompleted(NetworkInfo info, boolean isCaptivePortal) {
        enforceConnectivityInternalPermission();
        if (DBG) log("captivePortalCheckCompleted: ni=" + info + " captive=" + isCaptivePortal);
//        mNetTrackers[info.getType()].captivePortalCheckCompleted(isCaptivePortal);
    }

    /**
     * Setup data activity tracking for the given network.
     *
     * Every {@code setupDataActivityTracking} should be paired with a
     * {@link #removeDataActivityTracking} for cleanup.
     */
    private void setupDataActivityTracking(NetworkAgentInfo networkAgent) {
        final String iface = networkAgent.linkProperties.getInterfaceName();

        final int timeout;
        int type = ConnectivityManager.TYPE_NONE;

        if (networkAgent.networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                                             5);
            type = ConnectivityManager.TYPE_MOBILE;
        } else if (networkAgent.networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                                             0);
            type = ConnectivityManager.TYPE_WIFI;
        } else {
            // do not track any other networks
            timeout = 0;
        }

        if (timeout > 0 && iface != null && type != ConnectivityManager.TYPE_NONE) {
            try {
                mNetd.addIdleTimer(iface, timeout, type);
            } catch (Exception e) {
                // You shall not crash!
                loge("Exception in setupDataActivityTracking " + e);
            }
        }
    }

    /**
     * Remove data activity tracking when network disconnects.
     */
    private void removeDataActivityTracking(NetworkAgentInfo networkAgent) {
        final String iface = networkAgent.linkProperties.getInterfaceName();
        final NetworkCapabilities caps = networkAgent.networkCapabilities;

        if (iface != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                              caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) {
            try {
                // the call fails silently if no idletimer setup for this interface
                mNetd.removeIdleTimer(iface);
            } catch (Exception e) {
                loge("Exception in removeDataActivityTracking " + e);
            }
        }
    }

    /**
     * Reads the network specific MTU size from reources.
     * and set it on it's iface.
     */
    private void updateMtu(LinkProperties newLp, LinkProperties oldLp) {
        final String iface = newLp.getInterfaceName();
        final int mtu = newLp.getMtu();
        if (oldLp != null && newLp.isIdenticalMtu(oldLp)) {
            if (VDBG) log("identical MTU - not setting");
            return;
        }

        if (LinkProperties.isValidMtu(mtu, newLp.hasGlobalIPv6Address()) == false) {
            loge("Unexpected mtu value: " + mtu + ", " + iface);
            return;
        }

        // Cannot set MTU without interface name
        if (TextUtils.isEmpty(iface)) {
            loge("Setting MTU size with null iface.");
            return;
        }

        try {
            if (DBG) log("Setting MTU size: " + iface + ", " + mtu);
            mNetd.setMtu(iface, mtu);
        } catch (Exception e) {
            Slog.e(TAG, "exception in setMtu()" + e);
        }
    }

    private static final String DEFAULT_TCP_BUFFER_SIZES = "4096,87380,110208,4096,16384,110208";

    private void updateTcpBufferSizes(NetworkAgentInfo nai) {
        if (isDefaultNetwork(nai) == false) {
            return;
        }

        String tcpBufferSizes = nai.linkProperties.getTcpBufferSizes();
        String[] values = null;
        if (tcpBufferSizes != null) {
            values = tcpBufferSizes.split(",");
        }

        if (values == null || values.length != 6) {
            if (DBG) log("Invalid tcpBufferSizes string: " + tcpBufferSizes +", using defaults");
            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;
            values = tcpBufferSizes.split(",");
        }

        if (tcpBufferSizes.equals(mCurrentTcpBufferSizes)) return;

        try {
            if (DBG) Slog.d(TAG, "Setting tx/rx TCP buffers to " + tcpBufferSizes);

            final String prefix = "/sys/kernel/ipv4/tcp_";
            FileUtils.stringToFile(prefix + "rmem_min", values[0]);
            FileUtils.stringToFile(prefix + "rmem_def", values[1]);
            FileUtils.stringToFile(prefix + "rmem_max", values[2]);
            FileUtils.stringToFile(prefix + "wmem_min", values[3]);
            FileUtils.stringToFile(prefix + "wmem_def", values[4]);
            FileUtils.stringToFile(prefix + "wmem_max", values[5]);
            mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (IOException e) {
            loge("Can't set TCP buffer sizes:" + e);
        }

        final String defaultRwndKey = "net.tcp.default_init_rwnd";
        int defaultRwndValue = SystemProperties.getInt(defaultRwndKey, 0);
        Integer rwndValue = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.TCP_DEFAULT_INIT_RWND, defaultRwndValue);
        final String sysctlKey = "sys.sysctl.tcp_def_init_rwnd";
        if (rwndValue != 0) {
            SystemProperties.set(sysctlKey, rwndValue.toString());
        }
    }

    private void flushVmDnsCache() {
        /*
         * Tell the VMs to toss their DNS caches
         */
        Intent intent = new Intent(Intent.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = SystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        // if the system property isn't set, use the value for the apn type
        int ret = RESTORE_DEFAULT_NETWORK_DELAY;

        if ((networkType <= ConnectivityManager.MAX_NETWORK_TYPE) &&
                (mNetConfigs[networkType] != null)) {
            ret = mNetConfigs[networkType].restoreTime;
        }
        return ret;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }

        pw.println("NetworkFactories for:");
        pw.increaseIndent();
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            pw.println(nfi.name);
        }
        pw.decreaseIndent();
        pw.println();

        NetworkAgentInfo defaultNai = mNetworkForRequestId.get(mDefaultRequest.requestId);
        pw.print("Active default network: ");
        if (defaultNai == null) {
            pw.println("none");
        } else {
            pw.println(defaultNai.network.netId);
        }
        pw.println();

        pw.println("Current Networks:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            pw.println(nai.toString());
            pw.increaseIndent();
            pw.println("Requests:");
            pw.increaseIndent();
            for (int i = 0; i < nai.networkRequests.size(); i++) {
                pw.println(nai.networkRequests.valueAt(i).toString());
            }
            pw.decreaseIndent();
            pw.println("Lingered:");
            pw.increaseIndent();
            for (NetworkRequest nr : nai.networkLingered) pw.println(nr.toString());
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
        pw.println();

        pw.println("Network Requests:");
        pw.increaseIndent();
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            pw.println(nri.toString());
        }
        pw.println();
        pw.decreaseIndent();

        pw.println("mLegacyTypeTracker:");
        pw.increaseIndent();
        mLegacyTypeTracker.dump(pw);
        pw.decreaseIndent();
        pw.println();

        synchronized (this) {
            pw.println("NetworkTransitionWakeLock is currently " +
                    (mNetTransitionWakeLock.isHeld() ? "" : "not ") + "held.");
            pw.println("It was last requested for "+mNetTransitionWakeLockCausedBy);
        }
        pw.println();

        mTethering.dump(fd, pw, args);

        if (mInetLog != null) {
            pw.println();
            pw.println("Inet condition reports:");
            pw.increaseIndent();
            for(int i = 0; i < mInetLog.size(); i++) {
                pw.println(mInetLog.get(i));
            }
            pw.decreaseIndent();
        }
    }

    private boolean isLiveNetworkAgent(NetworkAgentInfo nai, String msg) {
        if (nai.network == null) return false;
        final NetworkAgentInfo officialNai = getNetworkAgentInfoForNetwork(nai.network);
        if (officialNai != null && officialNai.equals(nai)) return true;
        if (officialNai != null || VDBG) {
            loge(msg + " - isLiveNetworkAgent found mismatched netId: " + officialNai +
                " - " + nai);
        }
        return false;
    }

    private boolean isRequest(NetworkRequest request) {
        return mNetworkRequests.get(request).isRequest;
    }

    // must be stateless - things change under us.
    private class NetworkStateTrackerHandler extends Handler {
        public NetworkStateTrackerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    handleAsyncChannelHalfConnect(msg);
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai != null) nai.asyncChannel.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    handleAsyncChannelDisconnected(msg);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_CAPABILITIES_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_CAPABILITIES_CHANGED from unknown NetworkAgent");
                    } else {
                        updateCapabilities(nai, (NetworkCapabilities)msg.obj);
                    }
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("NetworkAgent not found for EVENT_NETWORK_PROPERTIES_CHANGED");
                    } else {
                        if (VDBG) {
                            log("Update of LinkProperties for " + nai.name() +
                                    "; created=" + nai.created);
                        }
                        LinkProperties oldLp = nai.linkProperties;
                        synchronized (nai) {
                            nai.linkProperties = (LinkProperties)msg.obj;
                        }
                        if (nai.created) updateLinkProperties(nai, oldLp);
                    }
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_INFO_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_INFO_CHANGED from unknown NetworkAgent");
                        break;
                    }
                    info = (NetworkInfo) msg.obj;
                    updateNetworkInfo(nai, info);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_SCORE_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_SCORE_CHANGED from unknown NetworkAgent");
                        break;
                    }
                    Integer score = (Integer) msg.obj;
                    if (score != null) updateNetworkScore(nai, score.intValue());
                    break;
                }
                case NetworkAgent.EVENT_UID_RANGES_ADDED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_UID_RANGES_ADDED from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.addVpnUidRanges(nai.network.netId, (UidRange[])msg.obj);
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in addVpnUidRanges: " + e);
                    }
                    break;
                }
                case NetworkAgent.EVENT_UID_RANGES_REMOVED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_UID_RANGES_REMOVED from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.removeVpnUidRanges(nai.network.netId, (UidRange[])msg.obj);
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in removeVpnUidRanges: " + e);
                    }
                    break;
                }
                case NetworkAgent.EVENT_SET_EXPLICITLY_SELECTED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_SET_EXPLICITLY_SELECTED from unknown NetworkAgent");
                        break;
                    }
                    if (nai.created && !nai.networkMisc.explicitlySelected) {
                        loge("ERROR: created network explicitly selected.");
                    }
                    nai.networkMisc.explicitlySelected = true;
                    break;
                }
                case NetworkMonitor.EVENT_NETWORK_TESTED: {
                    NetworkAgentInfo nai = (NetworkAgentInfo)msg.obj;
                    if (isLiveNetworkAgent(nai, "EVENT_NETWORK_VALIDATED")) {
                        boolean valid = (msg.arg1 == NetworkMonitor.NETWORK_TEST_RESULT_VALID);
                        nai.lastValidated = valid;
                        if (valid) {
                            if (DBG) log("Validated " + nai.name());
                            if (!nai.everValidated) {
                                nai.everValidated = true;
                                rematchNetworkAndRequests(nai, NascentState.JUST_VALIDATED,
                                    ReapUnvalidatedNetworks.REAP);
                                // If score has changed, rebroadcast to NetworkFactories. b/17726566
                                sendUpdatedScoreToFactories(nai);
                            }
                        }
                        updateInetCondition(nai);
                        // Let the NetworkAgent know the state of its network
                        nai.asyncChannel.sendMessage(
                                android.net.NetworkAgent.CMD_REPORT_NETWORK_STATUS,
                                (valid ? NetworkAgent.VALID_NETWORK : NetworkAgent.INVALID_NETWORK),
                                0, null);
                    }
                    break;
                }
                case NetworkMonitor.EVENT_NETWORK_LINGER_COMPLETE: {
                    NetworkAgentInfo nai = (NetworkAgentInfo)msg.obj;
                    if (isLiveNetworkAgent(nai, "EVENT_NETWORK_LINGER_COMPLETE")) {
                        handleLingerComplete(nai);
                    }
                    break;
                }
                case NetworkMonitor.EVENT_PROVISIONING_NOTIFICATION: {
                    if (msg.arg1 == 0) {
                        setProvNotificationVisibleIntent(false, msg.arg2, 0, null, null);
                    } else {
                        NetworkAgentInfo nai = null;
                        synchronized (mNetworkForNetId) {
                            nai = mNetworkForNetId.get(msg.arg2);
                        }
                        if (nai == null) {
                            loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                            break;
                        }
                        setProvNotificationVisibleIntent(true, msg.arg2, nai.networkInfo.getType(),
                                nai.networkInfo.getExtraInfo(), (PendingIntent)msg.obj);
                    }
                    break;
                }
                case NetworkStateTracker.EVENT_STATE_CHANGED: {
                    info = (NetworkInfo) msg.obj;
                    NetworkInfo.State state = info.getState();

                    if (VDBG || (state == NetworkInfo.State.CONNECTED) ||
                            (state == NetworkInfo.State.DISCONNECTED) ||
                            (state == NetworkInfo.State.SUSPENDED)) {
                        log("ConnectivityChange for " +
                            info.getTypeName() + ": " +
                            state + "/" + info.getDetailedState());
                    }

                    EventLogTags.writeConnectivityStateChanged(
                            info.getType(), info.getSubtype(), info.getDetailedState().ordinal());

                    if (info.isConnectedToProvisioningNetwork()) {
                        /**
                         * TODO: Create ConnectivityManager.TYPE_MOBILE_PROVISIONING
                         * for now its an in between network, its a network that
                         * is actually a default network but we don't want it to be
                         * announced as such to keep background applications from
                         * trying to use it. It turns out that some still try so we
                         * take the additional step of clearing any default routes
                         * to the link that may have incorrectly setup by the lower
                         * levels.
                         */
                        LinkProperties lp = getLinkPropertiesForType(info.getType());
                        if (DBG) {
                            log("EVENT_STATE_CHANGED: connected to provisioning network, lp=" + lp);
                        }

                        // Clear any default routes setup by the radio so
                        // any activity by applications trying to use this
                        // connection will fail until the provisioning network
                        // is enabled.
                        /*
                        for (RouteInfo r : lp.getRoutes()) {
                            removeRoute(lp, r, TO_DEFAULT_TABLE,
                                        mNetTrackers[info.getType()].getNetwork().netId);
                        }
                        */
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                    } else if (state == NetworkInfo.State.SUSPENDED) {
                    } else if (state == NetworkInfo.State.CONNECTED) {
                    //    handleConnect(info);
                    }
                    notifyLockdownVpn(null);
                    break;
                }
                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED: {
                    info = (NetworkInfo) msg.obj;
                    // TODO: Temporary allowing network configuration
                    //       change not resetting sockets.
                    //       @see bug/4455071
                    /*
                    handleConnectivityChange(info.getType(), mCurrentLinkProperties[info.getType()],
                            false);
                    */
                    break;
                }
            }
        }
    }

    // Cancel any lingering so the linger timeout doesn't teardown a network.
    // This should be called when a network begins satisfying a NetworkRequest.
    // Note: depending on what state the NetworkMonitor is in (e.g.,
    // if it's awaiting captive portal login, or if validation failed), this
    // may trigger a re-evaluation of the network.
    private void unlinger(NetworkAgentInfo nai) {
        if (VDBG) log("Canceling linger of " + nai.name());
        // If network has never been validated, it cannot have been lingered, so don't bother
        // needlessly triggering a re-evaluation.
        if (!nai.everValidated) return;
        nai.networkLingered.clear();
        nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
    }

    private void handleAsyncChannelHalfConnect(Message msg) {
        AsyncChannel ac = (AsyncChannel) msg.obj;
        if (mNetworkFactoryInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkFactory connected");
                // A network factory has connected.  Send it all current NetworkRequests.
                for (NetworkRequestInfo nri : mNetworkRequests.values()) {
                    if (nri.isRequest == false) continue;
                    NetworkAgentInfo nai = mNetworkForRequestId.get(nri.request.requestId);
                    ac.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK,
                            (nai != null ? nai.getCurrentScore() : 0), 0, nri.request);
                }
            } else {
                loge("Error connecting NetworkFactory");
                mNetworkFactoryInfos.remove(msg.obj);
            }
        } else if (mNetworkAgentInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkAgent connected");
                // A network agent has requested a connection.  Establish the connection.
                mNetworkAgentInfos.get(msg.replyTo).asyncChannel.
                        sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
            } else {
                loge("Error connecting NetworkAgent");
                NetworkAgentInfo nai = mNetworkAgentInfos.remove(msg.replyTo);
                if (nai != null) {
                    synchronized (mNetworkForNetId) {
                        mNetworkForNetId.remove(nai.network.netId);
                    }
                    // Just in case.
                    mLegacyTypeTracker.remove(nai);
                }
            }
        }
    }

    private void handleAsyncChannelDisconnected(Message msg) {
        NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
        if (nai != null) {
            if (DBG) {
                log(nai.name() + " got DISCONNECTED, was satisfying " + nai.networkRequests.size());
            }
            // A network agent has disconnected.
            if (nai.created) {
                // Tell netd to clean up the configuration for this network
                // (routing rules, DNS, etc).
                try {
                    mNetd.removeNetwork(nai.network.netId);
                } catch (Exception e) {
                    loge("Exception removing network: " + e);
                }
            }
            // TODO - if we move the logic to the network agent (have them disconnect
            // because they lost all their requests or because their score isn't good)
            // then they would disconnect organically, report their new state and then
            // disconnect the channel.
            if (nai.networkInfo.isConnected()) {
                nai.networkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                        null, null);
            }
            if (isDefaultNetwork(nai)) {
                mDefaultInetConditionPublished = 0;
            }
            notifyIfacesChanged();
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOST);
            nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_DISCONNECTED);
            mNetworkAgentInfos.remove(msg.replyTo);
            updateClat(null, nai.linkProperties, nai);
            mLegacyTypeTracker.remove(nai);
            synchronized (mNetworkForNetId) {
                mNetworkForNetId.remove(nai.network.netId);
            }
            // Since we've lost the network, go through all the requests that
            // it was satisfying and see if any other factory can satisfy them.
            // TODO: This logic may be better replaced with a call to rematchAllNetworksAndRequests
            final ArrayList<NetworkAgentInfo> toActivate = new ArrayList<NetworkAgentInfo>();
            for (int i = 0; i < nai.networkRequests.size(); i++) {
                NetworkRequest request = nai.networkRequests.valueAt(i);
                NetworkAgentInfo currentNetwork = mNetworkForRequestId.get(request.requestId);
                if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                    if (DBG) {
                        log("Checking for replacement network to handle request " + request );
                    }
                    mNetworkForRequestId.remove(request.requestId);
                    sendUpdatedScoreToFactories(request, 0);
                    NetworkAgentInfo alternative = null;
                    for (NetworkAgentInfo existing : mNetworkAgentInfos.values()) {
                        if (existing.satisfies(request) &&
                                (alternative == null ||
                                 alternative.getCurrentScore() < existing.getCurrentScore())) {
                            alternative = existing;
                        }
                    }
                    if (alternative != null) {
                        if (DBG) log(" found replacement in " + alternative.name());
                        if (!toActivate.contains(alternative)) {
                            toActivate.add(alternative);
                        }
                    }
                }
            }
            if (nai.networkRequests.get(mDefaultRequest.requestId) != null) {
                removeDataActivityTracking(nai);
                notifyLockdownVpn(nai);
                requestNetworkTransitionWakelock(nai.name());
            }
            for (NetworkAgentInfo networkToActivate : toActivate) {
                unlinger(networkToActivate);
                rematchNetworkAndRequests(networkToActivate, NascentState.NOT_JUST_VALIDATED,
                        ReapUnvalidatedNetworks.DONT_REAP);
            }
        }
    }

    // If this method proves to be too slow then we can maintain a separate
    // pendingIntent => NetworkRequestInfo map.
    // This method assumes that every non-null PendingIntent maps to exactly 1 NetworkRequestInfo.
    private NetworkRequestInfo findExistingNetworkRequestInfo(PendingIntent pendingIntent) {
        Intent intent = pendingIntent.getIntent();
        for (Map.Entry<NetworkRequest, NetworkRequestInfo> entry : mNetworkRequests.entrySet()) {
            PendingIntent existingPendingIntent = entry.getValue().mPendingIntent;
            if (existingPendingIntent != null &&
                    existingPendingIntent.getIntent().filterEquals(intent)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void handleRegisterNetworkRequestWithIntent(Message msg) {
        final NetworkRequestInfo nri = (NetworkRequestInfo) (msg.obj);

        NetworkRequestInfo existingRequest = findExistingNetworkRequestInfo(nri.mPendingIntent);
        if (existingRequest != null) { // remove the existing request.
            if (DBG) log("Replacing " + existingRequest.request + " with "
                    + nri.request + " because their intents matched.");
            handleReleaseNetworkRequest(existingRequest.request, getCallingUid());
        }
        handleRegisterNetworkRequest(msg);
    }

    private void handleRegisterNetworkRequest(Message msg) {
        final NetworkRequestInfo nri = (NetworkRequestInfo) (msg.obj);

        mNetworkRequests.put(nri.request, nri);

        // TODO: This logic may be better replaced with a call to rematchNetworkAndRequests

        // Check for the best currently alive network that satisfies this request
        NetworkAgentInfo bestNetwork = null;
        for (NetworkAgentInfo network : mNetworkAgentInfos.values()) {
            if (DBG) log("handleRegisterNetworkRequest checking " + network.name());
            if (network.satisfies(nri.request)) {
                if (DBG) log("apparently satisfied.  currentScore=" + network.getCurrentScore());
                if (!nri.isRequest) {
                    // Not setting bestNetwork here as a listening NetworkRequest may be
                    // satisfied by multiple Networks.  Instead the request is added to
                    // each satisfying Network and notified about each.
                    network.addRequest(nri.request);
                    notifyNetworkCallback(network, nri);
                } else if (bestNetwork == null ||
                        bestNetwork.getCurrentScore() < network.getCurrentScore()) {
                    bestNetwork = network;
                }
            }
        }
        if (bestNetwork != null) {
            if (DBG) log("using " + bestNetwork.name());
            unlinger(bestNetwork);
            bestNetwork.addRequest(nri.request);
            mNetworkForRequestId.put(nri.request.requestId, bestNetwork);
            notifyNetworkCallback(bestNetwork, nri);
            if (nri.request.legacyType != TYPE_NONE) {
                mLegacyTypeTracker.add(nri.request.legacyType, bestNetwork);
            }
        }

        if (nri.isRequest) {
            if (DBG) log("sending new NetworkRequest to factories");
            final int score = bestNetwork == null ? 0 : bestNetwork.getCurrentScore();
            for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
                nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score,
                        0, nri.request);
            }
        }
    }

    private void handleReleaseNetworkRequestWithIntent(PendingIntent pendingIntent,
            int callingUid) {
        NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            handleReleaseNetworkRequest(nri.request, callingUid);
        }
    }

    // Is nai unneeded by all NetworkRequests (and should be disconnected)?
    // For validated Networks this is simply whether it is satsifying any NetworkRequests.
    // For unvalidated Networks this is whether it is satsifying any NetworkRequests or
    // were it to become validated, would it have a chance of satisfying any NetworkRequests.
    private boolean unneeded(NetworkAgentInfo nai) {
        if (!nai.created || nai.isVPN()) return false;
        boolean unneeded = true;
        if (nai.everValidated) {
            for (int i = 0; i < nai.networkRequests.size() && unneeded; i++) {
                final NetworkRequest nr = nai.networkRequests.valueAt(i);
                try {
                    if (isRequest(nr)) unneeded = false;
                } catch (Exception e) {
                    loge("Request " + nr + " not found in mNetworkRequests.");
                    loge("  it came from request list  of " + nai.name());
                }
            }
        } else {
            for (NetworkRequestInfo nri : mNetworkRequests.values()) {
                // If this Network is already the highest scoring Network for a request, or if
                // there is hope for it to become one if it validated, then it is needed.
                if (nri.isRequest && nai.satisfies(nri.request) &&
                        (nai.networkRequests.get(nri.request.requestId) != null ||
                        // Note that this catches two important cases:
                        // 1. Unvalidated cellular will not be reaped when unvalidated WiFi
                        //    is currently satisfying the request.  This is desirable when
                        //    cellular ends up validating but WiFi does not.
                        // 2. Unvalidated WiFi will not be reaped when validated cellular
                        //    is currently satsifying the request.  This is desirable when
                        //    WiFi ends up validating and out scoring cellular.
                        mNetworkForRequestId.get(nri.request.requestId).getCurrentScore() <
                                nai.getCurrentScoreAsValidated())) {
                    unneeded = false;
                    break;
                }
            }
        }
        return unneeded;
    }

    private void handleReleaseNetworkRequest(NetworkRequest request, int callingUid) {
        NetworkRequestInfo nri = mNetworkRequests.get(request);
        if (nri != null) {
            if (Process.SYSTEM_UID != callingUid && nri.mUid != callingUid) {
                if (DBG) log("Attempt to release unowned NetworkRequest " + request);
                return;
            }
            if (DBG) log("releasing NetworkRequest " + request);
            nri.unlinkDeathRecipient();
            mNetworkRequests.remove(request);
            if (nri.isRequest) {
                // Find all networks that are satisfying this request and remove the request
                // from their request lists.
                // TODO - it's my understanding that for a request there is only a single
                // network satisfying it, so this loop is wasteful
                boolean wasKept = false;
                for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                    if (nai.networkRequests.get(nri.request.requestId) != null) {
                        nai.networkRequests.remove(nri.request.requestId);
                        if (DBG) {
                            log(" Removing from current network " + nai.name() +
                                    ", leaving " + nai.networkRequests.size() +
                                    " requests.");
                        }
                        if (unneeded(nai)) {
                            if (DBG) log("no live requests for " + nai.name() + "; disconnecting");
                            teardownUnneededNetwork(nai);
                        } else {
                            // suspect there should only be one pass through here
                            // but if any were kept do the check below
                            wasKept |= true;
                        }
                    }
                }

                NetworkAgentInfo nai = mNetworkForRequestId.get(nri.request.requestId);
                if (nai != null) {
                    mNetworkForRequestId.remove(nri.request.requestId);
                }
                // Maintain the illusion.  When this request arrived, we might have pretended
                // that a network connected to serve it, even though the network was already
                // connected.  Now that this request has gone away, we might have to pretend
                // that the network disconnected.  LegacyTypeTracker will generate that
                // phantom disconnect for this type.
                if (nri.request.legacyType != TYPE_NONE && nai != null) {
                    boolean doRemove = true;
                    if (wasKept) {
                        // check if any of the remaining requests for this network are for the
                        // same legacy type - if so, don't remove the nai
                        for (int i = 0; i < nai.networkRequests.size(); i++) {
                            NetworkRequest otherRequest = nai.networkRequests.valueAt(i);
                            if (otherRequest.legacyType == nri.request.legacyType &&
                                    isRequest(otherRequest)) {
                                if (DBG) log(" still have other legacy request - leaving");
                                doRemove = false;
                            }
                        }
                    }

                    if (doRemove) {
                        mLegacyTypeTracker.remove(nri.request.legacyType, nai);
                    }
                }

                for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
                    nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_CANCEL_REQUEST,
                            nri.request);
                }
            } else {
                // listens don't have a singular affectedNetwork.  Check all networks to see
                // if this listen request applies and remove it.
                for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                    nai.networkRequests.remove(nri.request.requestId);
                }
            }
            callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_RELEASED);
        }
    }

    private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_EXPIRE_NET_TRANSITION_WAKELOCK:
                case EVENT_CLEAR_NET_TRANSITION_WAKELOCK: {
                    String causedBy = null;
                    synchronized (ConnectivityService.this) {
                        if (msg.arg1 == mNetTransitionWakeLockSerialNumber &&
                                mNetTransitionWakeLock.isHeld()) {
                            mNetTransitionWakeLock.release();
                            causedBy = mNetTransitionWakeLockCausedBy;
                        } else {
                            break;
                        }
                    }
                    if (msg.what == EVENT_EXPIRE_NET_TRANSITION_WAKELOCK) {
                        log("Failed to find a new network - expiring NetTransition Wakelock");
                    } else {
                        log("NetTransition Wakelock (" + (causedBy == null ? "unknown" : causedBy) +
                                " cleared because we found a replacement network");
                    }
                    break;
                }
                case EVENT_APPLY_GLOBAL_HTTP_PROXY: {
                    handleDeprecatedGlobalHttpProxy();
                    break;
                }
                case EVENT_SET_DEPENDENCY_MET: {
                    boolean met = (msg.arg1 == ENABLED);
                    handleSetDependencyMet(msg.arg2, met);
                    break;
                }
                case EVENT_SEND_STICKY_BROADCAST_INTENT: {
                    Intent intent = (Intent)msg.obj;
                    sendStickyBroadcast(intent);
                    break;
                }
                case EVENT_ENABLE_FAIL_FAST_MOBILE_DATA: {
                    int tag = mEnableFailFastMobileDataTag.get();
                    if (msg.arg1 == tag) {
                        MobileDataStateTracker mobileDst =
                            (MobileDataStateTracker) mNetTrackers[ConnectivityManager.TYPE_MOBILE];
                        if (mobileDst != null) {
                            mobileDst.setEnableFailFastMobileData(msg.arg2);
                        }
                    } else {
                        log("EVENT_ENABLE_FAIL_FAST_MOBILE_DATA: stale arg1:" + msg.arg1
                                + " != tag:" + tag);
                    }
                    break;
                }
                case EVENT_SAMPLE_INTERVAL_ELAPSED: {
                    handleNetworkSamplingTimeout();
                    break;
                }
                case EVENT_PROXY_HAS_CHANGED: {
                    handleApplyDefaultProxy((ProxyInfo)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_FACTORY: {
                    handleRegisterNetworkFactory((NetworkFactoryInfo)msg.obj);
                    break;
                }
                case EVENT_UNREGISTER_NETWORK_FACTORY: {
                    handleUnregisterNetworkFactory((Messenger)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_AGENT: {
                    handleRegisterNetworkAgent((NetworkAgentInfo)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_REQUEST:
                case EVENT_REGISTER_NETWORK_LISTENER: {
                    handleRegisterNetworkRequest(msg);
                    break;
                }
                case EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT: {
                    handleRegisterNetworkRequestWithIntent(msg);
                    break;
                }
                case EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT: {
                    handleReleaseNetworkRequestWithIntent((PendingIntent) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_RELEASE_NETWORK_REQUEST: {
                    handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_SYSTEM_READY: {
                    for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                        nai.networkMonitor.systemReady = true;
                    }
                    break;
                }
            }
        }
    }

    // javadoc from interface
    public int tether(String iface) {
        ConnectivityManager.enforceTetherChangePermission(mContext);
        if (isTetheringSupported()) {
            return mTethering.tether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int untether(String iface) {
        ConnectivityManager.enforceTetherChangePermission(mContext);

        if (isTetheringSupported()) {
            return mTethering.untether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();

        if (isTetheringSupported()) {
            return mTethering.getLastTetherError(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - proper iface API for selection by property, inspection, etc
    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableUsbRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableWifiRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableBluetoothRegexs();
        } else {
            return new String[0];
        }
    }

    public int setUsbTethering(boolean enable) {
        ConnectivityManager.enforceTetherChangePermission(mContext);
        if (isTetheringSupported()) {
            return mTethering.setUsbTethering(enable);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - move iface listing, queries, etc to new module
    // javadoc from interface
    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetherableIfaces();
    }

    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfaces();
    }

    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getErroredIfaces();
    }

    public String[] getTetheredDhcpRanges() {
        enforceConnectivityInternalPermission();
        return mTethering.getTetheredDhcpRanges();
    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    public boolean isTetheringSupported() {
        enforceTetherAccessPermission();
        int defaultVal = (SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1);
        boolean tetherEnabledInSettings = (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TETHER_SUPPORTED, defaultVal) != 0)
                && !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING);
        return tetherEnabledInSettings && ((mTethering.getTetherableUsbRegexs().length != 0 ||
                mTethering.getTetherableWifiRegexs().length != 0 ||
                mTethering.getTetherableBluetoothRegexs().length != 0) &&
                mTethering.getUpstreamIfaceTypes().length != 0);
    }

    // Called when we lose the default network and have no replacement yet.
    // This will automatically be cleared after X seconds or a new default network
    // becomes CONNECTED, whichever happens first.  The timer is started by the
    // first caller and not restarted by subsequent callers.
    private void requestNetworkTransitionWakelock(String forWhom) {
        int serialNum = 0;
        synchronized (this) {
            if (mNetTransitionWakeLock.isHeld()) return;
            serialNum = ++mNetTransitionWakeLockSerialNumber;
            mNetTransitionWakeLock.acquire();
            mNetTransitionWakeLockCausedBy = forWhom;
        }
        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                EVENT_EXPIRE_NET_TRANSITION_WAKELOCK, serialNum, 0),
                mNetTransitionWakeLockTimeout);
        return;
    }

    // 100 percent is full good, 0 is full bad.
    public void reportInetCondition(int networkType, int percentage) {
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) return;
        boolean isGood = percentage > 50;
        // Revalidate if the app report does not match our current validated state.
        if (isGood != nai.lastValidated) {
            // Make the message logged by reportBadNetwork below less confusing.
            if (DBG && isGood) log("reportInetCondition: type=" + networkType + " ok, revalidate");
            reportBadNetwork(nai.network);
        }
    }

    public void reportBadNetwork(Network network) {
        enforceAccessPermission();
        enforceInternetPermission();

        if (network == null) return;

        final int uid = Binder.getCallingUid();
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) return;
        if (DBG) log("reportBadNetwork(" + nai.name() + ") by " + uid);
        synchronized (nai) {
            // Validating an uncreated network could result in a call to rematchNetworkAndRequests()
            // which isn't meant to work on uncreated networks.
            if (!nai.created) return;

            if (isNetworkWithLinkPropertiesBlocked(nai.linkProperties, uid)) return;

            nai.networkMonitor.sendMessage(NetworkMonitor.CMD_FORCE_REEVALUATION, uid);
        }
    }

    public ProxyInfo getDefaultProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            ProxyInfo ret = mGlobalProxy;
            if ((ret == null) && !mDefaultProxyDisabled) ret = mDefaultProxy;
            return ret;
        }
    }

    // Convert empty ProxyInfo's to null as null-checks are used to determine if proxies are present
    // (e.g. if mGlobalProxy==null fall back to network-specific proxy, if network-specific
    // proxy is null then there is no proxy in place).
    private ProxyInfo canonicalizeProxyInfo(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())
                && (proxy.getPacFileUrl() == null || Uri.EMPTY.equals(proxy.getPacFileUrl()))) {
            proxy = null;
        }
        return proxy;
    }

    // ProxyInfo equality function with a couple modifications over ProxyInfo.equals() to make it
    // better for determining if a new proxy broadcast is necessary:
    // 1. Canonicalize empty ProxyInfos to null so an empty proxy compares equal to null so as to
    //    avoid unnecessary broadcasts.
    // 2. Make sure all parts of the ProxyInfo's compare true, including the host when a PAC URL
    //    is in place.  This is important so legacy PAC resolver (see com.android.proxyhandler)
    //    changes aren't missed.  The legacy PAC resolver pretends to be a simple HTTP proxy but
    //    actually uses the PAC to resolve; this results in ProxyInfo's with PAC URL, host and port
    //    all set.
    private boolean proxyInfoEqual(ProxyInfo a, ProxyInfo b) {
        a = canonicalizeProxyInfo(a);
        b = canonicalizeProxyInfo(b);
        // ProxyInfo.equals() doesn't check hosts when PAC URLs are present, but we need to check
        // hosts even when PAC URLs are present to account for the legacy PAC resolver.
        return Objects.equals(a, b) && (a == null || Objects.equals(a.getHost(), b.getHost()));
    }

    public void setGlobalProxy(ProxyInfo proxyProperties) {
        enforceConnectivityInternalPermission();

        synchronized (mProxyLock) {
            if (proxyProperties == mGlobalProxy) return;
            if (proxyProperties != null && proxyProperties.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyProperties)) return;

            String host = "";
            int port = 0;
            String exclList = "";
            String pacFileUrl = "";
            if (proxyProperties != null && (!TextUtils.isEmpty(proxyProperties.getHost()) ||
                    !Uri.EMPTY.equals(proxyProperties.getPacFileUrl()))) {
                if (!proxyProperties.isValid()) {
                    if (DBG)
                        log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                    return;
                }
                mGlobalProxy = new ProxyInfo(proxyProperties);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionListAsString();
                if (!Uri.EMPTY.equals(proxyProperties.getPacFileUrl())) {
                    pacFileUrl = proxyProperties.getPacFileUrl().toString();
                }
            } else {
                mGlobalProxy = null;
            }
            ContentResolver res = mContext.getContentResolver();
            final long token = Binder.clearCallingIdentity();
            try {
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST, host);
                Settings.Global.putInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, port);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                        exclList);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC, pacFileUrl);
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            if (mGlobalProxy == null) {
                proxyProperties = mDefaultProxy;
            }
            sendProxyBroadcast(proxyProperties);
        }
    }

    private void loadGlobalProxy() {
        ContentResolver res = mContext.getContentResolver();
        String host = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST);
        int port = Settings.Global.getInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, 0);
        String exclList = Settings.Global.getString(res,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        String pacFileUrl = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC);
        if (!TextUtils.isEmpty(host) || !TextUtils.isEmpty(pacFileUrl)) {
            ProxyInfo proxyProperties;
            if (!TextUtils.isEmpty(pacFileUrl)) {
                proxyProperties = new ProxyInfo(pacFileUrl);
            } else {
                proxyProperties = new ProxyInfo(host, port, exclList);
            }
            if (!proxyProperties.isValid()) {
                if (DBG) log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                return;
            }

            synchronized (mProxyLock) {
                mGlobalProxy = proxyProperties;
            }
        }
    }

    public ProxyInfo getGlobalProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            return mGlobalProxy;
        }
    }

    private void handleApplyDefaultProxy(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())
                && Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            proxy = null;
        }
        synchronized (mProxyLock) {
            if (mDefaultProxy != null && mDefaultProxy.equals(proxy)) return;
            if (mDefaultProxy == proxy) return; // catches repeated nulls
            if (proxy != null &&  !proxy.isValid()) {
                if (DBG) log("Invalid proxy properties, ignoring: " + proxy.toString());
                return;
            }

            // This call could be coming from the PacManager, containing the port of the local
            // proxy.  If this new proxy matches the global proxy then copy this proxy to the
            // global (to get the correct local port), and send a broadcast.
            // TODO: Switch PacManager to have its own message to send back rather than
            // reusing EVENT_HAS_CHANGED_PROXY and this call to handleApplyDefaultProxy.
            if ((mGlobalProxy != null) && (proxy != null)
                    && (!Uri.EMPTY.equals(proxy.getPacFileUrl()))
                    && proxy.getPacFileUrl().equals(mGlobalProxy.getPacFileUrl())) {
                mGlobalProxy = proxy;
                sendProxyBroadcast(mGlobalProxy);
                return;
            }
            mDefaultProxy = proxy;

            if (mGlobalProxy != null) return;
            if (!mDefaultProxyDisabled) {
                sendProxyBroadcast(proxy);
            }
        }
    }

    // If the proxy has changed from oldLp to newLp, resend proxy broadcast with default proxy.
    // This method gets called when any network changes proxy, but the broadcast only ever contains
    // the default proxy (even if it hasn't changed).
    // TODO: Deprecate the broadcast extras as they aren't necessarily applicable in a multi-network
    // world where an app might be bound to a non-default network.
    private void updateProxy(LinkProperties newLp, LinkProperties oldLp, NetworkAgentInfo nai) {
        ProxyInfo newProxyInfo = newLp == null ? null : newLp.getHttpProxy();
        ProxyInfo oldProxyInfo = oldLp == null ? null : oldLp.getHttpProxy();

        if (!proxyInfoEqual(newProxyInfo, oldProxyInfo)) {
            sendProxyBroadcast(getDefaultProxy());
        }
    }

    private void handleDeprecatedGlobalHttpProxy() {
        String proxy = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.HTTP_PROXY);
        if (!TextUtils.isEmpty(proxy)) {
            String data[] = proxy.split(":");
            if (data.length == 0) {
                return;
            }

            String proxyHost =  data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            ProxyInfo p = new ProxyInfo(data[0], proxyPort, "");
            setGlobalProxy(p);
        }
    }

    private void sendProxyBroadcast(ProxyInfo proxy) {
        if (proxy == null) proxy = new ProxyInfo("", 0, "");
        if (mPacManager.setCurrentProxyScriptUrl(proxy)) return;
        if (DBG) log("sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static class SettingsObserver extends ContentObserver {
        private int mWhat;
        private Handler mHandler;
        SettingsObserver(Handler handler, int what) {
            super(handler);
            mHandler = handler;
            mWhat = what;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.HTTP_PROXY), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mWhat).sendToTarget();
        }
    }

    private static void log(String s) {
        Slog.d(TAG, s);
    }

    private static void loge(String s) {
        Slog.e(TAG, s);
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

    /**
     * Prepare for a VPN application.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public boolean prepareVpn(String oldPackage, String newPackage) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).prepare(oldPackage, newPackage);
        }
    }

    /**
     * Set whether the current VPN package has the ability to launch VPNs without
     * user intervention. This method is used by system-privileged apps.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public void setVpnPackageAuthorization(boolean authorized) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            mVpns.get(user).setPackageAuthorization(authorized);
        }
    }

    /**
     * Configure a TUN interface and return its file descriptor. Parameters
     * are encoded and opaque to this class. This method is used by VpnBuilder
     * and not available in ConnectivityManager. Permissions are checked in
     * Vpn class.
     * @hide
     */
    @Override
    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).establish(config);
        }
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     */
    @Override
    public void startLegacyVpn(VpnProfile profile) {
        throwIfLockdownEnabled();
        final LinkProperties egress = getActiveLinkProperties();
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            mVpns.get(user).startLegacyVpn(profile, mKeyStore, egress);
        }
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo() {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).getLegacyVpnInfo();
        }
    }

    /**
     * Returns the information of the ongoing VPN. This method is used by VpnDialogs and
     * not available in ConnectivityManager.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public VpnConfig getVpnConfig() {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).getVpnConfig();
        }
    }

    @Override
    public boolean updateLockdownVpn() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Lockdown VPN only available to AID_SYSTEM");
            return false;
        }

        // Tear down existing lockdown if profile was removed
        mLockdownEnabled = LockdownVpnTracker.isEnabled();
        if (mLockdownEnabled) {
            if (!mKeyStore.isUnlocked()) {
                Slog.w(TAG, "KeyStore locked; unable to create LockdownTracker");
                return false;
            }

            final String profileName = new String(mKeyStore.get(Credentials.LOCKDOWN_VPN));
            final VpnProfile profile = VpnProfile.decode(
                    profileName, mKeyStore.get(Credentials.VPN + profileName));
            int user = UserHandle.getUserId(Binder.getCallingUid());
            synchronized(mVpns) {
                setLockdownTracker(new LockdownVpnTracker(mContext, mNetd, this, mVpns.get(user),
                            profile));
            }
        } else {
            setLockdownTracker(null);
        }

        return true;
    }

    /**
     * Internally set new {@link LockdownVpnTracker}, shutting down any existing
     * {@link LockdownVpnTracker}. Can be {@code null} to disable lockdown.
     */
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        // Shutdown any existing tracker
        final LockdownVpnTracker existing = mLockdownTracker;
        mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }

        try {
            if (tracker != null) {
                mNetd.setFirewallEnabled(true);
                mNetd.setFirewallInterfaceRule("lo", true);
                mLockdownTracker = tracker;
                mLockdownTracker.init();
            } else {
                mNetd.setFirewallEnabled(false);
            }
        } catch (RemoteException e) {
            // ignored; NMS lives inside system_server
        }
    }

    private void throwIfLockdownEnabled() {
        if (mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    public void supplyMessenger(int networkType, Messenger messenger) {
        enforceConnectivityInternalPermission();

        if (isNetworkTypeValid(networkType) && mNetTrackers[networkType] != null) {
            mNetTrackers[networkType].supplyMessenger(messenger);
        }
    }

    public int findConnectionTypeForIface(String iface) {
        enforceConnectivityInternalPermission();

        if (TextUtils.isEmpty(iface)) return ConnectivityManager.TYPE_NONE;

        synchronized(mNetworkForNetId) {
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                NetworkAgentInfo nai = mNetworkForNetId.valueAt(i);
                LinkProperties lp = nai.linkProperties;
                if (lp != null && iface.equals(lp.getInterfaceName()) && nai.networkInfo != null) {
                    return nai.networkInfo.getType();
                }
            }
        }
        return ConnectivityManager.TYPE_NONE;
    }

    /**
     * Have mobile data fail fast if enabled.
     *
     * @param enabled DctConstants.ENABLED/DISABLED
     */
    private void setEnableFailFastMobileData(int enabled) {
        int tag;

        if (enabled == DctConstants.ENABLED) {
            tag = mEnableFailFastMobileDataTag.incrementAndGet();
        } else {
            tag = mEnableFailFastMobileDataTag.get();
        }
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_ENABLE_FAIL_FAST_MOBILE_DATA, tag,
                         enabled));
    }

    @Override
    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        // TODO: Remove?  Any reason to trigger a provisioning check?
        return -1;
    }

    private static final String NOTIFICATION_ID = "CaptivePortal.Notification";
    private volatile boolean mIsNotificationVisible = false;

    private void setProvNotificationVisible(boolean visible, int networkType, String action) {
        if (DBG) {
            log("setProvNotificationVisible: E visible=" + visible + " networkType=" + networkType
                + " action=" + action);
        }
        Intent intent = new Intent(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
        // Concatenate the range of types onto the range of NetIDs.
        int id = MAX_NET_ID + 1 + (networkType - ConnectivityManager.TYPE_NONE);
        setProvNotificationVisibleIntent(visible, id, networkType, null, pendingIntent);
    }

    /**
     * Show or hide network provisioning notificaitons.
     *
     * @param id an identifier that uniquely identifies this notification.  This must match
     *         between show and hide calls.  We use the NetID value but for legacy callers
     *         we concatenate the range of types with the range of NetIDs.
     */
    private void setProvNotificationVisibleIntent(boolean visible, int id, int networkType,
            String extraInfo, PendingIntent intent) {
        if (DBG) {
            log("setProvNotificationVisibleIntent: E visible=" + visible + " networkType=" +
                networkType + " extraInfo=" + extraInfo);
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            CharSequence title;
            CharSequence details;
            int icon;
            Notification notification = new Notification();
            switch (networkType) {
                case ConnectivityManager.TYPE_WIFI:
                    title = r.getString(R.string.wifi_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            extraInfo);
                    icon = R.drawable.stat_notify_wifi_in_range;
                    break;
                case ConnectivityManager.TYPE_MOBILE:
                case ConnectivityManager.TYPE_MOBILE_HIPRI:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    // TODO: Change this to pull from NetworkInfo once a printable
                    // name has been added to it
                    details = mTelephonyManager.getNetworkOperatorName();
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
                default:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            extraInfo);
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
            }

            notification.when = 0;
            notification.icon = icon;
            notification.flags = Notification.FLAG_AUTO_CANCEL;
            notification.tickerText = title;
            notification.color = mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color);
            notification.setLatestEventInfo(mContext, title, details, notification.contentIntent);
            notification.contentIntent = intent;

            try {
                notificationManager.notify(NOTIFICATION_ID, id, notification);
            } catch (NullPointerException npe) {
                loge("setNotificaitionVisible: visible notificationManager npe=" + npe);
                npe.printStackTrace();
            }
        } else {
            try {
                notificationManager.cancel(NOTIFICATION_ID, id);
            } catch (NullPointerException npe) {
                loge("setNotificaitionVisible: cancel notificationManager npe=" + npe);
                npe.printStackTrace();
            }
        }
        mIsNotificationVisible = visible;
    }

    /** Location to an updatable file listing carrier provisioning urls.
     *  An example:
     *
     * <?xml version="1.0" encoding="utf-8"?>
     *  <provisioningUrls>
     *   <provisioningUrl mcc="310" mnc="4">http://myserver.com/foo?mdn=%3$s&amp;iccid=%1$s&amp;imei=%2$s</provisioningUrl>
     *   <redirectedUrl mcc="310" mnc="4">http://www.google.com</redirectedUrl>
     *  </provisioningUrls>
     */
    private static final String PROVISIONING_URL_PATH =
            "/data/misc/radio/provisioning_urls.xml";
    private final File mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);

    /** XML tag for root element. */
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    /** XML tag for individual url */
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    /** XML tag for redirected url */
    private static final String TAG_REDIRECTED_URL = "redirectedUrl";
    /** XML attribute for mcc */
    private static final String ATTR_MCC = "mcc";
    /** XML attribute for mnc */
    private static final String ATTR_MNC = "mnc";

    private static final int REDIRECTED_PROVISIONING = 1;
    private static final int PROVISIONING = 2;

    private String getProvisioningUrlBaseFromFile(int type) {
        FileReader fileReader = null;
        XmlPullParser parser = null;
        Configuration config = mContext.getResources().getConfiguration();
        String tagType;

        switch (type) {
            case PROVISIONING:
                tagType = TAG_PROVISIONING_URL;
                break;
            case REDIRECTED_PROVISIONING:
                tagType = TAG_REDIRECTED_URL;
                break;
            default:
                throw new RuntimeException("getProvisioningUrlBaseFromFile: Unexpected parameter " +
                        type);
        }

        try {
            fileReader = new FileReader(mProvisioningUrlFile);
            parser = Xml.newPullParser();
            parser.setInput(fileReader);
            XmlUtils.beginDocument(parser, TAG_PROVISIONING_URLS);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (element.equals(tagType)) {
                    String mcc = parser.getAttributeValue(null, ATTR_MCC);
                    try {
                        if (mcc != null && Integer.parseInt(mcc) == config.mcc) {
                            String mnc = parser.getAttributeValue(null, ATTR_MNC);
                            if (mnc != null && Integer.parseInt(mnc) == config.mnc) {
                                parser.next();
                                if (parser.getEventType() == XmlPullParser.TEXT) {
                                    return parser.getText();
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        loge("NumberFormatException in getProvisioningUrlBaseFromFile: " + e);
                    }
                }
            }
            return null;
        } catch (FileNotFoundException e) {
            loge("Carrier Provisioning Urls file not found");
        } catch (XmlPullParserException e) {
            loge("Xml parser exception reading Carrier Provisioning Urls file: " + e);
        } catch (IOException e) {
            loge("I/O exception reading Carrier Provisioning Urls file: " + e);
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {}
            }
        }
        return null;
    }

    @Override
    public String getMobileRedirectedProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile(REDIRECTED_PROVISIONING);
        if (TextUtils.isEmpty(url)) {
            url = mContext.getResources().getString(R.string.mobile_redirected_provisioning_url);
        }
        return url;
    }

    @Override
    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile(PROVISIONING);
        if (TextUtils.isEmpty(url)) {
            url = mContext.getResources().getString(R.string.mobile_provisioning_url);
            log("getMobileProvisioningUrl: mobile_provisioining_url from resource =" + url);
        } else {
            log("getMobileProvisioningUrl: mobile_provisioning_url from File =" + url);
        }
        // populate the iccid, imei and phone number in the provisioning url.
        if (!TextUtils.isEmpty(url)) {
            String phoneNumber = mTelephonyManager.getLine1Number();
            if (TextUtils.isEmpty(phoneNumber)) {
                phoneNumber = "0000000000";
            }
            url = String.format(url,
                    mTelephonyManager.getSimSerialNumber() /* ICCID */,
                    mTelephonyManager.getDeviceId() /* IMEI */,
                    phoneNumber /* Phone numer */);
        }

        return url;
    }

    @Override
    public void setProvisioningNotificationVisible(boolean visible, int networkType,
            String action) {
        enforceConnectivityInternalPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            setProvNotificationVisible(visible, networkType, action);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setAirplaneMode(boolean enable) {
        enforceConnectivityInternalPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            final ContentResolver cr = mContext.getContentResolver();
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, enable ? 1 : 0);
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", enable);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void onUserStart(int userId) {
        synchronized(mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn != null) {
                loge("Starting user already has a VPN");
                return;
            }
            userVpn = new Vpn(mHandler.getLooper(), mContext, mNetd, this, userId);
            mVpns.put(userId, userVpn);
        }
    }

    private void onUserStop(int userId) {
        synchronized(mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn == null) {
                loge("Stopping user has no VPN");
                return;
            }
            mVpns.delete(userId);
        }
    }

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_STARTING.equals(action)) {
                onUserStart(userId);
            } else if (Intent.ACTION_USER_STOPPING.equals(action)) {
                onUserStop(userId);
            }
        }
    };

    /* Infrastructure for network sampling */

    private void handleNetworkSamplingTimeout() {

        if (SAMPLE_DBG) log("Sampling interval elapsed, updating statistics ..");

        // initialize list of interfaces ..
        Map<String, SamplingDataTracker.SamplingSnapshot> mapIfaceToSample =
                new HashMap<String, SamplingDataTracker.SamplingSnapshot>();
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                String ifaceName = tracker.getNetworkInterfaceName();
                if (ifaceName != null) {
                    mapIfaceToSample.put(ifaceName, null);
                }
            }
        }

        // Read samples for all interfaces
        SamplingDataTracker.getSamplingSnapshots(mapIfaceToSample);

        // process samples for all networks
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                String ifaceName = tracker.getNetworkInterfaceName();
                SamplingDataTracker.SamplingSnapshot ss = mapIfaceToSample.get(ifaceName);
                if (ss != null) {
                    // end the previous sampling cycle
                    tracker.stopSampling(ss);
                    // start a new sampling cycle ..
                    tracker.startSampling(ss);
                }
            }
        }

        if (SAMPLE_DBG) log("Done.");

        int samplingIntervalInSeconds = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS,
                DEFAULT_SAMPLING_INTERVAL_IN_SECONDS);

        if (SAMPLE_DBG) {
            log("Setting timer for " + String.valueOf(samplingIntervalInSeconds) + "seconds");
        }

        setAlarm(samplingIntervalInSeconds * 1000, mSampleIntervalElapsedIntent);
    }

    /**
     * Sets a network sampling alarm.
     */
    void setAlarm(int timeoutInMilliseconds, PendingIntent intent) {
        long wakeupTime = SystemClock.elapsedRealtime() + timeoutInMilliseconds;
        int alarmType;
        if (Resources.getSystem().getBoolean(
                R.bool.config_networkSamplingWakesDevice)) {
            alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP;
        } else {
            alarmType = AlarmManager.ELAPSED_REALTIME;
        }
        mAlarmManager.set(alarmType, wakeupTime, intent);
    }

    private final HashMap<Messenger, NetworkFactoryInfo> mNetworkFactoryInfos =
            new HashMap<Messenger, NetworkFactoryInfo>();
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests =
            new HashMap<NetworkRequest, NetworkRequestInfo>();

    private static class NetworkFactoryInfo {
        public final String name;
        public final Messenger messenger;
        public final AsyncChannel asyncChannel;

        public NetworkFactoryInfo(String name, Messenger messenger, AsyncChannel asyncChannel) {
            this.name = name;
            this.messenger = messenger;
            this.asyncChannel = asyncChannel;
        }
    }

    /**
     * Tracks info about the requester.
     * Also used to notice when the calling process dies so we can self-expire
     */
    private class NetworkRequestInfo implements IBinder.DeathRecipient {
        static final boolean REQUEST = true;
        static final boolean LISTEN = false;

        final NetworkRequest request;
        final PendingIntent mPendingIntent;
        boolean mPendingIntentSent;
        private final IBinder mBinder;
        final int mPid;
        final int mUid;
        final Messenger messenger;
        final boolean isRequest;

        NetworkRequestInfo(NetworkRequest r, PendingIntent pi, boolean isRequest) {
            request = r;
            mPendingIntent = pi;
            messenger = null;
            mBinder = null;
            mPid = getCallingPid();
            mUid = getCallingUid();
            this.isRequest = isRequest;
        }

        NetworkRequestInfo(Messenger m, NetworkRequest r, IBinder binder, boolean isRequest) {
            super();
            messenger = m;
            request = r;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            this.isRequest = isRequest;
            mPendingIntent = null;

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }

        public void binderDied() {
            log("ConnectivityService NetworkRequestInfo binderDied(" +
                    request + ", " + mBinder + ")");
            releaseNetworkRequest(request);
        }

        public String toString() {
            return (isRequest ? "Request" : "Listen") + " from uid/pid:" + mUid + "/" +
                    mPid + " for " + request +
                    (mPendingIntent == null ? "" : " to trigger " + mPendingIntent);
        }
    }

    @Override
    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, int timeoutMs, IBinder binder, int legacyType) {
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities);
        enforceMeteredApnPolicy(networkCapabilities);

        if (timeoutMs < 0 || timeoutMs > ConnectivityManager.MAX_NETWORK_REQUEST_TIMEOUT_MS) {
            throw new IllegalArgumentException("Bad timeout specified");
        }

        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, legacyType,
                nextNetworkRequestId());
        if (DBG) log("requestNetwork for " + networkRequest);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder,
                NetworkRequestInfo.REQUEST);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST, nri));
        if (timeoutMs > 0) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_TIMEOUT_NETWORK_REQUEST,
                    nri), timeoutMs);
        }
        return networkRequest;
    }

    private void enforceNetworkRequestPermissions(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                == false) {
            enforceConnectivityInternalPermission();
        } else {
            enforceChangePermission();
        }
    }

    private void enforceMeteredApnPolicy(NetworkCapabilities networkCapabilities) {
        // if UID is restricted, don't allow them to bring up metered APNs
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                == false) {
            final int uidRules;
            final int uid = Binder.getCallingUid();
            synchronized(mRulesLock) {
                uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
            }
            if ((uidRules & RULE_REJECT_METERED) != 0) {
                // we could silently fail or we can filter the available nets to only give
                // them those they have access to.  Chose the more useful
                networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
        }
    }

    @Override
    public NetworkRequest pendingRequestForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities);
        enforceMeteredApnPolicy(networkCapabilities);

        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, TYPE_NONE,
                nextNetworkRequestId());
        if (DBG) log("pendingRequest for " + networkRequest + " to trigger " + operation);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation,
                NetworkRequestInfo.REQUEST);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT,
                nri));
        return networkRequest;
    }

    private void releasePendingNetworkRequestWithDelay(PendingIntent operation) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT,
                getCallingUid(), 0, operation), mReleasePendingIntentDelayMs);
    }

    @Override
    public void releasePendingNetworkRequest(PendingIntent operation) {
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT,
                getCallingUid(), 0, operation));
    }

    @Override
    public NetworkRequest listenForNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, IBinder binder) {
        enforceAccessPermission();

        NetworkRequest networkRequest = new NetworkRequest(new NetworkCapabilities(
                networkCapabilities), TYPE_NONE, nextNetworkRequestId());
        if (DBG) log("listenForNetwork for " + networkRequest);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder,
                NetworkRequestInfo.LISTEN);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
        return networkRequest;
    }

    @Override
    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
    }

    @Override
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST, getCallingUid(),
                0, networkRequest));
    }

    @Override
    public void registerNetworkFactory(Messenger messenger, String name) {
        enforceConnectivityInternalPermission();
        NetworkFactoryInfo nfi = new NetworkFactoryInfo(name, messenger, new AsyncChannel());
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_FACTORY, nfi));
    }

    private void handleRegisterNetworkFactory(NetworkFactoryInfo nfi) {
        if (DBG) log("Got NetworkFactory Messenger for " + nfi.name);
        mNetworkFactoryInfos.put(nfi.messenger, nfi);
        nfi.asyncChannel.connect(mContext, mTrackerHandler, nfi.messenger);
    }

    @Override
    public void unregisterNetworkFactory(Messenger messenger) {
        enforceConnectivityInternalPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_UNREGISTER_NETWORK_FACTORY, messenger));
    }

    private void handleUnregisterNetworkFactory(Messenger messenger) {
        NetworkFactoryInfo nfi = mNetworkFactoryInfos.remove(messenger);
        if (nfi == null) {
            loge("Failed to find Messenger in unregisterNetworkFactory");
            return;
        }
        if (DBG) log("unregisterNetworkFactory for " + nfi.name);
    }

    /**
     * NetworkAgentInfo supporting a request by requestId.
     * These have already been vetted (their Capabilities satisfy the request)
     * and the are the highest scored network available.
     * the are keyed off the Requests requestId.
     */
    private final SparseArray<NetworkAgentInfo> mNetworkForRequestId =
            new SparseArray<NetworkAgentInfo>();

    private final SparseArray<NetworkAgentInfo> mNetworkForNetId =
            new SparseArray<NetworkAgentInfo>();

    // NetworkAgentInfo keyed off its connecting messenger
    // TODO - eval if we can reduce the number of lists/hashmaps/sparsearrays
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos =
            new HashMap<Messenger, NetworkAgentInfo>();

    // Note: if mDefaultRequest is changed, NetworkMonitor needs to be updated.
    private final NetworkRequest mDefaultRequest;

    private NetworkAgentInfo getDefaultNetwork() {
        return mNetworkForRequestId.get(mDefaultRequest.requestId);
    }

    private boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return nai == getDefaultNetwork();
    }

    public void registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkMisc networkMisc) {
        enforceConnectivityInternalPermission();

        // TODO: Instead of passing mDefaultRequest, provide an API to determine whether a Network
        // satisfies mDefaultRequest.
        NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(),
            new NetworkInfo(networkInfo), new LinkProperties(linkProperties),
            new NetworkCapabilities(networkCapabilities), currentScore, mContext, mTrackerHandler,
            new NetworkMisc(networkMisc), mDefaultRequest);
        synchronized (this) {
            nai.networkMonitor.systemReady = mSystemReady;
        }
        if (DBG) log("registerNetworkAgent " + nai);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_AGENT, nai));
    }

    private void handleRegisterNetworkAgent(NetworkAgentInfo na) {
        if (VDBG) log("Got NetworkAgent Messenger");
        mNetworkAgentInfos.put(na.messenger, na);
        assignNextNetId(na);
        na.asyncChannel.connect(mContext, mTrackerHandler, na.messenger);
        NetworkInfo networkInfo = na.networkInfo;
        na.networkInfo = null;
        updateNetworkInfo(na, networkInfo);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties oldLp) {
        LinkProperties newLp = networkAgent.linkProperties;
        int netId = networkAgent.network.netId;

        // The NetworkAgentInfo does not know whether clatd is running on its network or not. Before
        // we do anything else, make sure its LinkProperties are accurate.
        if (networkAgent.clatd != null) {
            networkAgent.clatd.fixupLinkProperties(oldLp);
        }

        updateInterfaces(newLp, oldLp, netId);
        updateMtu(newLp, oldLp);
        // TODO - figure out what to do for clat
//        for (LinkProperties lp : newLp.getStackedLinks()) {
//            updateMtu(lp, null);
//        }
        updateTcpBufferSizes(networkAgent);

        // TODO: deprecate and remove mDefaultDns when we can do so safely.
        // For now, use it only when the network has Internet access. http://b/18327075
        final boolean useDefaultDns = networkAgent.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
        final boolean flushDns = updateRoutes(newLp, oldLp, netId);
        updateDnses(newLp, oldLp, netId, flushDns, useDefaultDns);

        updateClat(newLp, oldLp, networkAgent);
        if (isDefaultNetwork(networkAgent)) {
            handleApplyDefaultProxy(newLp.getHttpProxy());
        } else {
            updateProxy(newLp, oldLp, networkAgent);
        }
        // TODO - move this check to cover the whole function
        if (!Objects.equals(newLp, oldLp)) {
            notifyIfacesChanged();
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_IP_CHANGED);
        }
    }

    private void updateClat(LinkProperties newLp, LinkProperties oldLp, NetworkAgentInfo nai) {
        final boolean wasRunningClat = nai.clatd != null && nai.clatd.isStarted();
        final boolean shouldRunClat = Nat464Xlat.requiresClat(nai);

        if (!wasRunningClat && shouldRunClat) {
            nai.clatd = new Nat464Xlat(mContext, mNetd, mTrackerHandler, nai);
            nai.clatd.start();
        } else if (wasRunningClat && !shouldRunClat) {
            nai.clatd.stop();
        }
    }

    private void updateInterfaces(LinkProperties newLp, LinkProperties oldLp, int netId) {
        CompareResult<String> interfaceDiff = new CompareResult<String>();
        if (oldLp != null) {
            interfaceDiff = oldLp.compareAllInterfaceNames(newLp);
        } else if (newLp != null) {
            interfaceDiff.added = newLp.getAllInterfaceNames();
        }
        for (String iface : interfaceDiff.added) {
            try {
                if (DBG) log("Adding iface " + iface + " to network " + netId);
                mNetd.addInterfaceToNetwork(iface, netId);
            } catch (Exception e) {
                loge("Exception adding interface: " + e);
            }
        }
        for (String iface : interfaceDiff.removed) {
            try {
                if (DBG) log("Removing iface " + iface + " from network " + netId);
                mNetd.removeInterfaceFromNetwork(iface, netId);
            } catch (Exception e) {
                loge("Exception removing interface: " + e);
            }
        }
    }

    /**
     * Have netd update routes from oldLp to newLp.
     * @return true if routes changed between oldLp and newLp
     */
    private boolean updateRoutes(LinkProperties newLp, LinkProperties oldLp, int netId) {
        CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
        if (oldLp != null) {
            routeDiff = oldLp.compareAllRoutes(newLp);
        } else if (newLp != null) {
            routeDiff.added = newLp.getAllRoutes();
        }

        // add routes before removing old in case it helps with continuous connectivity

        // do this twice, adding non-nexthop routes first, then routes they are dependent on
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway()) continue;
            if (DBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNetd.addRoute(netId, route);
            } catch (Exception e) {
                if ((route.getDestination().getAddress() instanceof Inet4Address) || VDBG) {
                    loge("Exception in addRoute for non-gateway: " + e);
                }
            }
        }
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway() == false) continue;
            if (DBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNetd.addRoute(netId, route);
            } catch (Exception e) {
                if ((route.getGateway() instanceof Inet4Address) || VDBG) {
                    loge("Exception in addRoute for gateway: " + e);
                }
            }
        }

        for (RouteInfo route : routeDiff.removed) {
            if (DBG) log("Removing Route [" + route + "] from network " + netId);
            try {
                mNetd.removeRoute(netId, route);
            } catch (Exception e) {
                loge("Exception in removeRoute: " + e);
            }
        }
        return !routeDiff.added.isEmpty() || !routeDiff.removed.isEmpty();
    }
    private void updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId,
                             boolean flush, boolean useDefaultDns) {
        if (oldLp == null || (newLp.isIdenticalDnses(oldLp) == false)) {
            Collection<InetAddress> dnses = newLp.getDnsServers();
            if (dnses.size() == 0 && mDefaultDns != null && useDefaultDns) {
                dnses = new ArrayList();
                dnses.add(mDefaultDns);
                if (DBG) {
                    loge("no dns provided for netId " + netId + ", so using defaults");
                }
            }
            if (DBG) log("Setting Dns servers for network " + netId + " to " + dnses);
            try {
                mNetd.setDnsServersForNetwork(netId, NetworkUtils.makeStrings(dnses),
                    newLp.getDomains());
            } catch (Exception e) {
                loge("Exception in setDnsServersForNetwork: " + e);
            }
            NetworkAgentInfo defaultNai = mNetworkForRequestId.get(mDefaultRequest.requestId);
            if (defaultNai != null && defaultNai.network.netId == netId) {
                setDefaultDnsSystemProperties(dnses);
            }
            flushVmDnsCache();
        } else if (flush) {
            try {
                mNetd.flushNetworkDnsCache(netId);
            } catch (Exception e) {
                loge("Exception in flushNetworkDnsCache: " + e);
            }
            flushVmDnsCache();
        }
    }

    private void setDefaultDnsSystemProperties(Collection<InetAddress> dnses) {
        int last = 0;
        for (InetAddress dns : dnses) {
            ++last;
            String key = "net.dns" + last;
            String value = dns.getHostAddress();
            SystemProperties.set(key, value);
        }
        for (int i = last + 1; i <= mNumDnsEntries; ++i) {
            String key = "net.dns" + i;
            SystemProperties.set(key, "");
        }
        mNumDnsEntries = last;
    }

    private void updateCapabilities(NetworkAgentInfo networkAgent,
            NetworkCapabilities networkCapabilities) {
        if (!Objects.equals(networkAgent.networkCapabilities, networkCapabilities)) {
            synchronized (networkAgent) {
                networkAgent.networkCapabilities = networkCapabilities;
            }
            rematchAllNetworksAndRequests(networkAgent, networkAgent.getCurrentScore());
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_CAP_CHANGED);
        }
    }

    private void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.networkRequests.size(); i++) {
            NetworkRequest nr = nai.networkRequests.valueAt(i);
            // Don't send listening requests to factories. b/17393458
            if (!isRequest(nr)) continue;
            sendUpdatedScoreToFactories(nr, nai.getCurrentScore());
        }
    }

    private void sendUpdatedScoreToFactories(NetworkRequest networkRequest, int score) {
        if (VDBG) log("sending new Min Network Score(" + score + "): " + networkRequest.toString());
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score, 0,
                    networkRequest);
        }
    }

    private void sendPendingIntentForRequest(NetworkRequestInfo nri, NetworkAgentInfo networkAgent,
            int notificationType) {
        if (notificationType == ConnectivityManager.CALLBACK_AVAILABLE && !nri.mPendingIntentSent) {
            Intent intent = new Intent();
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK, networkAgent.network);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_REQUEST, nri.request);
            nri.mPendingIntentSent = true;
            sendIntent(nri.mPendingIntent, intent);
        }
        // else not handled
    }

    private void sendIntent(PendingIntent pendingIntent, Intent intent) {
        mPendingIntentWakeLock.acquire();
        try {
            if (DBG) log("Sending " + pendingIntent);
            pendingIntent.send(mContext, 0, intent, this /* onFinished */, null /* Handler */);
        } catch (PendingIntent.CanceledException e) {
            if (DBG) log(pendingIntent + " was not sent, it had been canceled.");
            mPendingIntentWakeLock.release();
            releasePendingNetworkRequest(pendingIntent);
        }
        // ...otherwise, mPendingIntentWakeLock.release() gets called by onSendFinished()
    }

    @Override
    public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode,
            String resultData, Bundle resultExtras) {
        if (DBG) log("Finished sending " + pendingIntent);
        mPendingIntentWakeLock.release();
        // Release with a delay so the receiving client has an opportunity to put in its
        // own request.
        releasePendingNetworkRequestWithDelay(pendingIntent);
    }

    private void callCallbackForRequest(NetworkRequestInfo nri,
            NetworkAgentInfo networkAgent, int notificationType) {
        if (nri.messenger == null) return;  // Default request has no msgr
        Bundle bundle = new Bundle();
        bundle.putParcelable(NetworkRequest.class.getSimpleName(),
                new NetworkRequest(nri.request));
        Message msg = Message.obtain();
        if (notificationType != ConnectivityManager.CALLBACK_UNAVAIL &&
                notificationType != ConnectivityManager.CALLBACK_RELEASED) {
            bundle.putParcelable(Network.class.getSimpleName(), networkAgent.network);
        }
        switch (notificationType) {
            case ConnectivityManager.CALLBACK_LOSING: {
                msg.arg1 = 30 * 1000; // TODO - read this from NetworkMonitor
                break;
            }
            case ConnectivityManager.CALLBACK_CAP_CHANGED: {
                bundle.putParcelable(NetworkCapabilities.class.getSimpleName(),
                        new NetworkCapabilities(networkAgent.networkCapabilities));
                break;
            }
            case ConnectivityManager.CALLBACK_IP_CHANGED: {
                bundle.putParcelable(LinkProperties.class.getSimpleName(),
                        new LinkProperties(networkAgent.linkProperties));
                break;
            }
        }
        msg.what = notificationType;
        msg.setData(bundle);
        try {
            if (VDBG) {
                log("sending notification " + notifyTypeToName(notificationType) +
                        " for " + nri.request);
            }
            nri.messenger.send(msg);
        } catch (RemoteException e) {
            // may occur naturally in the race of binder death.
            loge("RemoteException caught trying to send a callback msg for " + nri.request);
        }
    }

    private void teardownUnneededNetwork(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.networkRequests.size(); i++) {
            NetworkRequest nr = nai.networkRequests.valueAt(i);
            // Ignore listening requests.
            if (!isRequest(nr)) continue;
            loge("Dead network still had at least " + nr);
            break;
        }
        nai.asyncChannel.disconnect();
    }

    private void handleLingerComplete(NetworkAgentInfo oldNetwork) {
        if (oldNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleLingerComplete");
            return;
        }
        if (DBG) log("handleLingerComplete for " + oldNetwork.name());
        teardownUnneededNetwork(oldNetwork);
    }

    private void makeDefault(NetworkAgentInfo newNetwork) {
        if (DBG) log("Switching to new default network: " + newNetwork);
        setupDataActivityTracking(newNetwork);
        try {
            mNetd.setDefaultNetId(newNetwork.network.netId);
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }
        notifyLockdownVpn(newNetwork);
        handleApplyDefaultProxy(newNetwork.linkProperties.getHttpProxy());
        updateTcpBufferSizes(newNetwork);
        setDefaultDnsSystemProperties(newNetwork.linkProperties.getDnsServers());
    }

    // Handles a network appearing or improving its score.
    //
    // - Evaluates all current NetworkRequests that can be
    //   satisfied by newNetwork, and reassigns to newNetwork
    //   any such requests for which newNetwork is the best.
    //
    // - Lingers any validated Networks that as a result are no longer
    //   needed. A network is needed if it is the best network for
    //   one or more NetworkRequests, or if it is a VPN.
    //
    // - Tears down newNetwork if it just became validated
    //   (i.e. nascent==JUST_VALIDATED) but turns out to be unneeded.
    //
    // - If reapUnvalidatedNetworks==REAP, tears down unvalidated
    //   networks that have no chance (i.e. even if validated)
    //   of becoming the highest scoring network.
    //
    // NOTE: This function only adds NetworkRequests that "newNetwork" could satisfy,
    // it does not remove NetworkRequests that other Networks could better satisfy.
    // If you need to handle decreases in score, use {@link rematchAllNetworksAndRequests}.
    // This function should be used when possible instead of {@code rematchAllNetworksAndRequests}
    // as it performs better by a factor of the number of Networks.
    //
    // @param newNetwork is the network to be matched against NetworkRequests.
    // @param nascent indicates if newNetwork just became validated, in which case it should be
    //               torn down if unneeded.
    // @param reapUnvalidatedNetworks indicates if an additional pass over all networks should be
    //               performed to tear down unvalidated networks that have no chance (i.e. even if
    //               validated) of becoming the highest scoring network.
    private void rematchNetworkAndRequests(NetworkAgentInfo newNetwork, NascentState nascent,
            ReapUnvalidatedNetworks reapUnvalidatedNetworks) {
        if (!newNetwork.created) return;
        if (nascent == NascentState.JUST_VALIDATED && !newNetwork.everValidated) {
            loge("ERROR: nascent network not validated.");
        }
        boolean keep = newNetwork.isVPN();
        boolean isNewDefault = false;
        NetworkAgentInfo oldDefaultNetwork = null;
        if (DBG) log("rematching " + newNetwork.name());
        // Find and migrate to this Network any NetworkRequests for
        // which this network is now the best.
        ArrayList<NetworkAgentInfo> affectedNetworks = new ArrayList<NetworkAgentInfo>();
        if (VDBG) log(" network has: " + newNetwork.networkCapabilities);
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkAgentInfo currentNetwork = mNetworkForRequestId.get(nri.request.requestId);
            if (newNetwork == currentNetwork) {
                if (DBG) {
                    log("Network " + newNetwork.name() + " was already satisfying" +
                            " request " + nri.request.requestId + ". No change.");
                }
                keep = true;
                continue;
            }

            // check if it satisfies the NetworkCapabilities
            if (VDBG) log("  checking if request is satisfied: " + nri.request);
            if (newNetwork.satisfies(nri.request)) {
                if (!nri.isRequest) {
                    // This is not a request, it's a callback listener.
                    // Add it to newNetwork regardless of score.
                    newNetwork.addRequest(nri.request);
                    continue;
                }

                // next check if it's better than any current network we're using for
                // this request
                if (VDBG) {
                    log("currentScore = " +
                            (currentNetwork != null ? currentNetwork.getCurrentScore() : 0) +
                            ", newScore = " + newNetwork.getCurrentScore());
                }
                if (currentNetwork == null ||
                        currentNetwork.getCurrentScore() < newNetwork.getCurrentScore()) {
                    if (currentNetwork != null) {
                        if (DBG) log("   accepting network in place of " + currentNetwork.name());
                        currentNetwork.networkRequests.remove(nri.request.requestId);
                        currentNetwork.networkLingered.add(nri.request);
                        affectedNetworks.add(currentNetwork);
                    } else {
                        if (DBG) log("   accepting network in place of null");
                    }
                    unlinger(newNetwork);
                    mNetworkForRequestId.put(nri.request.requestId, newNetwork);
                    newNetwork.addRequest(nri.request);
                    keep = true;
                    // Tell NetworkFactories about the new score, so they can stop
                    // trying to connect if they know they cannot match it.
                    // TODO - this could get expensive if we have alot of requests for this
                    // network.  Think about if there is a way to reduce this.  Push
                    // netid->request mapping to each factory?
                    sendUpdatedScoreToFactories(nri.request, newNetwork.getCurrentScore());
                    if (mDefaultRequest.requestId == nri.request.requestId) {
                        isNewDefault = true;
                        oldDefaultNetwork = currentNetwork;
                    }
                }
            }
        }
        // Linger any networks that are no longer needed.
        for (NetworkAgentInfo nai : affectedNetworks) {
            if (nai.everValidated && unneeded(nai)) {
                nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_LINGER);
                notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOSING);
            } else {
                unlinger(nai);
            }
        }
        if (keep) {
            if (isNewDefault) {
                // Notify system services that this network is up.
                makeDefault(newNetwork);
                synchronized (ConnectivityService.this) {
                    // have a new default network, release the transition wakelock in
                    // a second if it's held.  The second pause is to allow apps
                    // to reconnect over the new network
                    if (mNetTransitionWakeLock.isHeld()) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                                mNetTransitionWakeLockSerialNumber, 0),
                                1000);
                    }
                }
            }

            // do this after the default net is switched, but
            // before LegacyTypeTracker sends legacy broadcasts
            notifyNetworkCallbacks(newNetwork, ConnectivityManager.CALLBACK_AVAILABLE);

            if (isNewDefault) {
                // Maintain the illusion: since the legacy API only
                // understands one network at a time, we must pretend
                // that the current default network disconnected before
                // the new one connected.
                if (oldDefaultNetwork != null) {
                    mLegacyTypeTracker.remove(oldDefaultNetwork.networkInfo.getType(),
                                              oldDefaultNetwork);
                }
                mDefaultInetConditionPublished = newNetwork.everValidated ? 100 : 0;
                mLegacyTypeTracker.add(newNetwork.networkInfo.getType(), newNetwork);
                notifyLockdownVpn(newNetwork);
            }

            // Notify battery stats service about this network, both the normal
            // interface and any stacked links.
            // TODO: Avoid redoing this; this must only be done once when a network comes online.
            try {
                final IBatteryStats bs = BatteryStatsService.getService();
                final int type = newNetwork.networkInfo.getType();

                final String baseIface = newNetwork.linkProperties.getInterfaceName();
                bs.noteNetworkInterfaceType(baseIface, type);
                for (LinkProperties stacked : newNetwork.linkProperties.getStackedLinks()) {
                    final String stackedIface = stacked.getInterfaceName();
                    bs.noteNetworkInterfaceType(stackedIface, type);
                    NetworkStatsFactory.noteStackedIface(stackedIface, baseIface);
                }
            } catch (RemoteException ignored) {
            }

            // This has to happen after the notifyNetworkCallbacks as that tickles each
            // ConnectivityManager instance so that legacy requests correctly bind dns
            // requests to this network.  The legacy users are listening for this bcast
            // and will generally do a dns request so they can ensureRouteToHost and if
            // they do that before the callbacks happen they'll use the default network.
            //
            // TODO: Is there still a race here? We send the broadcast
            // after sending the callback, but if the app can receive the
            // broadcast before the callback, it might still break.
            //
            // This *does* introduce a race where if the user uses the new api
            // (notification callbacks) and then uses the old api (getNetworkInfo(type))
            // they may get old info.  Reverse this after the old startUsing api is removed.
            // This is on top of the multiple intent sequencing referenced in the todo above.
            for (int i = 0; i < newNetwork.networkRequests.size(); i++) {
                NetworkRequest nr = newNetwork.networkRequests.valueAt(i);
                if (nr.legacyType != TYPE_NONE && isRequest(nr)) {
                    // legacy type tracker filters out repeat adds
                    mLegacyTypeTracker.add(nr.legacyType, newNetwork);
                }
            }

            // A VPN generally won't get added to the legacy tracker in the "for (nri)" loop above,
            // because usually there are no NetworkRequests it satisfies (e.g., mDefaultRequest
            // wants the NOT_VPN capability, so it will never be satisfied by a VPN). So, add the
            // newNetwork to the tracker explicitly (it's a no-op if it has already been added).
            if (newNetwork.isVPN()) {
                mLegacyTypeTracker.add(TYPE_VPN, newNetwork);
            }
        } else if (nascent == NascentState.JUST_VALIDATED) {
            // Only tear down newly validated networks here.  Leave unvalidated to either become
            // validated (and get evaluated against peers, one losing here), or get reaped (see
            // reapUnvalidatedNetworks) if they have no chance of becoming the highest scoring
            // network.  Networks that have been up for a while and are validated should be torn
            // down via the lingering process so communication on that network is given time to
            // wrap up.
            if (DBG) log("Validated network turns out to be unwanted.  Tear it down.");
            teardownUnneededNetwork(newNetwork);
        }
        if (reapUnvalidatedNetworks == ReapUnvalidatedNetworks.REAP) {
            for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                if (!nai.everValidated && unneeded(nai)) {
                    if (DBG) log("Reaping " + nai.name());
                    teardownUnneededNetwork(nai);
                }
            }
        }
    }

    // Attempt to rematch all Networks with NetworkRequests.  This may result in Networks
    // being disconnected.
    // If only one Network's score or capabilities have been modified since the last time
    // this function was called, pass this Network in via the "changed" arugment, otherwise
    // pass null.
    // If only one Network has been changed but its NetworkCapabilities have not changed,
    // pass in the Network's score (from getCurrentScore()) prior to the change via
    // "oldScore", otherwise pass changed.getCurrentScore() or 0 if "changed" is null.
    private void rematchAllNetworksAndRequests(NetworkAgentInfo changed, int oldScore) {
        // TODO: This may get slow.  The "changed" parameter is provided for future optimization
        // to avoid the slowness.  It is not simply enough to process just "changed", for
        // example in the case where "changed"'s score decreases and another network should begin
        // satifying a NetworkRequest that "changed" currently satisfies.

        // Optimization: Only reprocess "changed" if its score improved.  This is safe because it
        // can only add more NetworkRequests satisfied by "changed", and this is exactly what
        // rematchNetworkAndRequests() handles.
        if (changed != null && oldScore < changed.getCurrentScore()) {
            rematchNetworkAndRequests(changed, NascentState.NOT_JUST_VALIDATED,
                    ReapUnvalidatedNetworks.REAP);
        } else {
            for (Iterator i = mNetworkAgentInfos.values().iterator(); i.hasNext(); ) {
                rematchNetworkAndRequests((NetworkAgentInfo)i.next(),
                        NascentState.NOT_JUST_VALIDATED,
                        // Only reap the last time through the loop.  Reaping before all rematching
                        // is complete could incorrectly teardown a network that hasn't yet been
                        // rematched.
                        i.hasNext() ? ReapUnvalidatedNetworks.DONT_REAP
                                : ReapUnvalidatedNetworks.REAP);
            }
        }
    }

    private void updateInetCondition(NetworkAgentInfo nai) {
        // Don't bother updating until we've graduated to validated at least once.
        if (!nai.everValidated) return;
        // For now only update icons for default connection.
        // TODO: Update WiFi and cellular icons separately. b/17237507
        if (!isDefaultNetwork(nai)) return;

        int newInetCondition = nai.lastValidated ? 100 : 0;
        // Don't repeat publish.
        if (newInetCondition == mDefaultInetConditionPublished) return;

        mDefaultInetConditionPublished = newInetCondition;
        sendInetConditionBroadcast(nai.networkInfo);
    }

    private void notifyLockdownVpn(NetworkAgentInfo nai) {
        if (mLockdownTracker != null) {
            if (nai != null && nai.isVPN()) {
                mLockdownTracker.onVpnStateChanged(nai.networkInfo);
            } else {
                mLockdownTracker.onNetworkInfoChanged();
            }
        }
    }

    private void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo newInfo) {
        NetworkInfo.State state = newInfo.getState();
        NetworkInfo oldInfo = null;
        synchronized (networkAgent) {
            oldInfo = networkAgent.networkInfo;
            networkAgent.networkInfo = newInfo;
        }
        notifyLockdownVpn(networkAgent);

        if (oldInfo != null && oldInfo.getState() == state) {
            if (VDBG) log("ignoring duplicate network state non-change");
            return;
        }
        if (DBG) {
            log(networkAgent.name() + " EVENT_NETWORK_INFO_CHANGED, going from " +
                    (oldInfo == null ? "null" : oldInfo.getState()) +
                    " to " + state);
        }

        if (state == NetworkInfo.State.CONNECTED && !networkAgent.created) {
            try {
                // This should never fail.  Specifying an already in use NetID will cause failure.
                if (networkAgent.isVPN()) {
                    mNetd.createVirtualNetwork(networkAgent.network.netId,
                            !networkAgent.linkProperties.getDnsServers().isEmpty(),
                            (networkAgent.networkMisc == null ||
                                !networkAgent.networkMisc.allowBypass));
                } else {
                    mNetd.createPhysicalNetwork(networkAgent.network.netId);
                }
            } catch (Exception e) {
                loge("Error creating network " + networkAgent.network.netId + ": "
                        + e.getMessage());
                return;
            }
            networkAgent.created = true;
            updateLinkProperties(networkAgent, null);
            notifyIfacesChanged();
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_PRECHECK);
            networkAgent.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
            if (networkAgent.isVPN()) {
                // Temporarily disable the default proxy (not global).
                synchronized (mProxyLock) {
                    if (!mDefaultProxyDisabled) {
                        mDefaultProxyDisabled = true;
                        if (mGlobalProxy == null && mDefaultProxy != null) {
                            sendProxyBroadcast(null);
                        }
                    }
                }
                // TODO: support proxy per network.
            }
            // Consider network even though it is not yet validated.
            rematchNetworkAndRequests(networkAgent, NascentState.NOT_JUST_VALIDATED,
                    ReapUnvalidatedNetworks.REAP);
        } else if (state == NetworkInfo.State.DISCONNECTED ||
                state == NetworkInfo.State.SUSPENDED) {
            networkAgent.asyncChannel.disconnect();
            if (networkAgent.isVPN()) {
                synchronized (mProxyLock) {
                    if (mDefaultProxyDisabled) {
                        mDefaultProxyDisabled = false;
                        if (mGlobalProxy == null && mDefaultProxy != null) {
                            sendProxyBroadcast(mDefaultProxy);
                        }
                    }
                }
            }
        }
    }

    private void updateNetworkScore(NetworkAgentInfo nai, int score) {
        if (DBG) log("updateNetworkScore for " + nai.name() + " to " + score);
        if (score < 0) {
            loge("updateNetworkScore for " + nai.name() + " got a negative score (" + score +
                    ").  Bumping score to min of 0");
            score = 0;
        }

        final int oldScore = nai.getCurrentScore();
        nai.setCurrentScore(score);

        rematchAllNetworksAndRequests(nai, oldScore);

        sendUpdatedScoreToFactories(nai);
    }

    // notify only this one new request of the current state
    protected void notifyNetworkCallback(NetworkAgentInfo nai, NetworkRequestInfo nri) {
        int notifyType = ConnectivityManager.CALLBACK_AVAILABLE;
        // TODO - read state from monitor to decide what to send.
//        if (nai.networkMonitor.isLingering()) {
//            notifyType = NetworkCallbacks.LOSING;
//        } else if (nai.networkMonitor.isEvaluating()) {
//            notifyType = NetworkCallbacks.callCallbackForRequest(request, nai, notifyType);
//        }
        if (nri.mPendingIntent == null) {
            callCallbackForRequest(nri, nai, notifyType);
        } else {
            sendPendingIntentForRequest(nri, nai, notifyType);
        }
    }

    private void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, boolean connected, int type) {
        // The NetworkInfo we actually send out has no bearing on the real
        // state of affairs. For example, if the default connection is mobile,
        // and a request for HIPRI has just gone away, we need to pretend that
        // HIPRI has just disconnected. So we need to set the type to HIPRI and
        // the state to DISCONNECTED, even though the network is of type MOBILE
        // and is still connected.
        NetworkInfo info = new NetworkInfo(nai.networkInfo);
        info.setType(type);
        if (connected) {
            info.setDetailedState(DetailedState.CONNECTED, null, info.getExtraInfo());
            sendConnectedBroadcast(info);
        } else {
            info.setDetailedState(DetailedState.DISCONNECTED, null, info.getExtraInfo());
            Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
            if (info.isFailover()) {
                intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                nai.networkInfo.setFailover(false);
            }
            if (info.getReason() != null) {
                intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
            }
            if (info.getExtraInfo() != null) {
                intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, info.getExtraInfo());
            }
            NetworkAgentInfo newDefaultAgent = null;
            if (nai.networkRequests.get(mDefaultRequest.requestId) != null) {
                newDefaultAgent = mNetworkForRequestId.get(mDefaultRequest.requestId);
                if (newDefaultAgent != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO,
                            newDefaultAgent.networkInfo);
                } else {
                    intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
                }
            }
            intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                    mDefaultInetConditionPublished);
            final Intent immediateIntent = new Intent(intent);
            immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
            sendStickyBroadcast(immediateIntent);
            sendStickyBroadcast(intent);
            if (newDefaultAgent != null) {
                sendConnectedBroadcast(newDefaultAgent.networkInfo);
            }
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType) {
        if (DBG) log("notifyType " + notifyTypeToName(notifyType) + " for " + networkAgent.name());
        for (int i = 0; i < networkAgent.networkRequests.size(); i++) {
            NetworkRequest nr = networkAgent.networkRequests.valueAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            if (VDBG) log(" sending notification for " + nr);
            if (nri.mPendingIntent == null) {
                callCallbackForRequest(nri, networkAgent, notifyType);
            } else {
                sendPendingIntentForRequest(nri, networkAgent, notifyType);
            }
        }
    }

    private String notifyTypeToName(int notifyType) {
        switch (notifyType) {
            case ConnectivityManager.CALLBACK_PRECHECK:    return "PRECHECK";
            case ConnectivityManager.CALLBACK_AVAILABLE:   return "AVAILABLE";
            case ConnectivityManager.CALLBACK_LOSING:      return "LOSING";
            case ConnectivityManager.CALLBACK_LOST:        return "LOST";
            case ConnectivityManager.CALLBACK_UNAVAIL:     return "UNAVAILABLE";
            case ConnectivityManager.CALLBACK_CAP_CHANGED: return "CAP_CHANGED";
            case ConnectivityManager.CALLBACK_IP_CHANGED:  return "IP_CHANGED";
            case ConnectivityManager.CALLBACK_RELEASED:    return "RELEASED";
        }
        return "UNKNOWN";
    }

    /**
     * Notify other system services that set of active ifaces has changed.
     */
    private void notifyIfacesChanged() {
        try {
            mStatsService.forceUpdateIfaces();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean addVpnAddress(String address, int prefixLength) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).addAddress(address, prefixLength);
        }
    }

    @Override
    public boolean removeVpnAddress(String address, int prefixLength) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).removeAddress(address, prefixLength);
        }
    }

    @Override
    public boolean setUnderlyingNetworksForVpn(Network[] networks) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).setUnderlyingNetworks(networks);
        }
    }
}
